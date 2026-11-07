#!/usr/bin/env bash
# Local fake SCP; no SSH credentials, server access or production changes required.
set -Eeuo pipefail
SCRIPT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/upload-artifact.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/ssh"
touch "$ROOT/ssh/key" "$ROOT/ssh/known_hosts"
printf 'archive-fixture' > "$ROOT/package with spaces.tar.gz"
cat > "$ROOT/bin/scp" <<'EOF'
#!/usr/bin/env bash
set -eu
printf 'call\n' >> "$TEST_ROOT/calls"
printf '%s\n' "$@" > "$TEST_ROOT/args"
case "${TEST_MODE:-success}" in
    success) ;;
    slow) sleep 3 ;;
    failure) exit 23 ;;
    hang) sleep 30 ;;
esac
EOF
chmod +x "$ROOT/bin/scp"
export PATH="$ROOT/bin:$PATH" TEST_ROOT="$ROOT" SSH_DIR="$ROOT/ssh"
export DEPLOY_HOST=example.invalid DEPLOY_USER=root DEPLOY_PORT=22
export REMOTE_DIR=/tmp/macro-research-deploy.Abc123
export GITHUB_STEP_SUMMARY="$ROOT/summary" UPLOAD_HEARTBEAT_SECONDS=1

fail() { echo "FAIL: $*" >&2; exit 1; }
run_upload() {
    bash "$SCRIPT" "$ROOT/package with spaces.tar.gz" release.tar.gz "${1:-10}" > "$ROOT/output" 2>&1
}

run_upload
grep -Fxq 'StrictHostKeyChecking=yes' "$ROOT/args" || fail 'Host verification disabled'
grep -Fxq 'ServerAliveInterval=15' "$ROOT/args" || fail 'Missing keepalive'
grep -Fxq 'ServerAliveCountMax=3' "$ROOT/args" || fail 'Missing keepalive limit'
grep -Fxq 'BatchMode=yes' "$ROOT/args" || fail 'Interactive SSH is unsafe'
grep -Fxq 'IdentitiesOnly=yes' "$ROOT/args" || fail 'Unbounded identity attempts'
grep -Fxq "$ROOT/package with spaces.tar.gz" "$ROOT/args" || fail 'Local path was split'
grep -Fxq 'root@example.invalid:/tmp/macro-research-deploy.Abc123/release.tar.gz' "$ROOT/args" || fail 'Wrong target'
grep -q 'KiB/s' "$ROOT/output" || fail 'Missing throughput'
grep -q '| release.tar.gz | 15 |' "$ROOT/summary" || fail 'Missing byte count summary'

DEPLOY_HOST=2001:db8::1 run_upload
grep -Fxq 'root@[2001:db8::1]:/tmp/macro-research-deploy.Abc123/release.tar.gz' "$ROOT/args" || fail 'IPv6 destination not bracketed'

TEST_MODE=slow run_upload
grep -q '仍在上传' "$ROOT/output" || fail 'Missing heartbeat during a quiet transfer'

: > "$ROOT/calls"
status=0
TEST_MODE=failure run_upload || status=$?
[[ "$status" == 23 ]] || fail 'SCP failure was hidden'
[[ "$(wc -l < "$ROOT/calls")" -eq 1 ]] || fail 'Unexpected retry'
grep -q 'exit 23' "$ROOT/summary" || fail 'Failure summary missing'
! grep -q '上传完成' "$ROOT/output" || fail 'Failure reported as success'

started=$SECONDS
status=0
TEST_MODE=hang run_upload 1 || status=$?
[[ "$status" == 124 || "$status" == 137 ]] || fail "Wrong timeout status: $status"
(( SECONDS - started < 15 )) || fail 'Timeout did not bound SCP'
grep -q '上传超时' "$ROOT/output" || fail 'Timeout not distinguished from network failure'

: > "$ROOT/calls"
if REMOTE_DIR=/root/macro-research run_upload; then fail 'Unsafe destination accepted'; fi
if DEPLOY_PORT=65536 run_upload; then fail 'Invalid port accepted'; fi
if run_upload 0; then fail 'Unbounded timeout accepted'; fi
[[ ! -s "$ROOT/calls" ]] || fail 'Invalid inputs reached SCP'
printf 'All upload regression tests passed.\n'
