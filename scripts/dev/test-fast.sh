#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

gradle_args=(test --continue)

if [[ "${INCLUDE_INTEGRATION:-0}" == "1" || "${INCLUDE_EXTERNAL:-0}" == "1" ]]; then
  gradle_args+=(-PincludeIntegration)
fi

if [[ "${INCLUDE_MATRIX:-0}" == "1" ]]; then
  gradle_args+=(-PincludeMatrix)
fi

if [[ "${INCLUDE_EXTERNAL:-0}" == "1" ]]; then
  gradle_args+=(-PincludeExternalIntegration)
fi

if [[ "${NO_DAEMON:-0}" == "1" ]]; then
  gradle_args+=(--no-daemon)
fi

if [[ "${NO_BUILD_CACHE:-0}" == "1" ]]; then
  gradle_args+=(--no-build-cache)
fi

echo "Running: ./gradlew ${gradle_args[*]}"
exec ./gradlew "${gradle_args[@]}"
