#!/usr/bin/env bash
set -euo pipefail

left="AGENTS.md"
right="CLAUDE.md"

if [[ ! -f "$left" ]]; then
  echo "::error file=$left::missing $left"
  exit 1
fi

if [[ ! -f "$right" ]]; then
  echo "::error file=$right::missing $right"
  exit 1
fi

if ! cmp -s "$left" "$right"; then
  echo "::error file=$left::$left and $right are out of sync"
  diff -u "$left" "$right" || true
  exit 1
fi

echo "Agent instruction sync check passed ($left == $right)."
