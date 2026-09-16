#!/usr/bin/env bash
#
# 构建可分发 release 产物 (本地与 GitHub Actions 通用)
#
# 用法:
#   ./scripts/build-release.sh                              # 本机 target，跑测试
#   ./scripts/build-release.sh --musl                       # x86_64 静态 musl
#   ./scripts/build-release.sh --target aarch64-unknown-linux-gnu
#   ./scripts/build-release.sh --version 0.2.0 --out dist
#   ./scripts/build-release.sh --no-tests --no-strip
#
# 产物 (默认输出到 backend/dist/):
#   market-event-analyzer-<version>-<target>.tar.gz          二进制 + config.toml + rules.toml
#   market-event-analyzer-<version>-<target>.tar.gz.sha256   校验和
#
# 版本号优先级: --version > $RELEASE_VERSION > git tag (CI 的 tag) > Cargo.toml

set -Eeuo pipefail

BIN_NAME="market-event-analyzer"
PKG_NAME="market-event-analyzer"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

OUT_DIR=""
TARGET=""
VERSION=""
RUN_TESTS=1
DO_STRIP=1

c_reset=$'\033[0m'; c_red=$'\033[1;31m'; c_green=$'\033[1;32m'; c_yellow=$'\033[1;33m'; c_blue=$'\033[1;34m'
log()  { printf '%s[build]%s %s\n' "$c_green" "$c_reset" "$*"; }
info() { printf '%s[build]%s %s\n' "$c_blue" "$c_reset" "$*"; }
warn() { printf '%s[build]%s %s\n' "$c_yellow" "$c_reset" "$*" >&2; }
die()  { printf '%s[build]%s %s\n' "$c_red" "$c_reset" "$*" >&2; exit 1; }

usage() {
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "${BASH_SOURCE[0]}"
    cat <<'EOF'

选项:
  --target <TRIPLE>  交叉/指定目标 (默认 rustc 宿主 target)
  --musl             等价于 --target x86_64-unknown-linux-musl (静态链接)
  --out <DIR>        产物输出目录 (默认 backend/dist)
  --version <V>      覆盖版本号
  --no-tests         跳过 cargo test
  --no-strip         不裁剪二进制符号
  -h, --help         显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)   TARGET="${2:?--target 需要参数}"; shift 2 ;;
        --musl)     TARGET="x86_64-unknown-linux-musl"; shift ;;
        --out)      OUT_DIR="${2:?--out 需要参数}"; shift 2 ;;
        --version)  VERSION="${2:?--version 需要参数}"; shift 2 ;;
        --no-tests) RUN_TESTS=0; shift ;;
        --no-strip) DO_STRIP=0; shift ;;
        -h|--help)  usage; exit 0 ;;
        *) die "未知参数: $1 (使用 --help 查看用法)" ;;
    esac
done

# ---------------------------------------------------------------------------
# 工具链检查
# ---------------------------------------------------------------------------
command -v cargo >/dev/null 2>&1 || die "未找到 cargo；请先安装 Rust: https://rustup.rs"
command -v rustc >/dev/null 2>&1 || die "未找到 rustc"

version_ge() { # $1 >= $2
    [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" == "$2" ]]
}
rustc_ver="$(rustc --version | awk '{print $2}')"
version_ge "${rustc_ver}" "1.85.0" || die "rustc ${rustc_ver} 过旧，本项目使用 edition 2024，需要 >= 1.85.0"
info "rustc ${rustc_ver}"

[[ -n "${TARGET}" ]] || TARGET="$(rustc -vV | awk '/^host:/{ print $2 }')"
info "target: ${TARGET}"

# ---------------------------------------------------------------------------
# 版本号
# ---------------------------------------------------------------------------
resolve_version() {
    if [[ -n "${VERSION}" ]]; then printf '%s' "${VERSION}"; return; fi
    if [[ -n "${RELEASE_VERSION:-}" ]]; then printf '%s' "${RELEASE_VERSION}"; return; fi
    if [[ "${GITHUB_REF_TYPE:-}" == "tag" && -n "${GITHUB_REF_NAME:-}" ]]; then
        printf '%s' "${GITHUB_REF_NAME#v}"; return
    fi
    local tag
    tag="$(git -C "${BACKEND_DIR}" describe --tags --match 'v*' --abbrev=0 2>/dev/null || true)"
    if [[ -n "${tag}" ]]; then printf '%s' "${tag#v}"; return; fi
    awk -F'"' '/^version[[:space:]]*=/{ print $2; exit }' "${BACKEND_DIR}/Cargo.toml"
}
VERSION="$(resolve_version)"
[[ -n "${VERSION}" ]] || die "无法确定版本号，请用 --version 指定"

# ---------------------------------------------------------------------------
# 输出目录 (解析为绝对路径，避免后续 cd 后含义变化)
# ---------------------------------------------------------------------------
if [[ -z "${OUT_DIR}" ]]; then OUT_DIR="${BACKEND_DIR}/dist"; fi
mkdir -p "${OUT_DIR}"
OUT_DIR="$(cd -- "${OUT_DIR}" && pwd)"

# ---------------------------------------------------------------------------
# musl 交叉编译需要的 C 编译器
# ---------------------------------------------------------------------------
if [[ "${TARGET}" == "x86_64-unknown-linux-musl" ]]; then
    if [[ -z "${CC_x86_64_unknown_linux_musl:-}" ]] && command -v musl-gcc >/dev/null 2>&1; then
        export CC_x86_64_unknown_linux_musl="musl-gcc"
    fi
    command -v musl-gcc >/dev/null 2>&1 || warn "未找到 musl-gcc (Debian/Ubuntu: apt-get install musl-tools)，构建可能失败"
fi

# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------
cd "${BACKEND_DIR}"
target_args=()
if [[ -n "${TARGET}" ]]; then target_args=(--target "${TARGET}"); fi

if [[ "${RUN_TESTS}" -eq 1 ]]; then
    log "cargo test --locked ${target_args[*]:-}"
    cargo test --locked "${target_args[@]}"
fi

log "cargo build --release --locked ${target_args[*]}"
cargo build --release --locked "${target_args[@]}"

BIN_PATH="${BACKEND_DIR}/target/${TARGET}/release/${BIN_NAME}"
[[ -f "${BIN_PATH}" ]] || die "构建产物不存在: ${BIN_PATH}"

# ---------------------------------------------------------------------------
# 裁剪符号
# ---------------------------------------------------------------------------
if [[ "${DO_STRIP}" -eq 1 ]]; then
    strip_cmd="strip"
    if [[ "${TARGET}" == aarch64* ]] && command -v aarch64-linux-gnu-strip >/dev/null 2>&1; then
        strip_cmd="aarch64-linux-gnu-strip"
    fi
    if command -v "${strip_cmd}" >/dev/null 2>&1; then
        if "${strip_cmd}" "${BIN_PATH}"; then
            info "已 strip: ${strip_cmd}"
        else
            warn "strip 失败，保留未裁剪二进制"
        fi
    else
        warn "未找到 ${strip_cmd}，保留未裁剪二进制"
    fi
fi

# ---------------------------------------------------------------------------
# 打包
# ---------------------------------------------------------------------------
hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}

pkg_dir="${OUT_DIR}/${PKG_NAME}-${VERSION}-${TARGET}"
archive="${OUT_DIR}/${PKG_NAME}-${VERSION}-${TARGET}.tar.gz"

rm -rf "${pkg_dir}" "${archive}" "${archive}.sha256"
mkdir -p "${pkg_dir}"
install -m 0755 "${BIN_PATH}"                "${pkg_dir}/${BIN_NAME}"
install -m 0644 "${BACKEND_DIR}/config.toml" "${pkg_dir}/config.toml"
install -m 0644 "${BACKEND_DIR}/rules.toml"  "${pkg_dir}/rules.toml"

cat > "${pkg_dir}/README.txt" <<EOF
${PKG_NAME} ${VERSION} (${TARGET})
构建时间: $(date -u '+%Y-%m-%dT%H:%M:%SZ')

文件:
  ${BIN_NAME}    后端可执行文件
  config.toml    服务配置 (端口、数据库、上游源、鉴权、配额、告警、Telegram)
  rules.toml     指标规则

直接运行:
  ./${BIN_NAME}
  默认读取当前目录的 config.toml 与 rules.toml，首次启动自动创建 data/market.db 并执行迁移。
  默认监听 127.0.0.1:8080，请用 Caddy/Nginx 对外终结 TLS。

环境变量:
  APP_CONFIG                指定配置文件路径 (默认 ./config.toml)
  RUST_LOG                  日志级别，如 market_event_analyzer=info,tower_http=info
  API_TOKENS                客户端访问令牌 name:token,name:token (auth.enabled = true 时必需)
  OPENAI_API_KEY            事件名翻译与 AI 分析 (translation / ai 开启时必需)
  TELEGRAM_BOT_TOKEN        Telegram 机器人令牌
  TELEGRAM_ADMIN_CHAT_IDS   管理员 chat id 白名单，逗号分隔
  TE_API_KEY                仅 --backfill 历史补采需要

用 systemd 安装 (推荐，需要仓库的 deploy/deploy.sh):
  sudo API_TOKENS="alice:$(openssl rand -hex 24)" ./deploy.sh deploy --binary /path/to/${BIN_NAME} --proxy http://127.0.0.1:7890
EOF

tar -C "${OUT_DIR}" -czf "${archive}" "$(basename "${pkg_dir}")"
rm -rf "${pkg_dir}"

( cd "${OUT_DIR}" && hash_file "$(basename "${archive}")" > "$(basename "${archive}").sha256" )

log "构建完成"
info "产物: ${archive}"
info "大小: $(du -h "${archive}" | cut -f1)"
cat "${archive}.sha256"
