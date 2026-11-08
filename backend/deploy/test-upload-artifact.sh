#!/usr/bin/env bash
# Fake SSH/SCP/rsync only: no credentials, network, or production changes.
set -Eeuo pipefail
SCRIPT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/upload-artifact.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/ssh dir'quote" "$ROOT/tmp dir'quote"
touch "$ROOT/ssh dir'quote/key" "$ROOT/ssh dir'quote/known_hosts"
printf 'archive-fixture' > "$ROOT/package with spaces.tar.gz"
cat > "$ROOT/bin/ssh" <<'EOF'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$@" > "$TEST_ROOT/ssh-args"
if [[ "${*: -1}" == *'command -v rsync'* ]]; then
    printf 'probe\n' >> "$TEST_ROOT/probes"
    case "${TEST_PROBE:-success}" in
        missing) exit 42 ;;
        failure) echo 'Host key verification failed.' >&2; exit 255 ;;
        slow) sleep 2 ;;
    esac
fi
EOF
cat > "$ROOT/bin/scp" <<'EOF'
#!/usr/bin/env bash
set -eu
printf 'scp\n' >> "$TEST_ROOT/calls"
printf '%s\n' "$@" > "$TEST_ROOT/args"
case "${TEST_MODE:-success}" in
    slow) sleep 3 ;;
    failure) exit 23 ;;
    hang) sleep 30 ;;
esac
EOF
cat > "$ROOT/bin/rsync" <<'EOF'
#!/usr/bin/env bash
set -eu
printf 'rsync\n' >> "$TEST_ROOT/calls"
printf '%s\n' "$@" > "$TEST_ROOT/args"
[[ "$1" == -e ]] || exit 99
# Exercise the real SSH wrapper (including paths with spaces/single quotes).
"$2" example.invalid 'rsync --server'
source="${*: -2:1}"
call="$(wc -l < "$TEST_ROOT/calls")"
case "${TEST_MODE:-success}" in
    failure) exit "${TEST_FAIL_CODE:-23}" ;;
    slow)
        printf '\r        5  33%%    1.00kB/s    0:00:02'
        sleep 3 ;;
    retry|retry-timeout)
        if [[ "$call" -eq 1 ]]; then
            head -c 5 "$source" > "$TEST_ROOT/partial"
            if [[ "$TEST_MODE" == retry-timeout ]]; then sleep 30; fi
            exit "${TEST_FAIL_CODE:-12}"
        fi
        [[ "$(wc -c < "$TEST_ROOT/partial")" -eq 5 ]] || exit 99
        printf 'resumed\n' > "$TEST_ROOT/resumed" ;;
    hang) sleep 30 ;;
esac
cp "$source" "$TEST_ROOT/partial"
printf '\r       15 100%%    1.00kB/s    0:00:00 (xfr#1, to-chk=0/1)\n'
EOF
chmod +x "$ROOT/bin/ssh" "$ROOT/bin/scp" "$ROOT/bin/rsync"
export PATH="$ROOT/bin:$PATH" TEST_ROOT="$ROOT" SSH_DIR="$ROOT/ssh dir'quote"
export TMPDIR="$ROOT/tmp dir'quote"
export DEPLOY_HOST=example.invalid DEPLOY_USER=root DEPLOY_PORT=22
export REMOTE_DIR=/tmp/macro-research-deploy.Abc123
export GITHUB_STEP_SUMMARY="$ROOT/summary" UPLOAD_HEARTBEAT_SECONDS=1

fail() { echo "FAIL: $*" >&2; tail -n 20 "$ROOT/output" >&2; exit 1; }
run_upload() {
    : > "$ROOT/calls"
    : > "$ROOT/probes"
    rm -f "$ROOT/resumed" "$ROOT/partial"
    bash "$SCRIPT" "$ROOT/package with spaces.tar.gz" "${2:-release.tar.gz}" "${1:-15}" > "$ROOT/output" 2>&1
}
assert_calls() { [[ "$(wc -l < "$ROOT/calls")" -eq "$1" ]] || fail "Expected $1 transfer calls"; }
assert_security() {
    local args="$1"
    for option in StrictHostKeyChecking=yes ServerAliveInterval=15 ServerAliveCountMax=3 BatchMode=yes IdentitiesOnly=yes; do
        grep -Fxq "$option" "$args" || fail "Missing $option"
    done
    grep -Fxq "$SSH_DIR/key" "$args" || fail 'Identity path was split'
    grep -Fxq "UserKnownHostsFile=$SSH_DIR/known_hosts" "$args" || fail 'Known-hosts path was split'
}

run_upload
assert_calls 1
assert_security "$ROOT/ssh-args"
for arg in --partial --append-verify --timeout=90 --info=progress2 --outbuf=L; do
    grep -Fxq -- "$arg" "$ROOT/args" || fail "Missing rsync option $arg"
done
grep -Fxq "$ROOT/package with spaces.tar.gz" "$ROOT/args" || fail 'Local path was split'
grep -Fxq 'root@example.invalid:/tmp/macro-research-deploy.Abc123/release.tar.gz' "$ROOT/args" || fail 'Wrong target'
grep -q '100%' "$ROOT/output" || fail 'Missing actual progress'
grep -q 'KiB/s' "$ROOT/output" || fail 'Missing throughput'
grep -q '| release.tar.gz | 15 | rsync | 1 |' "$ROOT/summary" || fail 'Missing summary'

DEPLOY_HOST=2001:db8::1 run_upload
grep -Fxq 'root@[2001:db8::1]:/tmp/macro-research-deploy.Abc123/release.tar.gz' "$ROOT/args" || fail 'IPv6 destination not bracketed'
TEST_MODE=slow run_upload
grep -q '33%' "$ROOT/output" || fail 'Missing in-flight byte progress'

for code in 10 12 30; do
    TEST_MODE=retry TEST_FAIL_CODE="$code" run_upload
    assert_calls 2
    [[ -f "$ROOT/resumed" ]] || fail 'Partial file not reused'
    cmp "$ROOT/partial" "$ROOT/package with spaces.tar.gz" || fail 'Wrong completed content'
    grep -q '| rsync | 2 |' "$ROOT/summary" || fail 'Retry count not reported'
done
TEST_MODE=retry-timeout UPLOAD_ATTEMPT_SECONDS=2 run_upload
assert_calls 2
[[ -f "$ROOT/resumed" ]] || fail 'Attempt timeout did not preserve partial file'

for code in 11 23 255; do
    status=0
    TEST_MODE=failure TEST_FAIL_CODE="$code" run_upload || status=$?
    [[ "$status" == "$code" ]] || fail 'Permanent error was hidden'
    assert_calls 1
    ! grep -q '上传完成' "$ROOT/output" || fail 'Failure reported as success'
done
status=0
TEST_MODE=failure TEST_FAIL_CODE=12 run_upload || status=$?
[[ "$status" == 12 ]] || fail 'Final transient error was hidden'
assert_calls 2

started=$SECONDS
status=0
TEST_MODE=hang UPLOAD_ATTEMPT_SECONDS=2 run_upload 8 || status=$?
[[ "$status" == 124 || "$status" == 137 ]] || fail "Wrong timeout status: $status"
(( SECONDS - started < 13 )) || fail 'Retries multiplied the total budget'
grep -q '上传超时' "$ROOT/output" || fail 'Missing timeout diagnosis'

# Small scripts stay SCP; no capability probe or retry.
run_upload 10 auto-deploy.sh
assert_calls 1
[[ ! -s "$ROOT/probes" ]] || fail 'Small script unnecessarily probed rsync'
assert_security "$ROOT/args"
grep -Fxq scp "$ROOT/calls" || fail 'Small script not using SCP'
TEST_PROBE=missing run_upload
assert_calls 1
grep -Fxq scp "$ROOT/calls" || fail 'No SCP fallback for missing remote rsync'
assert_security "$ROOT/args"
grep -q '无续传和字节进度' "$ROOT/output" || fail 'Fallback limitations not visible'
UPLOAD_TRANSPORT=scp TEST_MODE=slow run_upload
grep -q '尚无字节进度更新' "$ROOT/output" || fail 'SCP heartbeat misrepresents progress'

status=0
TEST_PROBE=failure run_upload || status=$?
[[ "$status" != 0 ]] || fail 'Host verification failure ignored'
assert_calls 0
status=0
UPLOAD_TRANSPORT=rsync TEST_PROBE=missing run_upload || status=$?
[[ "$status" != 0 ]] || fail 'Explicit rsync silently downgraded'
assert_calls 0
status=0
TEST_PROBE=slow run_upload 1 || status=$?
[[ "$status" != 0 ]] || fail 'Probe exceeded total budget'
assert_calls 0

status=0
UPLOAD_TRANSPORT=scp TEST_MODE=failure run_upload || status=$?
[[ "$status" == 23 ]] || fail 'SCP failure hidden'
assert_calls 1
status=0
UPLOAD_TRANSPORT=scp TEST_MODE=hang run_upload 1 || status=$?
[[ "$status" == 124 || "$status" == 137 ]] || fail 'SCP timeout hidden'
assert_calls 1

if REMOTE_DIR=/root/macro-research run_upload; then fail 'Unsafe destination accepted'; fi
if DEPLOY_PORT=65536 run_upload; then fail 'Invalid port accepted'; fi
if UPLOAD_TRANSPORT=unknown run_upload; then fail 'Invalid transport accepted'; fi
if UPLOAD_ATTEMPT_SECONDS=0 run_upload; then fail 'Unbounded attempt accepted'; fi
if run_upload 0; then fail 'Unbounded total timeout accepted'; fi
assert_calls 0
printf 'All upload regression tests passed.\n'
