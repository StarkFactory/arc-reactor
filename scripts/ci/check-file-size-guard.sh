#!/usr/bin/env bash
set -euo pipefail

declare -a limits=(
  "arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt:900"
  "arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorCoreBeansConfiguration.kt:350"
  "arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentPolicyAndFeatureProperties.kt:500"
)

failed=0

for entry in "${limits[@]}"; do
  file="${entry%%:*}"
  max_lines="${entry##*:}"

  if [[ ! -f "$file" ]]; then
    echo "::error file=$file::guard target file not found"
    failed=1
    continue
  fi

  actual_lines="$(wc -l < "$file" | tr -d ' ')"
  if (( actual_lines > max_lines )); then
    echo "::error file=$file::line-count guard exceeded ($actual_lines > $max_lines)"
    failed=1
  else
    echo "OK: $file ($actual_lines/$max_lines lines)"
  fi
done

if (( failed != 0 )); then
  echo "File-size guard failed. Split responsibilities before merging."
  exit 1
fi

echo "File-size guard passed."
