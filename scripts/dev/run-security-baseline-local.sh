#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

GITLEAKS_VERSION="${GITLEAKS_VERSION:-8.24.2}"
TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.69.1}"
OUTPUT_DIR="${OUTPUT_DIR:-artifacts/security-baseline/$(date +%Y%m%d-%H%M%S)}"
REDACT="${REDACT:-true}"
TRIVY_CACHE_DIR="${TRIVY_CACHE_DIR:-$ROOT_DIR/.cache/trivy}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/run-security-baseline-local.sh [options]

Run local security baseline equivalent (gitleaks + trivy fs) and persist artifacts.

Options:
  --output-dir <path>     Output directory for reports
  --no-redact             Disable gitleaks --redact
  --gitleaks-version <v>  Gitleaks version (default: 8.24.2)
  --trivy-image <image>   Trivy image for docker fallback (default: aquasec/trivy:0.69.1)
  -h, --help              Show help

Artifacts:
  - gitleaks.sarif
  - trivy-fs.json
  - summary.md
EOF
}

while (($# > 0)); do
  case "$1" in
    --output-dir)
      [[ $# -ge 2 ]] || { echo "Error: --output-dir requires a value" >&2; exit 1; }
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --no-redact)
      REDACT="false"
      shift
      ;;
    --gitleaks-version)
      [[ $# -ge 2 ]] || { echo "Error: --gitleaks-version requires a value" >&2; exit 1; }
      GITLEAKS_VERSION="$2"
      shift 2
      ;;
    --trivy-image)
      [[ $# -ge 2 ]] || { echo "Error: --trivy-image requires a value" >&2; exit 1; }
      TRIVY_IMAGE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

mkdir -p "$OUTPUT_DIR"
mkdir -p "$TRIVY_CACHE_DIR"

trivy_db_args=()
if [[ -f "$TRIVY_CACHE_DIR/db/metadata.json" ]]; then
  trivy_db_args+=(--skip-db-update)
fi

detect_gitleaks_archive_suffix() {
  local os
  local arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"

  case "$arch" in
    x86_64|amd64) arch="x64" ;;
    arm64|aarch64) arch="arm64" ;;
    *)
      echo "Error: unsupported architecture for automatic gitleaks install: $arch" >&2
      exit 1
      ;;
  esac

  case "$os" in
    darwin) echo "darwin_${arch}" ;;
    linux) echo "linux_${arch}" ;;
    *)
      echo "Error: unsupported OS for automatic gitleaks install: $os" >&2
      exit 1
      ;;
  esac
}

gitleaks_cmd="gitleaks"
if ! command -v gitleaks >/dev/null 2>&1; then
  tools_dir="$ROOT_DIR/.tools/gitleaks-${GITLEAKS_VERSION}"
  archive_suffix="$(detect_gitleaks_archive_suffix)"
  mkdir -p "$tools_dir"
  if [[ ! -x "$tools_dir/gitleaks" ]]; then
    echo "Installing gitleaks v${GITLEAKS_VERSION} locally..."
    curl -sSL \
      "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_${archive_suffix}.tar.gz" \
      -o "$tools_dir/gitleaks.tar.gz"
    tar -xzf "$tools_dir/gitleaks.tar.gz" -C "$tools_dir" gitleaks
    chmod +x "$tools_dir/gitleaks"
  fi
  gitleaks_cmd="$tools_dir/gitleaks"
fi

gitleaks_report="$OUTPUT_DIR/gitleaks.sarif"
trivy_report="$OUTPUT_DIR/trivy-fs.json"
summary_file="$OUTPUT_DIR/summary.md"

gitleaks_exit=0
trivy_exit=0
gitleaks_findings=0
trivy_findings=0

echo "[1/2] Gitleaks secret scan"
gitleaks_args=(git --baseline-path .gitleaks-baseline.json --exit-code 1 --report-format sarif --report-path "$gitleaks_report")
if [[ "$REDACT" == "true" ]]; then
  gitleaks_args=(git --redact --baseline-path .gitleaks-baseline.json --exit-code 1 --report-format sarif --report-path "$gitleaks_report")
fi
"$gitleaks_cmd" "${gitleaks_args[@]}" || gitleaks_exit=$?
if command -v jq >/dev/null 2>&1; then
  gitleaks_findings="$(jq -r '[.runs[]?.results[]?] | length' "$gitleaks_report" 2>/dev/null || echo 0)"
fi

echo "[2/2] Trivy filesystem scan"
if command -v trivy >/dev/null 2>&1; then
  trivy fs \
    --cache-dir "$TRIVY_CACHE_DIR" \
    "${trivy_db_args[@]}" \
    --severity CRITICAL,HIGH \
    --ignore-unfixed \
    --exit-code 1 \
    --format json \
    --output "$trivy_report" \
    . || trivy_exit=$?
else
  if ! command -v docker >/dev/null 2>&1; then
    echo "Error: neither trivy nor docker is available" >&2
    trivy_exit=127
  else
    docker run --rm \
      -v "${PWD}:/workspace" \
      -v "${TRIVY_CACHE_DIR}:/trivy-cache" \
      -w /workspace \
      "$TRIVY_IMAGE" fs \
      --cache-dir /trivy-cache \
      "${trivy_db_args[@]}" \
      --severity CRITICAL,HIGH \
      --ignore-unfixed \
      --exit-code 1 \
      --format json \
      --output "/workspace/$trivy_report" \
      . || trivy_exit=$?
  fi
fi
if command -v jq >/dev/null 2>&1; then
  trivy_findings="$(jq -r '[.Results[]?.Vulnerabilities[]?] | length' "$trivy_report" 2>/dev/null || echo 0)"
fi

cat >"$summary_file" <<EOF
# Security Baseline Summary

- generatedAt: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
- workspace: $ROOT_DIR
- outputDir: $OUTPUT_DIR
- gitleaksExitCode: $gitleaks_exit
- trivyExitCode: $trivy_exit
- gitleaksFindings: $gitleaks_findings
- trivyFindings: $trivy_findings

## Reports

- gitleaks: $gitleaks_report
- trivy: $trivy_report
EOF

echo "Security baseline reports generated:"
echo "  - $gitleaks_report"
echo "  - $trivy_report"
echo "  - $summary_file"

if [[ "$gitleaks_exit" -ne 0 || "$trivy_exit" -ne 0 ]]; then
  echo "Security baseline failed (gitleaks=$gitleaks_exit, trivy=$trivy_exit)" >&2
  exit 1
fi

echo "Security baseline passed."
