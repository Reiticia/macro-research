#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$root/release-version.sh"
manifest="$root/../Cargo.toml"
crate="$(awk -F'"' '/^version[[:space:]]*=/{ print $2; exit }' "$manifest")"
export GITHUB_REF_TYPE=tag GITHUB_REF_NAME=v99.99.99
unset RELEASE_VERSION || true
[[ "$(resolve_release_version "$manifest")" == "$crate" ]]
export RELEASE_VERSION=0.14.0
[[ "$(resolve_release_version "$manifest")" == 0.14.0 ]]
[[ "$(resolve_release_version "$manifest" 1.2.3)" == 1.2.3 ]]
if resolve_release_version "$manifest" '../unsafe' >/dev/null 2>&1; then exit 1; fi
if resolve_release_version "$manifest" 'v1.2.3' >/dev/null 2>&1; then exit 1; fi
printf 'release version tests passed\n'
