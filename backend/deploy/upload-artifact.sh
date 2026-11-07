#!/usr/bin/env bash
# CI-side bounded SCP only. Never stop/restart the backend, and never retry deployment.
set -Eeuo pipefail

fail() { printf '::error::%s\n' "$*" >&2; exit 1; }
log() { printf '[upload %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
[[ $# -eq 3 ]] || fail 'Usage: upload-artifact.sh LOCAL_FILE REMOTE_FILENAME TIMEOUT_SECONDS'
file="$1"; name="$2"; budget="$3"
[[ -f "$file" && ! -L "$file" ]] || fail 'Upload file missing or is a symlink'
[[ "$name" == release.tar.gz || "$name" == auto-deploy.sh ]] || fail 'Unexpected upload filename'
[[ "$budget" =~ ^[1-9][0-9]{0,2}$ ]] || fail 'Invalid upload timeout (1..999 seconds)'
[[ "${REMOTE_DIR:-}" =~ ^/tmp/macro-research-deploy\.[a-zA-Z0-9]+$ ]] || fail 'Invalid staging directory'
[[ "${DEPLOY_HOST:-}" =~ ^[a-zA-Z0-9._:-]+$ && "${DEPLOY_USER:-}" == root ]] || fail 'Invalid SSH destination'
[[ "${DEPLOY_PORT:-}" =~ ^[1-9][0-9]{0,4}$ ]] && (( DEPLOY_PORT <= 65535 )) || fail 'Invalid SSH port'
[[ -f "${SSH_DIR:?}/key" && -f "$SSH_DIR/known_hosts" ]] || fail 'Missing SSH credentials'
heartbeat="${UPLOAD_HEARTBEAT_SECONDS:-15}"
[[ "$heartbeat" =~ ^[1-9][0-9]?$ ]] || fail 'Invalid heartbeat interval'

host="$DEPLOY_HOST"
[[ "$host" != *:* ]] || host="[$host]"
options=(-i "$SSH_DIR/key" -P "$DEPLOY_PORT"
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$SSH_DIR/known_hosts" -o ConnectTimeout=15
    -o ConnectionAttempts=1 -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
bytes="$(wc -c < "$file")"; bytes="${bytes//[[:space:]]/}"
log "开始上传 $name：$bytes bytes，传输上限 ${budget}s（超时后最多另等 10s 强制终止）；旧后端不受此步骤影响"
summary() {
    [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
    # Each Actions step has its own summary file: write a complete table per upload.
    printf '### 上传 %s\n\n| 文件 | 字节数 | 耗时 | 平均速度／结果 |\n| --- | ---: | ---: | --- |\n| %s | %s | %ss | %s |\n' \
        "$name" "$name" "$bytes" "$elapsed" "$1" >> "$GITHUB_STEP_SUMMARY"
}
started=$SECONDS
pid=""
cleanup() {
    if [[ -n "$pid" ]]; then
        # GNU timeout forwards TERM to its managed SCP process group.
        kill -TERM "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

timeout --signal=TERM --kill-after=10s "${budget}s" scp "${options[@]}" "$file" "$DEPLOY_USER@$host:$REMOTE_DIR/$name" &
pid=$!
last=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
    sleep 1
    if (( SECONDS - last >= heartbeat )); then
        log "$name 仍在上传，已等待 $((SECONDS - started))s（心跳不是字节进度）"
        last=$SECONDS
    fi
done
status=0
wait "$pid" || status=$?
pid=""
elapsed=$((SECONDS - started))
if (( status != 0 )); then
    if (( status == 124 || status == 137 )); then
        log "$name 上传超时，耗时 ${elapsed}s；不会执行后续停服部署"
    else
        log "$name 上传失败，exit=$status，耗时 ${elapsed}s；不会执行后续停服部署"
    fi
    summary "失败 (exit $status)"
    exit "$status"
fi
rate="$(awk -v bytes="$bytes" -v seconds="$elapsed" 'BEGIN { printf "%.1f", bytes / 1024 / (seconds > 0 ? seconds : 1) }')"
log "$name 上传完成：${elapsed}s，平均 ${rate} KiB/s（含连接耗时）"
summary "$rate KiB/s"
