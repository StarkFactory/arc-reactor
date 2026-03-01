#!/usr/bin/env bash
set -euo pipefail

# Fail fast when an already-versioned Flyway migration is modified, deleted, or renamed.
# Policy: existing V*.sql files are immutable; add a new versioned migration instead.

readonly MIGRATION_PATHSPEC=':(glob)arc-core/src/main/resources/db/migration/V*.sql'

resolve_diff_range() {
  if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
    git fetch --no-tags --depth=200 origin \
      "${GITHUB_BASE_REF}:refs/remotes/origin/${GITHUB_BASE_REF}" >/dev/null 2>&1 || true

    if git rev-parse --verify -q "origin/${GITHUB_BASE_REF}" >/dev/null; then
      local merge_base
      merge_base="$(git merge-base HEAD "origin/${GITHUB_BASE_REF}" || true)"
      if [[ -n "${merge_base}" ]]; then
        echo "${merge_base}..HEAD"
        return 0
      fi
    fi
  fi

  if git rev-parse --verify -q HEAD~1 >/dev/null; then
    echo "HEAD~1..HEAD"
    return 0
  fi

  echo ""
}

diff_range="$(resolve_diff_range)"
if [[ -z "${diff_range}" ]]; then
  echo "Flyway immutability guard: no comparable commit range; skipping."
  exit 0
fi

# Use Git's glob pathspec instead of shell expansion so deleted/renamed files are detected too.
changes="$(git diff --name-status --diff-filter=ACDMR "${diff_range}" -- "${MIGRATION_PATHSPEC}" || true)"

if [[ -z "${changes}" ]]; then
  echo "Flyway immutability guard passed: no versioned migration changes."
  exit 0
fi

failed=0

while IFS= read -r line; do
  [[ -z "${line}" ]] && continue
  status="$(awk '{print $1}' <<< "${line}")"

  if [[ "${status}" == A* ]]; then
    echo "OK (new migration): ${line}"
    continue
  fi

  if [[ "${status}" == M* ]]; then
    file_path="$(awk '{print $2}' <<< "${line}")"
    echo "::error file=${file_path}::Versioned Flyway migration was modified. " \
      "Do not edit existing V*.sql files; add a new migration version."
    failed=1
    continue
  fi

  if [[ "${status}" == D* ]]; then
    file_path="$(awk '{print $2}' <<< "${line}")"
    echo "::error file=${file_path}::Versioned Flyway migration was deleted. " \
      "Existing migrations are immutable."
    failed=1
    continue
  fi

  if [[ "${status}" == R* ]]; then
    old_path="$(awk '{print $2}' <<< "${line}")"
    new_path="$(awk '{print $3}' <<< "${line}")"
    echo "::error file=${old_path}::Versioned Flyway migration was renamed (${old_path} -> ${new_path}). " \
      "Existing migrations are immutable."
    failed=1
    continue
  fi

  echo "::error::Unsupported Flyway migration change detected: ${line}"
  failed=1
done <<< "${changes}"

if (( failed != 0 )); then
  echo "Flyway immutability guard failed."
  echo "Resolution:"
  echo "1) Revert changes to existing versioned migrations."
  echo "2) Add a new V<next>__*.sql migration for schema changes."
  echo "3) If production already has a checksum mismatch, follow the migration runbook and " \
    "execute flyway repair only with explicit change approval."
  exit 1
fi

echo "Flyway immutability guard passed."
