# Arc Reactor Helm Chart

Helm chart for deploying [Arc Reactor](https://github.com/StarkFactory/arc-reactor) on Kubernetes — a Spring AI-based AI Agent framework.

## Requirements

- Kubernetes 1.25+
- Helm 3.10+
- An external PostgreSQL instance (or enable the bundled one for dev/testing)
- At least one LLM provider API key (Gemini, OpenAI, or Anthropic)

## Quick Start

```bash
# Minimal installation with Gemini API key
helm install arc-reactor ./helm/arc-reactor \
  --set secrets.geminiApiKey=YOUR_GEMINI_KEY

# With external PostgreSQL
helm install arc-reactor ./helm/arc-reactor \
  --set secrets.geminiApiKey=YOUR_GEMINI_KEY \
  --set postgresql.host=mydb.example.com \
  --set postgresql.password=securepassword \
  --set config.flyway.enabled=true

# Production install using the bundled production values override
helm install arc-reactor ./helm/arc-reactor \
  -f helm/arc-reactor/values-production.yaml \
  --set secrets.geminiApiKey=YOUR_GEMINI_KEY \
  --set postgresql.host=mydb.example.com \
  --set postgresql.password=securepassword

# With ingress (nginx) and TLS via cert-manager
helm install arc-reactor ./helm/arc-reactor \
  --set secrets.geminiApiKey=YOUR_GEMINI_KEY \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set "ingress.hosts[0].host=arc-reactor.example.com" \
  --set "ingress.hosts[0].paths[0].path=/" \
  --set "ingress.hosts[0].paths[0].pathType=Prefix" \
  --set "ingress.tls[0].secretName=arc-reactor-tls" \
  --set "ingress.tls[0].hosts[0]=arc-reactor.example.com" \
  --set "ingress.annotations.cert-manager\.io/cluster-issuer=letsencrypt-prod"
```

## Using an External Secret

Instead of passing secrets via `--set`, reference a pre-existing Kubernetes `Secret`:

```bash
kubectl create secret generic arc-reactor-credentials \
  --from-literal=GEMINI_API_KEY=xxx \
  --from-literal=SPRING_DATASOURCE_PASSWORD=yyy

helm install arc-reactor ./helm/arc-reactor \
  --set existingSecret=arc-reactor-credentials \
  --set postgresql.host=mydb.example.com
```

The secret must contain the keys that the deployment references (e.g. `GEMINI_API_KEY`, `SPRING_DATASOURCE_PASSWORD`).

## Upgrading

```bash
helm upgrade arc-reactor ./helm/arc-reactor \
  -f helm/arc-reactor/values-production.yaml \
  --set secrets.geminiApiKey=YOUR_GEMINI_KEY \
  --set postgresql.host=mydb.example.com \
  --set postgresql.password=securepassword
```

## Uninstalling

```bash
helm uninstall arc-reactor
```

Note: The `Secret` created by this chart has `helm.sh/resource-policy: keep` set by default to prevent accidental credential deletion. Remove it manually if needed:

```bash
kubectl delete secret arc-reactor
```

## Configuration Reference

### Image

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Container image repository | `ghcr.io/starkfactory/arc-reactor` |
| `image.tag` | Image tag (defaults to Chart.appVersion) | `""` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |

### Deployment

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `1` |
| `resources.limits.cpu` | CPU limit | `2000m` |
| `resources.limits.memory` | Memory limit | `1Gi` |
| `resources.requests.cpu` | CPU request | `500m` |
| `resources.requests.memory` | Memory request | `512Mi` |
| `terminationGracePeriodSeconds` | Graceful shutdown period | `35` |

### Service

| Parameter | Description | Default |
|-----------|-------------|---------|
| `service.type` | Kubernetes service type | `ClusterIP` |
| `service.port` | Service port | `8080` |

### Ingress

| Parameter | Description | Default |
|-----------|-------------|---------|
| `ingress.enabled` | Enable ingress | `false` |
| `ingress.className` | Ingress class name | `""` |
| `ingress.annotations` | Ingress annotations | `{}` |
| `ingress.hosts` | Ingress hosts | `[]` |
| `ingress.tls` | TLS configuration | `[]` |

### Autoscaling

| Parameter | Description | Default |
|-----------|-------------|---------|
| `autoscaling.enabled` | Enable HPA | `false` |
| `autoscaling.minReplicas` | Minimum replicas | `1` |
| `autoscaling.maxReplicas` | Maximum replicas | `5` |
| `autoscaling.targetCPUUtilizationPercentage` | Target CPU utilization | `70` |

### Secrets

| Parameter | Description | Default |
|-----------|-------------|---------|
| `secrets.geminiApiKey` | Google Gemini API key | `""` |
| `secrets.openaiApiKey` | OpenAI API key | `""` |
| `secrets.anthropicApiKey` | Anthropic API key | `""` |
| `secrets.jwtSecret` | JWT signing secret (required) | `""` |
| `secrets.datasourcePassword` | Datasource password | `""` |
| `existingSecret` | Name of an existing Kubernetes Secret | `""` |

### Arc Reactor Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `config.springProfilesActive` | Active Spring profile | `prod` |
| `config.llm.defaultProvider` | Default LLM provider (`gemini`, `openai`, `anthropic`) | `gemini` |
| `config.llm.temperature` | LLM temperature | `0.7` |
| `config.llm.maxOutputTokens` | Maximum output tokens | `4096` |
| `config.concurrency.maxConcurrentRequests` | Maximum concurrent requests | `20` |
| `config.concurrency.requestTimeoutMs` | Request timeout (ms) | `30000` |
| `config.concurrency.toolCallTimeoutMs` | Tool call timeout (ms) | `15000` |
| `config.guard.enabled` | Enable request guard | `true` |
| `config.guard.rateLimitPerMinute` | Rate limit per minute | `20` |
| `config.guard.rateLimitPerHour` | Rate limit per hour | `200` |
| `config.guard.auditEnabled` | Enable guard audit trail | `true` |
| `config.agent.maxToolCalls` | Maximum tool calls per request | `10` |
| `config.agent.maxToolsPerRequest` | Maximum tools per request | `20` |
| `config.rag.enabled` | Enable RAG | `false` |
| `config.authPublicActuatorHealth` | Expose `/actuator/health*` without JWT (for probes) | `true` |
| `config.flyway.enabled` | Enable Flyway migrations | `false` |
| `config.logging.level` | Application log level | `INFO` |

### PostgreSQL

| Parameter | Description | Default |
|-----------|-------------|---------|
| `postgresql.enabled` | Deploy bundled PostgreSQL (dev only) | `false` |
| `postgresql.host` | External PostgreSQL host | `""` |
| `postgresql.port` | PostgreSQL port | `5432` |
| `postgresql.database` | Database name | `arcreactor` |
| `postgresql.username` | Database username | `arc` |
| `postgresql.password` | Database password | `""` |

## Health Probes

The chart configures liveness and readiness probes using Spring Boot Actuator:

- **Liveness**: `GET /actuator/health/liveness`
- **Readiness**: `GET /actuator/health/readiness`

## REST API

After deployment, the API is available at:

- `POST /api/chat` — Send a message to the agent
- `POST /api/chat/stream` — SSE streaming chat
- `GET /api/sessions` — List sessions
- `GET /swagger-ui.html` — Interactive API documentation
- `GET /actuator/health` — Health status
- `GET /actuator/metrics` — Metrics

## Linting the Chart

```bash
helm lint helm/arc-reactor
helm lint helm/arc-reactor -f helm/arc-reactor/values-production.yaml
```
