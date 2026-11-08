#!/usr/bin/env bash
# Real rsync client/server, fake SSH executing the server locally. No network.
# Linux CI exercises this; Windows Git Bash without rsync skips it explicitly.
set -Eeuo pipefail
if ! command -v rsync >/dev/null 2>&1 || [[ "$(uname -s)" != Linux ]]; then
    echo 'SKIP: real rsync upload test requires Linux and rsync (mock tests still run).'
    exit 0
fi
SCRIPT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/upload-artifact.sh"
ROOT="$(mktemp -d)"
REMOTE_DIR="$(mktemp -d /tmp/macro-research-deploy.XXXXXXXX)"
trap 'rm -rf -- "$ROOT" "$REMOTE_DIR"' EXIT
fail() {
    printf 'FAIL: %s\n' "$*" >&2
    if [[ -f "$ROOT/output" ]]; then tail -n 60 "$ROOT/output" >&2; fi
    exit 1
}
mkdir -p "$ROOT/bin" "$ROOT/ssh dir'quote" "$ROOT/tmp dir'quote"
touch "$ROOT/ssh dir'quote/key" "$ROOT/ssh dir'quote/known_hosts"
cat > "$ROOT/bin/ssh" <<'EOF'
#!/usr/bin/env bash
set -eu
while [[ "$1" == -* ]]; do
    case "$1" in
        -i|-p|-o|-l) shift 2 ;;
        *) echo "Unexpected SSH option: $1" >&2; exit 99 ;;
    esac
done
[[ "$1" == example.invalid || "$1" == root@example.invalid ]]
shift
if [[ "$*" == *'command -v rsync'* ]]; then exit 0; fi
[[ "$1" == rsync && "$2" == --server ]]
exec "$@"
EOF
chmod +x "$ROOT/bin/ssh"
export PATH="$ROOT/bin:$PATH" REMOTE_DIR
export SSH_DIR="$ROOT/ssh dir'quote" TMPDIR="$ROOT/tmp dir'quote"
export DEPLOY_HOST=example.invalid DEPLOY_USER=root DEPLOY_PORT=22
export UPLOAD_TRANSPORT=rsync UPLOAD_HEARTBEAT_SECONDS=1
export GITHUB_STEP_SUMMARY="$ROOT/summary"
dd if=/dev/urandom of="$ROOT/package with spaces.tar.gz" bs=1024 count=256 status=none
for state in empty partial corrupt; do
    printf 'Testing real rsync upload: %s\n' "$state"
    rm -f "$REMOTE_DIR/release.tar.gz"
    case "$state" in
        partial) head -c 65536 "$ROOT/package with spaces.tar.gz" > "$REMOTE_DIR/release.tar.gz" ;;
        corrupt) dd if=/dev/zero of="$REMOTE_DIR/release.tar.gz" bs=1024 count=64 status=none ;;
    esac
    bash "$SCRIPT" "$ROOT/package with spaces.tar.gz" release.tar.gz 30 > "$ROOT/output" 2>&1 \
        || fail "$state: upload failed"
    cmp "$ROOT/package with spaces.tar.gz" "$REMOTE_DIR/release.tar.gz" \
        || fail "$state: received content differs"
    hash="$(sha256sum "$ROOT/package with spaces.tar.gz" | cut -d' ' -f1)"
    (cd "$REMOTE_DIR" && printf '%s  release.tar.gz\n' "$hash" | sha256sum -c -) \
        || fail "$state: SHA256 mismatch"
    # progress2 counts retransmitted data too. A corrupt append prefix can cause
    # a second transfer and a final cumulative percentage above 100 (e.g. 200%).
    # Transfer success is established by exit status + cmp + SHA256, not its UI %.
    grep -E 'rsync 最近报告.*[0-9]+%' "$ROOT/output" \
        || fail "$state: missing rsync progress report"
done
echo 'Real rsync upload tests passed (fresh, resume, corrupt-prefix recovery).'
