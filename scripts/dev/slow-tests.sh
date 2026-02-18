#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

limit="${1:-20}"

xml_count="$(find . -path "*/build/test-results/test/TEST-*.xml" 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$xml_count" == "0" ]]; then
  echo "No JUnit XML reports found. Run tests first (e.g., ./gradlew test)."
  exit 0
fi

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

find . -path "*/build/test-results/test/TEST-*.xml" 2>/dev/null | sort | while IFS= read -r file; do
  time_value="$(sed -n 's/.*testsuite .*time="\([0-9.]*\)".*/\1/p' "$file" | head -n1)"
  suite_name="$(sed -n 's/.*testsuite name="\([^"]*\)".*/\1/p' "$file" | head -n1)"
  if [[ -n "$time_value" && -n "$suite_name" ]]; then
    printf "%10s  %s  %s\n" "$time_value" "$suite_name" "$file" >>"$tmp_file"
  fi
done

echo "Top ${limit} slowest test suites:"
set +o pipefail
sort -nr "$tmp_file" | head -n "$limit"
set -o pipefail
