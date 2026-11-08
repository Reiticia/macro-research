#!/usr/bin/env bash
# CI-side uploads only. Retry a transfer, NEVER the stop/backup/deploy operation.
set -Eeuo pipefail
export LC_ALL=C

fail() { printf '::error::%s\n' "$*" >&2; exit 1; }
log() { printf '[upload %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
[[ $# -eq 3 ]] || fail 'Usage: upload-artifact.sh LOCAL_FILE REMOTE_FILENAME TOTAL_TIMEOUT_SECONDS'
file="$1"; name="$2"; budget="$3"
[[ -f "$file" && ! -L "$file" ]] || fail 'Upload file missing or is a symlink'
[[ "$name" == release.tar.gz || "$name" == auto-deploy.sh ]] || fail 'Unexpected upload filename'
[[ "$budget" =~ ^[1-9][0-9]{0,2}$ ]] || fail 'Invalid upload timeout (1..999 seconds)'
[[ "${REMOTE_DIR:-}" =~ ^/tmp/macro-research-deploy\.[a-zA-Z0-9]+$ ]] || fail 'Invalid staging directory'
[[ "${DEPLOY_HOST:-}" =~ ^[a-zA-Z0-9._:-]+$ && "${DEPLOY_USER:-}" == root ]] || fail 'Invalid SSH destination'
[[ "${DEPLOY_PORT:-}" =~ ^[1-9][0-9]{0,4}$ ]] && (( DEPLOY_PORT <= 65535 )) || fail 'Invalid SSH port'
[[ -f "${SSH_DIR:?}/key" && -f "$SSH_DIR/known_hosts" ]] || fail 'Missing SSH credentials'
heartbeat="${UPLOAD_HEARTBEAT_SECONDS:-15}"
attempt_limit="${UPLOAD_ATTEMPT_SECONDS:-600}"
transport="${UPLOAD_TRANSPORT:-auto}"
[[ "$heartbeat" =~ ^[1-9][0-9]?$ ]] || fail 'Invalid heartbeat interval'
[[ "$attempt_limit" =~ ^[1-9][0-9]{0,2}$ ]] || fail 'Invalid attempt timeout'
[[ "$transport" =~ ^(auto|rsync|scp)$ ]] || fail 'Invalid upload transport'
# Absolute local paths prevent rsync interpreting a colon as a remote source.
file="$(cd -- "$(dirname -- "$file")" && pwd)/$(basename -- "$file")"
host="$DEPLOY_HOST"
[[ "$host" != *:* ]] || host="[$host]"
common=(-i "$SSH_DIR/key" -o BatchMode=yes -o IdentitiesOnly=yes
    -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$SSH_DIR/known_hosts"
    -o ConnectTimeout=15 -o ConnectionAttempts=1
    -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
ssh_options=(-p "$DEPLOY_PORT" "${common[@]}")
scp_options=(-P "$DEPLOY_PORT" "${common[@]}")
bytes="$(wc -c < "$file")"; bytes="${bytes//[[:space:]]/}"
started=$SECONDS
pid=""
work="$(mktemp -d)"
cleanup() {
    if [[ -n "$pid" ]]; then
        # GNU timeout owns the transfer process group and forwards TERM to it.
        kill -TERM "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log "开始上传 $name：$bytes bytes，总预算 ${budget}s（含探测、重试；最终强制终止最多另等 10s）；尚未停服"
# Optional capability probe, never install software on the production server.
# Distinguish missing rsync from failed SSH: authentication/host-key errors fail closed.
if [[ "$transport" == auto && "$name" == auto-deploy.sh ]]; then transport=scp; fi
if [[ "$transport" != scp ]]; then
    if command -v rsync >/dev/null 2>&1; then
        remaining=$((budget - SECONDS + started))
        (( remaining > 0 )) || fail 'Upload budget exhausted before capability probe'
        probe_limit=$remaining
        (( probe_limit <= 20 )) || probe_limit=20
        probe_status=0
        timeout --kill-after=10s "${probe_limit}s" ssh "${ssh_options[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
            'if command -v rsync >/dev/null 2>&1; then exit 0; else exit 42; fi' || probe_status=$?
        case "$probe_status" in
            0) transport=rsync ;;
            42)
                [[ "$transport" == auto ]] || fail 'Remote rsync is missing; install it before forcing rsync'
                log '远端未安装 rsync，回退 SCP（无续传和字节进度）；安装 rsync 后后续部署自动启用续传'
                transport=scp ;;
            *) fail "SSH capability probe failed (exit $probe_status); refusing protocol fallback" ;;
        esac
    else
        [[ "$transport" == auto ]] || fail 'Local rsync is missing'
        log 'Runner 未安装 rsync，回退 SCP（无续传和字节进度）'
        transport=scp
    fi
fi

# rsync parses -e itself, not as Bash; use a wrapper to preserve spaces/quotes in
# identity and known_hosts paths without constructing an eval-able shell string.
if [[ "$transport" == rsync ]]; then
    {
        printf '#!/usr/bin/env bash\nexec ssh'
        printf ' %q' "${ssh_options[@]}"
        printf ' "$@"\n'
    } > "$work/backend-upload-ssh"
    chmod 0700 "$work/backend-upload-ssh"
    export PATH="$work:$PATH"
    command=(rsync -e backend-upload-ssh --partial --append-verify
        --timeout=90 --info=progress2 --outbuf=L --no-human-readable
        -- "$file" "$DEPLOY_USER@$host:$REMOTE_DIR/$name")
    max_attempts=2
else
    command=(scp "${scp_options[@]}" "$file" "$DEPLOY_USER@$host:$REMOTE_DIR/$name")
    max_attempts=1
fi
log "传输方式：$transport；最多 $max_attempts 次尝试；不重试停服/备份/替换"
summary() {
    [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
    printf '### 上传 %s\n\n| 文件 | 字节数 | 方式 | 尝试 | 总耗时 | 有效平均速度／结果 |\n| --- | ---: | --- | ---: | ---: | --- |\n| %s | %s | %s | %s | %ss | %s |\n' \
        "$name" "$name" "$bytes" "$transport" "$attempt" "$elapsed" "$1" >> "$GITHUB_STEP_SUMMARY"
}
progress() {
    local line
    # Bound the log read even during a fast transfer. These are rsync's latest
    # actual progress counters, NOT independently verified remote/durable bytes.
    line="$(tail -c 4096 "$work/stdout" | tr '\r' '\n' | grep -E '^[[:space:]]*[0-9,]+[[:space:]]+[0-9]+%' | tail -n 1 || true)"
    if [[ -n "$line" ]]; then
        log "尝试 $attempt；已用 $((SECONDS - started))s；rsync 最近报告（字节 / 百分比 / 速率 / 时间）：$line"
    else
        log "尝试 $attempt；已用 $((SECONDS - started))s；尚无字节进度更新（心跳不表示数据仍在前进）"
    fi
}
status=124
attempt=0
for ((next=1; next<=max_attempts; next++)); do
    remaining=$((budget - SECONDS + started))
    if (( remaining <= 0 )); then status=124; break; fi
    attempt=$next
    limit=$remaining
    if [[ "$transport" == rsync ]] && (( limit > attempt_limit )); then limit=$attempt_limit; fi
    log "尝试 $attempt/$max_attempts，上限 ${limit}s，剩余总预算 ${remaining}s"
    : > "$work/stdout"
    : > "$work/stderr"
    timeout --signal=TERM --kill-after=10s "${limit}s" "${command[@]}" > "$work/stdout" 2> "$work/stderr" &
    pid=$!
    last=$SECONDS
    while kill -0 "$pid" 2>/dev/null; do
        sleep 1
        if (( SECONDS - last >= heartbeat )); then progress; last=$SECONDS; fi
    done
    status=0
    wait "$pid" || status=$?
    pid=""
    progress
    # Prefix subprocess output so it cannot emit GitHub workflow commands.
    tail -c 4096 "$work/stderr" | tr '\r' '\n' | awk '{ print "[transfer stderr] " $0 }'
    (( status != 0 )) || break
    log "尝试 $attempt 失败，exit=$status"
    # Partial-file/permission/disk errors (23, 11, etc.) are not transient.
    # SSH 255 includes auth/host-key errors: do not blindly retry those either.
    case "$status" in 10|12|30|35|124|137) ;; *) break ;; esac
    [[ "$transport" == rsync && "$attempt" -lt "$max_attempts" ]] || break
    remaining=$((budget - SECONDS + started))
    (( remaining > 3 )) || break
    log '3s 后在同一暂存目录续传；已有部分不删除，--append-verify 校验已有前缀'
    sleep 3
done
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
log "$name 上传完成：${elapsed}s，有效平均 ${rate} KiB/s（含连接/重试/等待；仍须通过远端 SHA256 复核）"
summary "$rate KiB/s"
