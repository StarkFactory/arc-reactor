#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMPLATE_CONFIG="$ROOT_DIR/examples/config/application.quickstart.yml"
LOCAL_CONFIG="$ROOT_DIR/arc-core/src/main/resources/application-local.yml"

run_app=false
force_copy=false
api_key="${GEMINI_API_KEY:-}"
jwt_secret="${ARC_REACTOR_AUTH_JWT_SECRET:-}"
datasource_url="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/arcreactor}"
datasource_username="${SPRING_DATASOURCE_USERNAME:-arc}"
datasource_password="${SPRING_DATASOURCE_PASSWORD:-arc}"

usage() {
  cat <<'EOF'
Usage: ./scripts/dev/bootstrap-local.sh [options]

Options:
  --api-key <key>  Set GEMINI_API_KEY for this bootstrap run.
  --run            Start :arc-app:bootRun after bootstrap.
  --force          Overwrite existing application-local.yml from template.
  -h, --help       Show this help message.

Examples:
  ./scripts/dev/bootstrap-local.sh --api-key your-key
  ./scripts/dev/bootstrap-local.sh --api-key your-key --run
EOF
}

abort() {
  echo "Error: $1" >&2
  exit 1
}

mask_secret() {
  local secret="$1"
  local len="${#secret}"
  if (( len <= 6 )); then
    echo "******"
    return
  fi
  echo "${secret:0:4}****${secret:len-2:2}"
}

generate_dev_jwt_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32
    return
  fi
  echo "dev-only-jwt-secret-change-me-before-prod-123456"
}

while (($# > 0)); do
  case "$1" in
    --api-key)
      [[ $# -ge 2 ]] || abort "--api-key requires a value"
      api_key="$2"
      shift 2
      ;;
    --run)
      run_app=true
      shift
      ;;
    --force)
      force_copy=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      abort "Unknown option: $1"
      ;;
  esac
done

[[ -f "$TEMPLATE_CONFIG" ]] || abort "Missing template: $TEMPLATE_CONFIG"

if [[ ! -f "$LOCAL_CONFIG" || "$force_copy" == "true" ]]; then
  cp "$TEMPLATE_CONFIG" "$LOCAL_CONFIG"
  echo "Created local config: $LOCAL_CONFIG"
else
  echo "Local config already exists, skipped copy: $LOCAL_CONFIG"
fi

if [[ -z "$api_key" ]]; then
  cat <<'EOF'
GEMINI_API_KEY is not set.
Provide it via:
  1) --api-key <key>
  2) export GEMINI_API_KEY=<key>
EOF
  exit 1
fi

echo "GEMINI_API_KEY detected: $(mask_secret "$api_key")"
if [[ -z "$jwt_secret" ]]; then
  jwt_secret="$(generate_dev_jwt_secret)"
  echo "ARC_REACTOR_AUTH_JWT_SECRET not set. Generated dev secret: $(mask_secret "$jwt_secret")"
else
  echo "ARC_REACTOR_AUTH_JWT_SECRET detected: $(mask_secret "$jwt_secret")"
fi

echo "PostgreSQL DSN: $datasource_url"
echo "PostgreSQL user: $datasource_username"

if [[ "$run_app" == "true" ]]; then
  echo "Starting application (:arc-app:bootRun)..."
  cd "$ROOT_DIR"
  GEMINI_API_KEY="$api_key" \
  ARC_REACTOR_AUTH_JWT_SECRET="$jwt_secret" \
  SPRING_DATASOURCE_URL="$datasource_url" \
  SPRING_DATASOURCE_USERNAME="$datasource_username" \
  SPRING_DATASOURCE_PASSWORD="$datasource_password" \
  ./gradlew :arc-app:bootRun
  exit 0
fi

cat <<'EOF'
Bootstrap completed.
Next step:
  export GEMINI_API_KEY=<your-key>
  export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
  export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
  export SPRING_DATASOURCE_USERNAME=arc
  export SPRING_DATASOURCE_PASSWORD=arc
  ./gradlew :arc-app:bootRun
EOF
