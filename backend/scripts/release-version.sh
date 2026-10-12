#!/usr/bin/env bash
# Shared, side-effect-free version resolution. Android tags are NOT backend crate versions.
resolve_release_version() {
    local manifest="$1" explicit="${2:-}" value
    value="${explicit:-${RELEASE_VERSION:-}}"
    if [[ -z "$value" ]]; then
        value="$(awk -F'"' '/^version[[:space:]]*=/{ print $2; exit }' "$manifest")"
    fi
    if [[ ! "$value" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
        printf 'Invalid release version\n' >&2
        return 1
    fi
    printf '%s' "$value"
}
