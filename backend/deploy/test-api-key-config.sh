#!/usr/bin/env bash
# Test config upgrade using temporary synthetic fixtures; never read local config.toml.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
APP_DIR="$ROOT/app"
source "$SCRIPT_DIR/deploy.sh"
CONFIG_SRC="$SCRIPT_DIR/../config.example.toml"
BACKEND_DIR="$ROOT/source"
mkdir -p "$APP_DIR" "$BACKEND_DIR"
printf 'fixture-rules\n' > "$BACKEND_DIR/rules.toml"
APP_GROUP=fixture
BINARY_SRC="$ROOT/fixture-binary"
printf 'fixture-binary\n' > "$BINARY_SRC"
require_root() { :; }
require_systemd() { :; }
require_source_tree() { :; }
ensure_user() { :; }
backup_database() { :; }
systemctl() { :; }
render_unit() { :; }
wait_healthy() { return 0; }
installed_tls() { return 1; }
# No root/systemd or ownership changes needed: exercise config contents, not permissions.
chown() { :; }
install() {
    if [[ "$1" == -d ]]; then mkdir -p -- "${@: -1}";
    else cp -- "${@: -2:1}" "${@: -1}"; fi
}
assert_equal() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    [[ "$value" == "$2" ]] || { printf 'Config regression failed\n' >&2; exit 1; }
}

cmd_deploy >/dev/null
assert_equal "$(toml_get "$APP_DIR/config.toml" auth enabled)" true
assert_equal "$(toml_get "$APP_DIR/config.toml" auth tokens)" ''

# Simulate an older installer which auto-disabled auth for empty static tokens.
printf '\n' > "$APP_DIR/config.toml"
printf '[auth]\nenabled = false\ntokens = "fixture-retired-token"\n[translation]\nenabled = false\napi_key = "fixture-translation-key"\n[ai]\nenabled = false\napi_key = "fixture-ai-key"\n[telegram]\nenabled = false\nbot_token = "fixture-bot-token"\nadmin_chat_ids = "42"\n' > "$APP_DIR/config.toml"
cmd_deploy >/dev/null
assert_equal "$(toml_get "$APP_DIR/config.toml" auth enabled)" true
assert_equal "$(toml_get "$APP_DIR/config.toml" auth tokens)" ''
assert_equal "$(toml_get "$APP_DIR/config.toml" translation api_key)" fixture-translation-key
assert_equal "$(toml_get "$APP_DIR/config.toml" ai api_key)" fixture-ai-key
assert_equal "$(toml_get "$APP_DIR/config.toml" telegram bot_token)" fixture-bot-token
assert_equal "$(toml_get "$APP_DIR/config.toml" telegram admin_chat_ids)" 42
printf 'API-key config upgrade regression passed.\n'
