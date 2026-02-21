#!/usr/bin/env bash
set -euo pipefail

bash scripts/ci/check-agent-doc-sync.sh
python3 scripts/ci/check-doc-links.py
python3 scripts/ci/check-default-config-alignment.py

echo "All docs checks passed."
