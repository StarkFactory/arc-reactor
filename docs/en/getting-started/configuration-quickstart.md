# Configuration Quickstart

If you fork Arc Reactor, start from a known-good local baseline and only then enable optional features.

## 1) Fast path (bootstrap script)

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

What this script does:

- Copies `examples/config/application.quickstart.yml` to `arc-core/src/main/resources/application-local.yml` (if missing)
- Validates `GEMINI_API_KEY`
- Generates a dev JWT secret when `ARC_REACTOR_AUTH_JWT_SECRET` is missing
- Starts `:arc-app:bootRun` with PostgreSQL connection environment variables

## 2) Manual path (required values)

Arc Reactor enforces runtime preflight checks. Set all required values:

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

Startup fails fast when one of these is missing or invalid.

## 3) Optional local YAML file

Start from one of these templates:

- [`application.yml.example`](../../../application.yml.example)
- [`examples/config/application.quickstart.yml`](../../../examples/config/application.quickstart.yml)
- [`examples/config/application.advanced.yml`](../../../examples/config/application.advanced.yml)

Example:

```bash
cp examples/config/application.quickstart.yml arc-core/src/main/resources/application-local.yml
```

## 4) First API smoke test

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"qa@example.com","password":"passw0rd!","name":"QA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"Say hello in one sentence."}'
```

Optional contract check (no LLM call):

```bash
./scripts/dev/validate-runtime-contract.sh --base-url http://localhost:8080
```

## 5) Enable optional features gradually

Common opt-in toggles:

- `arc.reactor.rag.enabled`
- `arc.reactor.rag.ingestion.enabled`
- `arc.reactor.approval.enabled`
- `arc.reactor.tool-policy.dynamic.enabled`
- `arc.reactor.output-guard.enabled`
- `arc.reactor.admin.enabled`

## 6) Need full control?

- Full reference: [configuration.md](configuration.md)
- Troubleshooting: [troubleshooting.md](troubleshooting.md)
- Deployment guide: [deployment.md](deployment.md)
