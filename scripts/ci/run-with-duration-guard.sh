#!/usr/bin/env bash
set -uo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <max-seconds> <command...>" >&2
  exit 64
fi

max_seconds="$1"
shift

if ! [[ "$max_seconds" =~ ^[0-9]+$ ]]; then
  echo "max-seconds must be an integer: $max_seconds" >&2
  exit 64
fi

label="${DURATION_LABEL:-$*}"
start_epoch="$(date +%s)"

set +e
"$@"
status=$?
set -e

end_epoch="$(date +%s)"
elapsed="$((end_epoch - start_epoch))"

echo "[duration-guard] ${label} took ${elapsed}s (limit: ${max_seconds}s)"

if [ "$status" -ne 0 ]; then
  echo "::error::${label} failed with exit code ${status}" >&2
  exit "$status"
fi

if [ "$elapsed" -gt "$max_seconds" ]; then
  echo "::error::${label} exceeded duration limit (${elapsed}s > ${max_seconds}s)" >&2
  exit 124
fi
