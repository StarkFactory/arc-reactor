# Deployment Guide

This guide covers local runtime, Docker image execution, and Docker Compose baseline deployment.

## Runtime prerequisites (current defaults)

Arc Reactor startup preflight requires:

- `GEMINI_API_KEY` (or another enabled provider key)
- `ARC_REACTOR_AUTH_JWT_SECRET` (minimum 32 bytes)
- PostgreSQL datasource settings
  - `SPRING_DATASOURCE_URL` (`jdbc:postgresql://...`)
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`

> For local non-production experiments only, you can disable the PostgreSQL preflight check with
> `ARC_REACTOR_POSTGRES_REQUIRED=false`.

## Run locally with Gradle

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

Alternative:

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

## Docker image

```bash
./gradlew :arc-app:bootJar
docker build -t arc-reactor:latest .
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-api-key \
  -e ARC_REACTOR_AUTH_JWT_SECRET=replace-with-32-byte-secret \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/arcreactor \
  -e SPRING_DATASOURCE_USERNAME=arc \
  -e SPRING_DATASOURCE_PASSWORD=arc \
  arc-reactor:latest
```

## Docker Compose baseline (recommended)

```bash
cp .env.example .env
# Edit GEMINI_API_KEY and ARC_REACTOR_AUTH_JWT_SECRET
docker compose up -d --build
```

`docker-compose.yml` includes:

- `app`: Arc Reactor runtime
- `db`: pgvector/PostgreSQL 16
- health checks and startup dependency ordering

Stop stack:

```bash
docker compose down
```

## Required environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GEMINI_API_KEY` | Yes (unless using another provider) | Gemini API key |
| `ARC_REACTOR_AUTH_JWT_SECRET` | Yes | JWT signing secret, minimum 32 bytes |
| `SPRING_DATASOURCE_URL` | Yes | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | Yes | PostgreSQL password |
| `SPRING_AI_OPENAI_API_KEY` | Optional | OpenAI provider key |
| `SPRING_AI_ANTHROPIC_API_KEY` | Optional | Anthropic provider key |

## Production checklist

### Security

- [ ] Keep authentication enabled (always-on); do not use legacy `arc.reactor.auth.enabled`
- [ ] Set a strong `ARC_REACTOR_AUTH_JWT_SECRET` via secret manager
- [ ] Restrict MCP server exposure (`arc.reactor.mcp.security.allowed-server-names`)
- [ ] Configure CORS allow-list when cross-origin access is needed
- [ ] Keep `ARC_REACTOR_AUTH_PUBLIC_ACTUATOR_HEALTH=false` unless probes are intentionally unauthenticated

### Reliability

- [ ] Use PostgreSQL with Flyway enabled
- [ ] Tune `arc.reactor.concurrency.request-timeout-ms` and `tool-call-timeout-ms`
- [ ] Set rate limits appropriate to your traffic profile

### Observability

- [ ] Expose actuator metrics (`management.endpoints.web.exposure.include`)
- [ ] Enable Prometheus scraping if needed
- [ ] If using `arc-admin`, validate metric pipeline and tenant dashboards

## Related docs

- [Configuration quickstart](configuration-quickstart.md)
- [Configuration reference](configuration.md)
- [Kubernetes reference](kubernetes-reference.md)
- [Troubleshooting](troubleshooting.md)
