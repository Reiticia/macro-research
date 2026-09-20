#!/usr/bin/env bash
#
# Market Event Analyzer - Linux 服务器部署脚本 (systemd)
#
# 用法:
#   sudo ./deploy.sh                     # 构建并部署/升级 systemd 服务
#   sudo ./deploy.sh deploy --port 9000  # 指定监听端口
#   sudo ./deploy.sh deploy --binary ./market-event-analyzer   # 使用预编译二进制
#   sudo ./deploy.sh status              # 查看服务状态、健康检查与配置概览
#   sudo ./deploy.sh logs                # 跟踪服务日志
#   sudo ./deploy.sh check-ai            # 中转站/模型连通性自检
#   sudo ./deploy.sh backfill            # 补采最近三个月 (需 [backfill] te_api_key)
#   sudo ./deploy.sh backfill 2026-06-08 2026-09-07
#   sudo ./deploy.sh uninstall --purge   # 卸载服务并删除数据
#
# 说明:
#   * 迁移文件通过 sqlx::migrate! 编译进二进制，首次启动自动建库/迁移。
#   * config.toml 与 rules.toml 必须与二进制同目录 (WorkingDirectory)。
#   * 密钥只写入 EnvironmentFile (/etc/market-analyzer/market-analyzer.env)，权限 0640。
#   * 重复执行 deploy 即为升级：先停服 -> 备份 SQLite -> 替换二进制 -> 启动并健康检查。

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# 可配置项 (可用环境变量覆盖)
# ---------------------------------------------------------------------------
APP_NAME="market-event-analyzer"
SERVICE_NAME="${SERVICE_NAME:-market-event-analyzer}"
APP_USER="${APP_USER:-market}"
APP_DIR="${APP_DIR:-/opt/market-analyzer}"
CONF_DIR="${CONF_DIR:-/etc/market-analyzer}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/market-analyzer}"
BACKUP_KEEP="${BACKUP_KEEP:-10}"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
RUST_LOG="${RUST_LOG:-market_event_analyzer=info,tower_http=info}"

DO_BACKUP=1
ENABLE_TRANSLATION="auto"   # auto | on | off
ENABLE_AI="auto"            # auto | on | off
OUTBOUND_PROXY=""           # http://127.0.0.1:7890 或 socks5://...
BINARY_SRC=""
CONFIG_SRC=""   # 安装时使用的配置来源，由 require_source_tree 决定
RUN_TESTS=0

# ---------------------------------------------------------------------------
# 输出工具
# ---------------------------------------------------------------------------
c_reset=$'\033[0m'; c_red=$'\033[1;31m'; c_green=$'\033[1;32m'; c_yellow=$'\033[1;33m'; c_blue=$'\033[1;34m'
log()  { printf '%s[deploy]%s %s\n' "$c_green" "$c_reset" "$*"; }
info() { printf '%s[deploy]%s %s\n' "$c_blue"  "$c_reset" "$*"; }
warn() { printf '%s[deploy]%s %s\n' "$c_yellow" "$c_reset" "$*" >&2; }
die()  { printf '%s[deploy]%s %s\n' "$c_red"   "$c_reset" "$*" >&2; exit 1; }

usage() {
    # 打印文件头部的注释块作为用法说明
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "${BASH_SOURCE[0]}"
    cat <<'EOF'

选项:
  --port <N>            监听端口 (默认 8080)
  --host <ADDR>         监听地址 (默认 127.0.0.1；启用 --tls-cert 后默认 0.0.0.0)
  --dir <PATH>          安装目录 (默认 /opt/market-analyzer)
  --user <NAME>         运行用户 (默认 market)
  --binary <PATH>       跳过构建，安装指定的预编译二进制
  --skip-tests          不运行 cargo test
  --test                部署前运行 cargo test
  --translation <MODE>  auto | on | off (默认 auto: 保留配置文件里已有的 enabled)
  --ai <MODE>           auto | on | off (默认 auto: 保留配置文件里已有的 enabled)
  --proxy <URL>         出网代理 (如 http://127.0.0.1:7890)，写入 [network].proxy_url
  --tls-cert <PATH>     证书链 PEM；与 --tls-key 同时提供即由后端直出 HTTPS（无需反向代理）
  --tls-key <PATH>      私钥 PEM
  --no-backup           升级前不备份 SQLite
  --keep <N>            保留最近 N 份备份 (默认 10)
  -h, --help            显示帮助

密钥（全部写在安装目录的 config.toml 里，文件权限 0640；不再使用环境变量）:
  [auth] tokens                客户端访问令牌，格式 name:token,name:token
  [translation] api_key        事件名翻译
  [ai] api_key                 AI 市场简报（可与翻译用不同中转站/不同 Key）
  [telegram] bot_token         Telegram 机器人令牌
  [telegram] admin_chat_ids    管理员 chat id，逗号分隔
  [backfill] te_api_key        历史补采 --backfill

首次部署后直接编辑配置文件再重启：
  sudo nano /opt/market-analyzer/config.toml && sudo systemctl restart market-event-analyzer

可选环境变量（只影响进程本身，不是凭据）:
  APP_CONFIG   指定配置文件路径（默认取可执行文件同目录的 config.toml）
  RUST_LOG     日志级别（优先于 [server] log_level）

安装路径覆盖:
  APP_DIR CONF_DIR BACKUP_DIR APP_USER SERVICE_NAME HOST PORT
EOF
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
COMMAND="deploy"
EXTRA_ARGS=()

parse_global() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --port)          PORT="${2:?--port 需要参数}"; shift 2 ;;
            --host)          HOST="${2:?--host 需要参数}"; shift 2 ;;
            --dir)           APP_DIR="${2:?--dir 需要参数}"; shift 2 ;;
            --user)          APP_USER="${2:?--user 需要参数}"; shift 2 ;;
            --binary)        BINARY_SRC="${2:?--binary 需要参数}"; shift 2 ;;
            --translation)   ENABLE_TRANSLATION="${2:?--translation 需要参数}"; shift 2 ;;
            --ai)            ENABLE_AI="${2:?--ai 需要参数}"; shift 2 ;;
            --proxy)         OUTBOUND_PROXY="${2:?--proxy 需要参数}"; shift 2 ;;
            --keep)          BACKUP_KEEP="${2:?--keep 需要参数}"; shift 2 ;;
            --test)          RUN_TESTS=1; shift ;;
            --skip-tests)    RUN_TESTS=0; shift ;;
            --no-backup)     DO_BACKUP=0; shift ;;
            -h|--help)       usage; exit 0 ;;
            *)               return 1 ;;
        esac
    done
}

if [[ $# -gt 0 ]]; then
    case "$1" in
        deploy|install|upgrade|status|logs|uninstall|backfill|check-ai) COMMAND="$1"; shift ;;
    esac
fi

case "$COMMAND" in
    # backfill / uninstall 的参数原样透传，由各自的子命令解析
    backfill|uninstall|check-ai)
        EXTRA_ARGS=("$@")
        ;;
    *)
        parse_global "$@" || die "未知参数: ${1:-} (使用 --help 查看用法)"
        ;;
esac

case "$ENABLE_TRANSLATION" in
    auto|on|off) ;;
    *) die "--translation 只能是 auto / on / off" ;;
esac

case "$ENABLE_AI" in
    auto|on|off) ;;
    *) die "--ai 只能是 auto / on / off" ;;
esac

if [[ -n "${TLS_CERT}" || -n "${TLS_KEY}" ]]; then
    [[ -n "${TLS_CERT}" && -n "${TLS_KEY}" ]] || die "--tls-cert 与 --tls-key 必须同时提供"
    [[ -f "${TLS_CERT}" ]] || die "找不到证书文件: ${TLS_CERT}"
    [[ -f "${TLS_KEY}"  ]] || die "找不到私钥文件: ${TLS_KEY}"
    # 直出 HTTPS 时监听 127.0.0.1 外部就连不上，除非用户显式指定 --host。
    if [[ "${HOST_EXPLICIT}" -eq 0 ]]; then
        HOST="0.0.0.0"
    fi
fi

# ---------------------------------------------------------------------------
# 基础环境
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

DATA_DIR="${APP_DIR}/data"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
UNIT_TEMPLATE="${SCRIPT_DIR}/market-event-analyzer.service"

require_root() {
    [[ "${EUID}" -eq 0 ]] || die "需要 root 权限，请用: sudo $0 $*"
}

require_systemd() {
    command -v systemctl >/dev/null 2>&1 || die "未找到 systemctl；容器环境请改用 Docker 部署 (见 docs/backend.md)"
    [[ -d /run/systemd/system ]] || die "当前系统未运行 systemd (PID 1)；请改用 Docker 部署 (见 docs/backend.md)"
}

require_source_tree() {
    [[ -f "${BACKEND_DIR}/Cargo.toml" ]] || die "找不到 ${BACKEND_DIR}/Cargo.toml，请在仓库 backend/deploy 目录下运行本脚本"
    # 真实 config.toml 不入库；没有时用跟踪的模板作为安装起点。
    if [[ -f "${BACKEND_DIR}/config.toml" ]]; then
        CONFIG_SRC="${BACKEND_DIR}/config.toml"
    elif [[ -f "${BACKEND_DIR}/config.example.toml" ]]; then
        CONFIG_SRC="${BACKEND_DIR}/config.example.toml"
        info "使用配置模板 ${BACKEND_DIR}/config.example.toml 作为安装起点（部署后请编辑安装目录的 config.toml 填密钥）"
    else
        die "缺少 ${BACKEND_DIR}/config.toml 与 ${BACKEND_DIR}/config.example.toml"
    fi
    [[ -f "${BACKEND_DIR}/rules.toml" ]] || die "缺少 ${BACKEND_DIR}/rules.toml"
}

version_ge() { # $1 >= $2
    [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" == "$2" ]]
}

require_toolchain() {
    command -v cargo >/dev/null 2>&1 || die "未找到 cargo；请先安装 Rust: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    local rustc_ver
    rustc_ver="$(rustc --version | awk '{print $2}')"
    version_ge "$rustc_ver" "1.85.0" || die "rustc ${rustc_ver} 过旧，本项目使用 edition 2024，需要 >= 1.85.0"
    if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
        die "缺少 C 编译器 (sqlx 的 SQLite 需要)；Ubuntu/Debian: apt-get install -y build-essential"
    fi
    info "工具链: rustc ${rustc_ver}"
}

# ---------------------------------------------------------------------------
# systemd 服务用户
# ---------------------------------------------------------------------------
ensure_user() {
    if ! id -u "${APP_USER}" >/dev/null 2>&1; then
        log "创建系统用户 ${APP_USER}"
        useradd --system --home-dir "${APP_DIR}" --shell /usr/sbin/nologin --user-group "${APP_USER}"
    fi
    APP_GROUP="$(id -gn "${APP_USER}")"
}

# ---------------------------------------------------------------------------
# TOML 定点改写 (仅替换指定 section 下的 key)
# ---------------------------------------------------------------------------
set_toml_value() {
    local file="$1" section="$2" key="$3" value="$4" tmp
    tmp="$(mktemp)"
    awk -v section="[${section}]" -v key="${key}" -v value="${value}" '
        { sub(/\r$/, "") }   # 统一为 LF，兼容 Windows 检出/编辑产生的 CRLF
        $0 == section { in_section = 1; print; next }
        /^\[/        { in_section = 0 }
        in_section && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" { print key " = " value; next }
        { print }
    ' "${file}" > "${tmp}"
    install -m 0640 -o root -g "${APP_GROUP}" "${tmp}" "${file}"
    rm -f "${tmp}"
}

# ---------------------------------------------------------------------------
# 读取配置文件中已配置的密钥（升级时原样保留，不会被空值覆盖）
# ---------------------------------------------------------------------------
toml_get() {
    local file="$1" section="[$2]" key="$3"
    [[ -f "${file}" ]] || return 0
    awk -v section="${section}" -v key="${key}" '
        { sub(/\r$/, "") }
        $0 == section { in_section = 1; next }
        /^\[/        { in_section = 0 }
        in_section && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
            sub(/^[^=]*=/, "", $0)
            gsub(/^[[:space:]]*"|"[[:space:]]*$/, "", $0)
            print $0
            exit
        }
    ' "${file}"
}

# Writes a secret only when a value exists: an empty value must never wipe a configured one.
set_toml_secret() {
    local file="$1" section="$2" key="$3" previous="$4" next="$5"
    local value="${previous}"
    [[ -z "${value}" ]] && value="${next}"
    if [[ -n "${value}" ]]; then
        set_toml_value "${file}" "${section}" "${key}" "\"${value}\""
    fi
}

# Keeps a subsystem's `enabled` switch intact across an upgrade, defaulting it to false only
# when the file has neither a switch nor a credential (i.e. the very first deploy).
apply_enabled() {
    local section="$1" requested="$2" previous="$3" credential="$4"
    case "${requested}" in
        on)  set_toml_value "${APP_DIR}/config.toml" "${section}" enabled "true"; return ;;
        off) set_toml_value "${APP_DIR}/config.toml" "${section}" enabled "false"; return ;;
    esac
    if [[ -n "${previous}" ]]; then
        set_toml_value "${APP_DIR}/config.toml" "${section}" enabled "${previous}"
    elif [[ -z "${credential}" ]]; then
        set_toml_value "${APP_DIR}/config.toml" "${section}" enabled "false"
        warn "[${section}] 没有密钥：本次部署默认关闭该子系统，填好密钥后把 enabled 改为 true"
    fi
}

# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------
build_binary() {
    log "构建 release 二进制 (cargo build --release --locked)"
    pushd "${BACKEND_DIR}" >/dev/null
    if [[ "${RUN_TESTS}" -eq 1 ]]; then
        log "运行测试 (cargo test --locked)"
        cargo test --locked
    fi
    cargo build --release --locked
    popd >/dev/null
    BINARY_SRC="${BACKEND_DIR}/target/release/${APP_NAME}"
    [[ -x "${BINARY_SRC}" ]] || die "构建产物不存在: ${BINARY_SRC}"
}

# ---------------------------------------------------------------------------
# 备份 / 恢复
# ---------------------------------------------------------------------------
backup_database() {
    [[ "${DO_BACKUP}" -eq 1 ]] || { info "已跳过数据库备份"; return 0; }
    [[ -f "${DATA_DIR}/market.db" ]] || { info "数据库尚不存在，跳过备份"; return 0; }
    install -d -m 0700 -o root -g root "${BACKUP_DIR}"
    local stamp dest
    stamp="$(date +%Y%m%d-%H%M%S)"
    dest="${BACKUP_DIR}/market-${stamp}.db"
    # 服务已停止，直接复制即可 (含 WAL/SHM)
    cp -a "${DATA_DIR}/market.db" "${dest}"
    for suffix in -wal -shm; do
        [[ -f "${DATA_DIR}/market.db${suffix}" ]] && cp -a "${DATA_DIR}/market.db${suffix}" "${dest}${suffix}"
    done
    log "数据库已备份: ${dest}"
    # 轮转
    mapfile -t old < <(ls -1t "${BACKUP_DIR}"/market-*.db 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) || true)
    if [[ "${#old[@]}" -gt 0 ]]; then
        rm -f "${old[@]}"
        info "已清理 ${#old[@]} 份旧备份 (保留最近 ${BACKUP_KEEP} 份)"
    fi
}

# ---------------------------------------------------------------------------
# systemd 单元
# ---------------------------------------------------------------------------
render_unit() {
    [[ -f "${UNIT_TEMPLATE}" ]] || die "缺少单元模板: ${UNIT_TEMPLATE}"
    sed -e "s|@APP_USER@|${APP_USER}|g" \
        -e "s|@APP_GROUP@|${APP_GROUP}|g" \
        -e "s|@APP_DIR@|${APP_DIR}|g" \
        "${UNIT_TEMPLATE}" > "${SERVICE_FILE}"
    chmod 0644 "${SERVICE_FILE}"
}

# ---------------------------------------------------------------------------
# 已安装实例的监听端口 (status 用；读不到则回退 --port/默认值)
# ---------------------------------------------------------------------------
installed_port() {
    local file="${APP_DIR}/config.toml" port=""
    if [[ -f "${file}" ]]; then
        port="$(awk '
            /^\[server\]/ { s = 1; next }
            /^\[/         { s = 0 }
            s && /^[[:space:]]*port[[:space:]]*=/ { gsub(/[^0-9]/, ""); print; exit }
        ' "${file}")"
    fi
    printf '%s' "${port:-${PORT}}"
}

# 安装后的配置是否已启用后端直出 HTTPS
installed_tls() {
    local file="${APP_DIR}/config.toml"
    [[ -f "${file}" ]] || return 1
    awk '
        /^\[server\]/ { s = 1; next }
        /^\[/         { s = 0 }
        s && /^[[:space:]]*tls_cert[[:space:]]*=[[:space:]]*"[^"]+"/ { found = 1 }
        END { exit(found ? 0 : 1) }
    ' "${file}"
}

# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
wait_healthy() {
    command -v curl >/dev/null 2>&1 || { warn "未安装 curl，跳过健康检查"; return 0; }
    # 直出 HTTPS 时本机自检也要走 TLS；证书可能就是给这个域名签的。
    local scheme="http" insecure=""
    if installed_tls; then scheme="https"; insecure="-k"; fi
    local url="${scheme}://127.0.0.1:${PORT}/health"
    local _
    for _ in $(seq 1 30); do
        if curl -fsS ${insecure} --max-time 2 "${url}" >/dev/null 2>&1; then
            log "健康检查通过: ${url}"
            return 0
        fi
        sleep 1
    done
    warn "健康检查失败 (${url})；最近日志:"
    journalctl -u "${SERVICE_NAME}" -n 40 --no-pager || true
    return 1
}

# ---------------------------------------------------------------------------
# deploy
# ---------------------------------------------------------------------------
cmd_deploy() {
    require_root "${COMMAND}"
    require_systemd
    require_source_tree
    ensure_user

    if [[ -z "${BINARY_SRC}" ]]; then
        require_toolchain
        build_binary
    else
        BINARY_SRC="$(cd -- "$(dirname -- "${BINARY_SRC}")" && pwd)/$(basename -- "${BINARY_SRC}")"
        [[ -f "${BINARY_SRC}" ]] || die "找不到指定的二进制: ${BINARY_SRC}"
        info "使用预编译二进制: ${BINARY_SRC}"
    fi


    # 既有配置里已经写好的密钥与开关先读出来（install 会用仓库版覆盖该文件）。
    local prev_config="${APP_DIR}/config.toml"
    local prev_tokens prev_translation_key prev_ai_key prev_bot_token prev_chat_ids prev_te_key
    local prev_enabled_translation prev_enabled_ai prev_enabled_telegram prev_enabled_auth
    prev_tokens="$(toml_get "${prev_config}" auth tokens)"
    prev_translation_key="$(toml_get "${prev_config}" translation api_key)"
    prev_ai_key="$(toml_get "${prev_config}" ai api_key)"
    prev_bot_token="$(toml_get "${prev_config}" telegram bot_token)"
    prev_chat_ids="$(toml_get "${prev_config}" telegram admin_chat_ids)"
    prev_te_key="$(toml_get "${prev_config}" backfill te_api_key)"
    prev_enabled_translation="$(toml_get "${prev_config}" translation enabled)"
    prev_enabled_ai="$(toml_get "${prev_config}" ai enabled)"
    prev_enabled_telegram="$(toml_get "${prev_config}" telegram enabled)"
    prev_enabled_auth="$(toml_get "${prev_config}" auth enabled)"

    # 停服 -> 备份
    log "停止服务 ${SERVICE_NAME}"
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    backup_database

    # 安装目录
    log "安装到 ${APP_DIR}"
    install -d -m 0755 -o root -g root "${APP_DIR}"
    install -d -m 0750 -o "${APP_USER}" -g "${APP_GROUP}" "${DATA_DIR}"

    install -m 0755 -o root -g root "${BINARY_SRC}" "${APP_DIR}/${APP_NAME}"
    install -m 0640 -o root -g "${APP_GROUP}" "${CONFIG_SRC}" "${APP_DIR}/config.toml"
    install -m 0640 -o root -g "${APP_GROUP}" "${BACKEND_DIR}/rules.toml"  "${APP_DIR}/rules.toml"

    set_toml_value "${APP_DIR}/config.toml" server host "\"${HOST}\""
    set_toml_value "${APP_DIR}/config.toml" server port "${PORT}"
    if [[ -n "${OUTBOUND_PROXY}" ]]; then
        set_toml_value "${APP_DIR}/config.toml" network proxy_url "\"${OUTBOUND_PROXY}\""
        info "出网代理: ${OUTBOUND_PROXY}（仅 [network].proxied 中列出的源与 Telegram 经过它）"
    fi

    # 配置文件是唯一事实来源：升级时把已配置的密钥与开关原样写回新文件（仓库版是空值）。
    # 首次部署后直接编辑 ${APP_DIR}/config.toml 填密钥并重启即可，无需重跑部署。
    set_toml_secret "${APP_DIR}/config.toml" auth tokens "${prev_tokens}" ""
    set_toml_secret "${APP_DIR}/config.toml" translation api_key "${prev_translation_key}" ""
    set_toml_secret "${APP_DIR}/config.toml" ai api_key "${prev_ai_key}" ""
    set_toml_secret "${APP_DIR}/config.toml" telegram bot_token "${prev_bot_token}" ""
    set_toml_secret "${APP_DIR}/config.toml" telegram admin_chat_ids "${prev_chat_ids}" ""
    set_toml_secret "${APP_DIR}/config.toml" backfill te_api_key "${prev_te_key}" ""

    # enabled 开关：只有显式传 --translation/--ai 才改写；否则保留文件里已有的值。
    # 首次部署（既无密钥也无开关记录）时把没有凭据的子系统写成 false，避免服务干跑。
    apply_enabled translation "${ENABLE_TRANSLATION}" "${prev_enabled_translation}" "${prev_translation_key}"
    apply_enabled ai "${ENABLE_AI}" "${prev_enabled_ai}" "${prev_ai_key}"
    if [[ -n "${prev_bot_token}" && -n "${prev_chat_ids}" ]]; then
        set_toml_value "${APP_DIR}/config.toml" telegram enabled "${prev_enabled_telegram:-true}"
    else
        set_toml_value "${APP_DIR}/config.toml" telegram enabled "${prev_enabled_telegram:-false}"
        warn "[telegram] 未配置 bot_token / admin_chat_ids：告警关闭，数据源异常只写日志"
    fi
    if [[ -n "${prev_tokens}" ]]; then
        set_toml_value "${APP_DIR}/config.toml" auth enabled "${prev_enabled_auth:-true}"
    else
        set_toml_value "${APP_DIR}/config.toml" auth enabled "${prev_enabled_auth:-false}"
        warn "[auth] tokens 未配置：鉴权关闭，数据接口公开"
    fi
    info "配置与密钥保留于 ${APP_DIR}/config.toml（权限 0640）"

    # 直出 HTTPS：证书与私钥只读给运行用户，私钥不放在仓库与安装目录之外
    if [[ -n "${TLS_CERT}" ]]; then
        install -d -m 0750 -o root -g "${APP_GROUP}" "${CONF_DIR}/tls"
        install -m 0640 -o root -g "${APP_GROUP}" "${TLS_CERT}" "${CONF_DIR}/tls/fullchain.pem"
        install -m 0640 -o root -g "${APP_GROUP}" "${TLS_KEY}"  "${CONF_DIR}/tls/privkey.pem"
        set_toml_value "${APP_DIR}/config.toml" server tls_cert "\"${CONF_DIR}/tls/fullchain.pem\""
        set_toml_value "${APP_DIR}/config.toml" server tls_key  "\"${CONF_DIR}/tls/privkey.pem\""
        info "后端直出 HTTPS: ${CONF_DIR}/tls/（续期后需 systemctl restart ${SERVICE_NAME}）"
    else
        set_toml_value "${APP_DIR}/config.toml" server tls_cert '""'
        set_toml_value "${APP_DIR}/config.toml" server tls_key  '""'
    fi

    info "配置文件: ${APP_DIR}/config.toml（含密钥，权限 0640；可用 APP_CONFIG 指向其他文件）"

    # systemd
    render_unit
    systemctl daemon-reload
    systemctl enable "${SERVICE_NAME}" >/dev/null
    log "启动服务 ${SERVICE_NAME}"
    systemctl restart "${SERVICE_NAME}"

    if wait_healthy; then
        log "部署完成 ✅"
    else
        die "服务已启动但健康检查失败，请查看: journalctl -u ${SERVICE_NAME} -e"
    fi

    info "监听地址: ${HOST}:${PORT}"
    if installed_tls; then
        info "后端直出 HTTPS：客户端地址填 https://<域名>（无需反向代理）"
        info "证书续期后重启服务: systemctl restart ${SERVICE_NAME}"
    else
        info "当前为明文 HTTP，仅适合内网/隧道；客户端请用「允许明文 HTTP」开关或换 https"
    fi
}

# ---------------------------------------------------------------------------
# check-ai: 中转站/模型连通性自检（不碰数据库，不落任何分析结果）
# ---------------------------------------------------------------------------
cmd_check_ai() {
    require_root "${COMMAND}"
    [[ -x "${APP_DIR}/${APP_NAME}" ]] || die "未找到已安装的二进制，请先部署: $0"

    # 密钥不经过命令行，也不走环境：全部从安装目录的配置文件读。
    export APP_CONFIG="${APP_DIR}/config.toml"
    export RUST_LOG="market_event_analyzer=warn"

    cd "${APP_DIR}"
    local status=0
    if command -v runuser >/dev/null 2>&1; then
        runuser -u "${APP_USER}" --preserve-environment -- \
            "${APP_DIR}/${APP_NAME}" --check-ai || status=$?
    else
        sudo -u "${APP_USER}" --preserve-env=APP_CONFIG,RUST_LOG -- \
            "${APP_DIR}/${APP_NAME}" --check-ai || status=$?
    fi
    return "${status}"
}

# ---------------------------------------------------------------------------
# status / logs
# ---------------------------------------------------------------------------
cmd_status() {
    require_systemd
    systemctl --no-pager --full status "${SERVICE_NAME}" || true
    echo
    local port
    port="$(installed_port)"
    if command -v curl >/dev/null 2>&1; then
        local code scheme="http" insecure=""
        if installed_tls; then scheme="https"; insecure="-k"; fi
        code="$(curl -s ${insecure} -o /dev/null -w '%{http_code}' --max-time 3 "${scheme}://127.0.0.1:${port}/health" || true)"
        [[ "${code}" == "200" ]] && log "健康检查 /health (${scheme}, port ${port}) -> 200" || warn "健康检查 /health (${scheme}, port ${port}) -> ${code:-无响应}"
    fi
    if [[ -f "${APP_DIR}/config.toml" ]]; then
        echo
        info "配置文件: ${APP_DIR}/config.toml（值不回显）"
        local section name key
        for key in "auth:tokens" "translation:api_key" "ai:api_key" \
                   "telegram:bot_token" "telegram:admin_chat_ids" "backfill:te_api_key"; do
            section="${key%%:*}"
            name="${key##*:}"
            if [[ -n "$(toml_get "${APP_DIR}/config.toml" "${section}" "${name}")" ]]; then
                info "  [${section}] ${name} = 已配置"
            else
                info "  [${section}] ${name} = （空）"
            fi
        done
    fi
}

cmd_logs() {
    require_root "${COMMAND}"
    require_systemd
    journalctl -u "${SERVICE_NAME}" -f -n 100
}

# ---------------------------------------------------------------------------
# backfill
# ---------------------------------------------------------------------------
cmd_backfill() {
    require_root "${COMMAND}"
    [[ -x "${APP_DIR}/${APP_NAME}" ]] || die "未找到已安装的二进制，请先部署: $0"
    if [[ -z "$(toml_get "${APP_DIR}/config.toml" backfill te_api_key)" ]]; then
        die "未配置 [backfill] te_api_key（写入 ${APP_DIR}/config.toml 后重试）"
    fi

    systemctl is-active --quiet "${SERVICE_NAME}" && \
        warn "服务正在运行，补采与实时采集共用数据库 (WAL 并发安全，但建议错峰)"

    # 通过环境而不是命令行传凭据，避免密钥出现在 ps 输出中。
    export APP_CONFIG="${APP_DIR}/config.toml"
    export RUST_LOG

    log "开始补采: ${EXTRA_ARGS[*]:-默认最近三个月}"
    cd "${APP_DIR}"
    if command -v runuser >/dev/null 2>&1; then
        runuser -u "${APP_USER}" --preserve-environment -- \
            "${APP_DIR}/${APP_NAME}" --backfill "${EXTRA_ARGS[@]}"
    else
        sudo -u "${APP_USER}" --preserve-env=APP_CONFIG,RUST_LOG -- \
            "${APP_DIR}/${APP_NAME}" --backfill "${EXTRA_ARGS[@]}"
    fi
    log "补采结束"
}

# ---------------------------------------------------------------------------
# uninstall
# ---------------------------------------------------------------------------
cmd_uninstall() {
    require_root "${COMMAND}"
    require_systemd

    local purge=0
    for arg in "$@"; do
        case "${arg}" in
            --purge) purge=1 ;;
            *) die "未知参数: ${arg}" ;;
        esac
    done

    log "停止并禁用服务"
    systemctl stop "${SERVICE_NAME}" 2>/dev/null || true
    systemctl disable "${SERVICE_NAME}" 2>/dev/null || true
    rm -f "${SERVICE_FILE}"
    systemctl daemon-reload

    if [[ "${purge}" -eq 1 ]]; then
        warn "删除安装目录 ${APP_DIR}、配置 ${CONF_DIR} 与备份 ${BACKUP_DIR}"
        rm -rf "${APP_DIR}" "${CONF_DIR}" "${BACKUP_DIR}"
        if id -u "${APP_USER}" >/dev/null 2>&1; then
            userdel "${APP_USER}" 2>/dev/null || true
        fi
        log "已完全卸载 (数据已删除)"
    else
        log "服务已卸载；数据仍保留在 ${APP_DIR} 与 ${BACKUP_DIR}"
        info "彻底清理请运行: sudo $0 uninstall --purge"
    fi
}

# ---------------------------------------------------------------------------
# main (仅在被直接执行时运行，方便测试时 source)
# ---------------------------------------------------------------------------
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    case "${COMMAND}" in
        deploy|install|upgrade) cmd_deploy ;;
        status)                 cmd_status ;;
        logs)                   cmd_logs ;;
        backfill)               cmd_backfill ;;
        check-ai)               cmd_check_ai ;;
        uninstall)              cmd_uninstall "${EXTRA_ARGS[@]}" ;;
        *) die "未知命令: ${COMMAND}" ;;
    esac
fi
