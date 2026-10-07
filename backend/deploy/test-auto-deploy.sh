#!/usr/bin/env bash
# No SSH, root access, tmux server or real installation required.
set -Eeuo pipefail
SCRIPT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/auto-deploy.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
REVISION=1111111111111111111111111111111111111111
CONFIG_HASH=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_content() { [[ "$(< "$1")" == "$2" ]] || fail "Unexpected content in $1"; }

cat > "$ROOT/harness.sh" <<'EOF'
source "$DEPLOY_SCRIPT"
check_runtime() { printf 'runtime\n' >> "$APP_DIR/events"; }
check_pane() { printf 'pane\n' >> "$APP_DIR/events"; }
stop_backend() {
    [[ "$(< "$APP_DIR/market-event-analyzer")" == old-binary ]] || die 'Replaced old binary before stopping'
    printf 'stop\n' >> "$APP_DIR/events"
    [[ "${TEST_FAIL:-}" != stop ]] || die 'Simulated stop failure'
    rm "$APP_DIR/running"
}
start_backend() {
    if [[ "$(< "$APP_DIR/market-event-analyzer")" == old-binary ]]; then
        printf 'recover-start\n' >> "$APP_DIR/events"
        [[ "${TEST_FAIL:-}" != backup-recovery-fails ]] || die 'Simulated old-backend recovery failure'
    else
        [[ "$(< "$APP_DIR/market-event-analyzer")" == new-binary ]] || die 'New binary was not installed'
        printf 'start\n' >> "$APP_DIR/events"
    fi
    cp "$APP_DIR/market-event-analyzer" "$APP_DIR/running"
}
observe_backend() {
    if [[ "$(< "$APP_DIR/market-event-analyzer")" == old-binary ]]; then
        printf 'recover-observe\n' >> "$APP_DIR/events"
    else
        printf 'observe\n' >> "$APP_DIR/events"
        if [[ "${TEST_FAIL:-}" == observe ]]; then
            rm "$APP_DIR/running"
            die 'Simulated early process exit'
        fi
    fi
}
main "$@"
EOF

fixture() {
    CASE_DIR="$ROOT/$1"; mkdir -p "$CASE_DIR/app/data" "$CASE_DIR/package/release"
    printf old-binary > "$CASE_DIR/app/market-event-analyzer"
    printf old-binary > "$CASE_DIR/app/running"
    printf private-config > "$CASE_DIR/app/config.toml"
    printf private-rules > "$CASE_DIR/app/rules.toml"
    printf private-firebase > "$CASE_DIR/app/firebase-service-account.json"
    printf database > "$CASE_DIR/app/data/market.db"
    printf '%s\n' "$CONFIG_HASH" > "$CASE_DIR/app/.deploy-config-example.sha256"
    cat > "$CASE_DIR/app/backup.sh" <<'EOF'
set -eu
[[ "$1" == --dir && "$2" == "$PWD" ]]
[[ ! -e running ]]
[[ "$(< market-event-analyzer)" == old-binary ]]
printf 'backup\n' >> events
[[ "${TEST_FAIL:-}" != backup && "${TEST_FAIL:-}" != backup-recovery-fails ]]
EOF
    printf new-binary > "$CASE_DIR/package/release/market-event-analyzer"
    printf DO-NOT-INSTALL > "$CASE_DIR/package/release/config.toml"
    printf DO-NOT-INSTALL > "$CASE_DIR/package/release/rules.toml"
    tar -czf "$CASE_DIR/release.tar.gz" -C "$CASE_DIR/package" release
    ARCHIVE_HASH="$(sha256sum "$CASE_DIR/release.tar.gz")"; ARCHIVE_HASH="${ARCHIVE_HASH%% *}"
}

run_deployment() {
    DEPLOY_SCRIPT="$SCRIPT" TEST_FAIL="${TEST_FAIL:-}" bash "$ROOT/harness.sh" \
        --dir "$CASE_DIR/app" --archive "$CASE_DIR/release.tar.gz" \
        --archive-sha256 "$ARCHIVE_HASH" --config-sha256 "$CONFIG_HASH" --revision "$REVISION" \
        > "$CASE_DIR/output" 2>&1
}

fixture success
run_deployment
assert_content "$CASE_DIR/app/events" $'runtime\npane\nstop\nbackup\nstart\nobserve'
assert_content "$CASE_DIR/app/market-event-analyzer" new-binary
assert_content "$CASE_DIR/app/running" new-binary
assert_content "$CASE_DIR/app/config.toml" private-config
assert_content "$CASE_DIR/app/rules.toml" private-rules
assert_content "$CASE_DIR/app/firebase-service-account.json" private-firebase
assert_content "$CASE_DIR/app/data/market.db" database
assert_content "$CASE_DIR/app/.deploy-revision" "$REVISION"
grep -qx 'DEPLOY_RESULT=success' "$CASE_DIR/output" || fail 'Missing success result'

for mode in changed-config missing-baseline; do
    fixture "$mode"
    if [[ "$mode" == changed-config ]]; then
        printf bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb > "$CASE_DIR/app/.deploy-config-example.sha256"
    else
        rm "$CASE_DIR/app/.deploy-config-example.sha256"
    fi
    run_deployment
    [[ ! -e "$CASE_DIR/app/events" ]] || fail 'Skipped deployment touched the installation'
    assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
    assert_content "$CASE_DIR/app/running" old-binary
    grep -qx 'DEPLOY_RESULT=manual_required' "$CASE_DIR/output" || fail 'Missing manual-deployment result'
done

fixture backup-fails
TEST_FAIL=backup
if run_deployment; then fail 'Backup failure was ignored'; fi
TEST_FAIL=""
assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
assert_content "$CASE_DIR/app/running" old-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane\nstop\nbackup\nrecover-start\nrecover-observe'
[[ ! -e "$CASE_DIR/app/.deploy-revision" ]] || fail 'Failed backup was recorded as deployed'
grep -q 'Old backend restarted and verified' "$CASE_DIR/output" || fail 'Missing old-backend recovery confirmation'

fixture backup-recovery-fails
TEST_FAIL=backup-recovery-fails
if run_deployment; then fail 'Backup/recovery failure was ignored'; fi
TEST_FAIL=""
assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane\nstop\nbackup\nrecover-start'
[[ ! -e "$CASE_DIR/app/running" ]] || fail 'Failed recovery was reported as running'
[[ ! -e "$CASE_DIR/app/.deploy-revision" ]] || fail 'Failed recovery was recorded as deployed'
grep -q 'Old backend recovery failed; manual intervention required' "$CASE_DIR/output" || fail 'Missing recovery failure diagnostic'

fixture stop-fails
TEST_FAIL=stop
if run_deployment; then fail 'Stop failure was ignored'; fi
TEST_FAIL=""
assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
assert_content "$CASE_DIR/app/running" old-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane\nstop'

fixture bad-checksum
ARCHIVE_HASH=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
if run_deployment; then fail 'Archive hash mismatch was ignored'; fi
assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
assert_content "$CASE_DIR/app/running" old-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane'
[[ ! -e "$CASE_DIR/app/.deploy-revision" ]] || fail 'Failed deployment was recorded as successful'

fixture missing-binary
rm "$CASE_DIR/package/release/market-event-analyzer"
tar -czf "$CASE_DIR/release.tar.gz" -C "$CASE_DIR/package" release
ARCHIVE_HASH="$(sha256sum "$CASE_DIR/release.tar.gz")"; ARCHIVE_HASH="${ARCHIVE_HASH%% *}"
if run_deployment; then fail 'An archive without the binary was accepted'; fi
assert_content "$CASE_DIR/app/market-event-analyzer" old-binary
assert_content "$CASE_DIR/app/running" old-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane'
[[ ! -e "$CASE_DIR/app/.deploy-revision" ]] || fail 'Malformed archive was recorded as deployed'

fixture early-exit
TEST_FAIL=observe
if run_deployment; then fail 'Early process exit was ignored'; fi
TEST_FAIL=""
assert_content "$CASE_DIR/app/market-event-analyzer" new-binary
assert_content "$CASE_DIR/app/events" $'runtime\npane\nstop\nbackup\nstart\nobserve'
[[ ! -e "$CASE_DIR/app/running" ]] || fail 'Exited new backend was reported as running'
[[ ! -e "$CASE_DIR/app/.deploy-revision" ]] || fail 'Failed launch was recorded as successful'
grep -q 'Simulated early process exit' "$CASE_DIR/output" || fail 'Missing observation error'

# Exercise the actual observer with mocked /proc operations and a virtual clock.
cat > "$ROOT/observer.sh" <<'EOF'
source "$DEPLOY_SCRIPT"
RUN_DIR="$OBSERVER_DIR"
OBSERVE_SECONDS=3
OLD_PID=123; SHELL_PID=10
checks=0
pid_is_new_backend() {
    checks=$((checks + 1))
    [[ "$MODE" != startup-exit && ( "$MODE" != later-exit || "$checks" -lt 3 ) ]]
}
kill() { [[ "$MODE" != startup-exit ]]; }
awk() {
    if [[ "$MODE" == pid-reused && -e "$OBSERVER_DIR/token-read" ]]; then
        printf '200\n'
    else
        touch "$OBSERVER_DIR/token-read"
        printf '100\n'
    fi
}
sleep() { SECONDS=$((SECONDS + 1)); }
observe_backend
EOF
mkdir "$ROOT/observer"; printf '456\n' > "$ROOT/observer/pid"
for mode in live startup-exit later-exit pid-reused; do
    rm -f "$ROOT/observer/token-read"
    if DEPLOY_SCRIPT="$SCRIPT" OBSERVER_DIR="$ROOT/observer" MODE="$mode" bash "$ROOT/observer.sh" > "$ROOT/observer-output" 2>&1; then
        [[ "$mode" == live ]] || fail "Observer accepted $mode"
    else
        [[ "$mode" != live ]] || fail 'Observer rejected a sustained process'
    fi
done

# Identity checks must reject an old PID, unrelated executable, or unrelated pane.
cat > "$ROOT/identity.sh" <<'EOF'
source "$DEPLOY_SCRIPT"
APP_DIR=/app; NEW_PID=456; OLD_PID=123; SHELL_PID=10
kill() { [[ "$MODE" != dead ]]; }
readlink() { if [[ "$MODE" == wrong-exe ]]; then printf /app/other; else printf /app/market-event-analyzer; fi; }
is_descendant() { [[ "$MODE" != wrong-pane ]]; }
[[ "$MODE" != old-pid ]] || NEW_PID=123
pid_is_new_backend
EOF
for mode in live dead wrong-exe wrong-pane old-pid; do
    if DEPLOY_SCRIPT="$SCRIPT" MODE="$mode" bash "$ROOT/identity.sh"; then
        [[ "$mode" == live ]] || fail "Identity check accepted $mode"
    else
        [[ "$mode" != live ]] || fail 'Identity check rejected the new backend'
    fi
done
printf 'All auto-deployment regression tests passed.\n'
