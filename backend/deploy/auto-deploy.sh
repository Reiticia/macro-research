#!/usr/bin/env bash
# Deploy a CI archive into an existing Linux/tmux installation. Never overwrite config or data.
# Requires a manually acknowledged config.example.toml hash in .deploy-config-example.sha256.
set -Eeuo pipefail
umask 077

BIN_NAME=market-event-analyzer
APP_DIR=/root/macro-research
PANE=macro-research:0.0
OBSERVE_SECONDS=60
START_TIMEOUT=20
STOP_TIMEOUT=30
ARCHIVE=""; ARCHIVE_HASH=""; CONFIG_HASH=""; REVISION=""
STAGE=""; NEXT_BINARY=""
BACKEND_STOPPED=0
BINARY_REPLACED=0

log() { printf '[auto-deploy] %s\n' "$*"; }
die() { printf '[auto-deploy] ERROR: %s\n' "$*" >&2; exit 1; }

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --dir) APP_DIR="${2:?}"; shift 2 ;;
            --pane) PANE="${2:?}"; shift 2 ;;
            --archive) ARCHIVE="${2:?}"; shift 2 ;;
            --archive-sha256) ARCHIVE_HASH="${2:?}"; shift 2 ;;
            --config-sha256) CONFIG_HASH="${2:?}"; shift 2 ;;
            --revision) REVISION="${2:?}"; shift 2 ;;
            --observe-seconds) OBSERVE_SECONDS="${2:?}"; shift 2 ;;
            *) die "Unknown argument: $1" ;;
        esac
    done
    [[ "$ARCHIVE_HASH" =~ ^[a-f0-9]{64}$ && "$CONFIG_HASH" =~ ^[a-f0-9]{64}$ ]] || die 'Missing/invalid SHA256 hash'
    [[ "$REVISION" =~ ^[a-f0-9]{40}$ ]] || die 'Missing/invalid Git revision'
    [[ "$OBSERVE_SECONDS" =~ ^[1-9][0-9]{0,2}$ ]] || die 'Observation window must be a positive integer of at most three digits'
    (( OBSERVE_SECONDS >= 30 && OBSERVE_SECONDS <= 600 )) || die 'Observation window must be 30..600 seconds'
    [[ -f "$ARCHIVE" && ! -L "$ARCHIVE" ]] || die 'Archive not found or is a symlink'
    [[ -d "$APP_DIR" ]] || die "Installation directory not found: $APP_DIR"
    APP_DIR="$(cd -- "$APP_DIR" && pwd -P)"
    ARCHIVE="$(realpath -- "$ARCHIVE")"
}

check_config_baseline() {
    local baseline=""
    [[ ! -f "$APP_DIR/.deploy-config-example.sha256" ]] || baseline="$(< "$APP_DIR/.deploy-config-example.sha256")"
    if [[ "$baseline" != "$CONFIG_HASH" ]]; then
        log 'Configuration template changed or has no acknowledged baseline. Manual deployment required.'
        printf 'DEPLOY_RESULT=manual_required\n'
        return 1
    fi
}

check_runtime() {
    [[ "$(uname -s)" == Linux ]] || die 'This script requires Linux /proc'
    [[ "$EUID" -eq 0 ]] || die 'Use the root SSH account for /root/macro-research'
    local command
    for command in tmux sqlite3 sha256sum tar install flock ps readlink tee cmp; do
        command -v "$command" >/dev/null || die "Required command is missing: $command"
    done
    for file in backup.sh config.toml rules.toml "$BIN_NAME"; do
        [[ -f "$APP_DIR/$file" ]] || die "Required installation file is missing: $file"
    done
    [[ -f "$APP_DIR/data/market.db" ]] || die 'Database is missing'
    # tmux is stopped explicitly before backup.sh, which itself only manages systemd.
    # Keep sqlite3 mandatory for consistent snapshots and database integrity checks.
    exec 9> "$APP_DIR/.auto-deploy.lock"
    flock -n 9 || die 'Another deployment is running'
}

is_descendant() {
    local pid="$1" ancestor="$2" parent
    while [[ "$pid" =~ ^[0-9]+$ ]] && (( pid > 1 )); do
        [[ "$pid" != "$ancestor" ]] || return 0
        parent="$(ps -o ppid= -p "$pid" 2>/dev/null)" || return 1
        parent="${parent//[[:space:]]/}"
        [[ "$parent" != "$pid" ]] || return 1
        pid="$parent"
    done
    return 1
}

backend_pids() {
    local entry executable
    for entry in /proc/[0-9]*/exe; do
        executable="$(readlink "$entry" 2>/dev/null)" || continue
        if [[ "$executable" == "$APP_DIR/$BIN_NAME" || "$executable" == "$APP_DIR/$BIN_NAME (deleted)" ]]; then
            entry="${entry#/proc/}"
            printf '%s\n' "${entry%/exe}"
        fi
    done
}

foreground_group() {
    ps -o tpgid= -p "$SHELL_PID" | tr -d '[:space:]'
}

check_pane() {
    local dead shell_exe foreground group
    read -r PANE_ID SHELL_PID dead < <(tmux display-message -p -t "$PANE" '#{pane_id} #{pane_pid} #{pane_dead}')
    [[ "$PANE_ID" =~ ^%[0-9]+$ && "$SHELL_PID" =~ ^[0-9]+$ && "$dead" == 0 ]] || die 'The tmux pane is missing/dead'
    shell_exe="$(readlink "/proc/$SHELL_PID/exe")"
    case "${shell_exe##*/}" in bash|zsh|sh|dash|fish) ;; *) die 'The pane must have an interactive shell, not a directly launched binary' ;; esac
    local pids=()
    mapfile -t pids < <(backend_pids)
    [[ "${#pids[@]}" -eq 1 ]] || die 'Expected exactly one running backend in this installation'
    OLD_PID="${pids[0]}"
    is_descendant "$OLD_PID" "$SHELL_PID" || die 'The existing backend does not belong to the selected tmux pane'
    foreground="$(foreground_group)"
    group="$(ps -o pgid= -p "$OLD_PID" | tr -d '[:space:]')"
    [[ -n "$group" && "$group" == "$foreground" ]] || die 'The backend is not the foreground job; refusing to send Ctrl+C'
}

cleanup() {
    [[ -z "$STAGE" ]] || rm -rf -- "$STAGE"
    [[ -z "$NEXT_BINARY" ]] || rm -f -- "$NEXT_BINARY"
}

handle_exit() {
    local status=$? recovery_status
    trap - EXIT
    # Before replacement there have been no migrations by the new program. Restarting the
    # unchanged old binary is safe; never auto-rollback after the new binary is installed.
    if [[ "$BACKEND_STOPPED" -eq 1 && "$BINARY_REPLACED" -eq 0 ]] \
        && cmp -s "$APP_DIR/$BIN_NAME" "$RUN_DIR/previous-binary"; then
        log 'Deployment aborted before replacement; attempting to restart the unchanged old backend'
        # A separate, strictly checked subshell lets recovery fail without swallowing the
        # original backup/deployment failure. Do not run this subshell in an if condition:
        # that would disable errexit inside its functions.
        set +e
        (
            set -Eeuo pipefail
            start_backend
            observe_backend
        )
        recovery_status=$?
        set -e
        if [[ "$recovery_status" -eq 0 ]]; then
            log 'Old backend restarted and verified; the deployment is still unsuccessful'
        else
            log "Old backend recovery failed; manual intervention required. Inspect $RUN_DIR/backend.log"
        fi
    fi
    cleanup || true
    exit "$status"
}

stage_release() {
    local actual member listing
    actual="$(sha256sum "$ARCHIVE")"; actual="${actual%% *}"
    [[ "$actual" == "$ARCHIVE_HASH" ]] || die 'Archive SHA256 verification failed'
    listing="$(tar -tzf "$ARCHIVE")" || die 'Cannot read release archive'
    STAGE="$(mktemp -d "$APP_DIR/.auto-deploy-stage.XXXXXXXX")"
    local members=()
    mapfile -t members < <(printf '%s\n' "$listing" | grep -E '^[^/]+/market-event-analyzer$')
    [[ "${#members[@]}" -eq 1 ]] || die 'Archive must contain exactly one packaged backend binary'
    member="${members[0]}"
    [[ "$member" != ../* && "$member" != ./* ]] || die 'Unsafe archive path'
    # Extract only the executable, never the packaged config.toml/rules.toml or other members.
    tar -xzf "$ARCHIVE" -C "$STAGE" --no-same-owner --no-same-permissions -- "$member"
    [[ -f "$STAGE/$member" && ! -L "$STAGE/$member" && -s "$STAGE/$member" ]] || die 'Packaged binary is not a regular nonempty file'
    NEXT_BINARY="$STAGE/${BIN_NAME}.next"
    install -m 0755 "$STAGE/$member" "$NEXT_BINARY"
    RUN_DIR="$(mktemp -d "$APP_DIR/.deploy-run-${REVISION:0:12}.XXXXXXXX")"
    chmod 0700 "$RUN_DIR"
    # Keep a binary copy for manual recovery. Do not auto-rollback a possibly migrated database.
    cp -p -- "$APP_DIR/$BIN_NAME" "$RUN_DIR/previous-binary"
}

stop_backend() {
    # Preflight/staging may take time. Never interrupt a different foreground job.
    local group
    kill -0 "$OLD_PID" 2>/dev/null || die 'Old backend exited before restart; refusing to send Ctrl+C'
    is_descendant "$OLD_PID" "$SHELL_PID" || die 'Old backend no longer belongs to the selected pane'
    group="$(ps -o pgid= -p "$OLD_PID" | tr -d '[:space:]')"
    [[ -n "$group" && "$group" == "$(foreground_group)" ]] || die 'Foreground job changed during preflight; refusing to send Ctrl+C'
    log "Sending Ctrl+C to $PANE_ID (old PID $OLD_PID)"
    tmux send-keys -t "$PANE_ID" C-c
    local deadline=$((SECONDS + STOP_TIMEOUT))
    while kill -0 "$OLD_PID" 2>/dev/null; do
        (( SECONDS < deadline )) || die 'Old backend did not stop; installed binary was not replaced'
        sleep 1
    done
    # Wait until the interactive shell owns the foreground terminal again.
    local shell_group
    shell_group="$(ps -o pgid= -p "$SHELL_PID" | tr -d '[:space:]')"
    until [[ "$(foreground_group)" == "$shell_group" ]]; do
        (( SECONDS < deadline )) || die 'tmux did not return to the interactive shell'
        sleep 1
    done
    [[ -z "$(backend_pids)" ]] || die 'Another backend process appeared; refusing to replace the binary'
}

start_backend() {
    local launch="$RUN_DIR/launch.sh" command
    {
        printf '#!/usr/bin/env bash\nset -eu\n'
        printf 'cd -- %q\n' "$APP_DIR"
        printf 'printf "%%s\\n" "$$" > %q\n' "$RUN_DIR/pid"
        # tee preserves the tmux console and also keeps diagnostic logs on the server.
        printf 'exec env APP_CONFIG=%q %q > >(tee -a %q) 2>&1\n' "$APP_DIR/config.toml" "$APP_DIR/$BIN_NAME" "$RUN_DIR/backend.log"
    } > "$launch"
    chmod 0700 "$launch"
    printf -v command 'bash %q' "$launch"
    tmux send-keys -t "$PANE_ID" C-u
    tmux send-keys -l -t "$PANE_ID" "$command"
    tmux send-keys -t "$PANE_ID" C-m
}

pid_is_new_backend() {
    [[ "$NEW_PID" =~ ^[0-9]+$ && "$NEW_PID" != "$OLD_PID" ]] || return 1
    kill -0 "$NEW_PID" 2>/dev/null || return 1
    [[ "$(readlink "/proc/$NEW_PID/exe" 2>/dev/null)" == "$APP_DIR/$BIN_NAME" ]] || return 1
    is_descendant "$NEW_PID" "$SHELL_PID"
}

observe_backend() {
    local deadline=$((SECONDS + START_TIMEOUT)) start_token current_token
    NEW_PID=""
    until [[ -s "$RUN_DIR/pid" ]]; do
        (( SECONDS < deadline )) || die "Backend launch did not produce a PID. Inspect $RUN_DIR/backend.log on the server"
        sleep 1
    done
    NEW_PID="$(< "$RUN_DIR/pid")"
    until pid_is_new_backend; do
        kill -0 "$NEW_PID" 2>/dev/null || die "New backend exited during startup. Inspect $RUN_DIR/backend.log on the server"
        (( SECONDS < deadline )) || die "New PID never became the backend executable. Inspect $RUN_DIR/backend.log"
        sleep 1
    done
    # /proc starttime distinguishes this process from a later reuse of the same numeric PID.
    start_token="$(awk '{sub(/^.*\) /, ""); print $20}' "/proc/$NEW_PID/stat")"
    deadline=$((SECONDS + OBSERVE_SECONDS))
    log "New backend PID $NEW_PID; observing for $OBSERVE_SECONDS seconds"
    while (( SECONDS < deadline )); do
        pid_is_new_backend || die "New backend stopped during observation. Inspect $RUN_DIR/backend.log on the server"
        current_token="$(awk '{sub(/^.*\) /, ""); print $20}' "/proc/$NEW_PID/stat" 2>/dev/null)" || die 'New backend disappeared during observation'
        [[ "$current_token" == "$start_token" ]] || die 'Backend PID was reused during observation'
        sleep 1
    done
    pid_is_new_backend || die 'New backend stopped at the end of observation'
    [[ "$(awk '{sub(/^.*\) /, ""); print $20}' "/proc/$NEW_PID/stat" 2>/dev/null)" == "$start_token" ]] || die 'Backend PID changed at the end of observation'
}

record_deployment() {
    printf '%s\n' "$CONFIG_HASH" > "$APP_DIR/.deploy-config-example.sha256.tmp"
    mv -f -- "$APP_DIR/.deploy-config-example.sha256.tmp" "$APP_DIR/.deploy-config-example.sha256"
    printf '%s\n' "$REVISION" > "$APP_DIR/.deploy-revision.tmp"
    mv -f -- "$APP_DIR/.deploy-revision.tmp" "$APP_DIR/.deploy-revision"
    log "Deployment succeeded: $REVISION. Logs: $RUN_DIR/backend.log"
    printf 'DEPLOY_RESULT=success\n'
}

main() {
    parse_args "$@"
    check_config_baseline || return 0
    check_runtime
    # Recheck after taking the lock, before any backup or process changes.
    check_config_baseline || return 0
    check_pane
    trap handle_exit EXIT
    # Validate/unpack into staging before downtime; the installed binary stays unchanged.
    stage_release
    check_config_baseline || return 0
    stop_backend
    BACKEND_STOPPED=1
    log 'Old backend stopped; backing up the installation before replacement'
    (cd -- "$APP_DIR" && bash ./backup.sh --dir "$APP_DIR")
    # Atomic replacement avoids ETXTBSY and never truncates a running executable.
    mv -f -- "$NEXT_BINARY" "$APP_DIR/$BIN_NAME"
    BINARY_REPLACED=1
    NEXT_BINARY=""
    start_backend
    observe_backend
    record_deployment
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
