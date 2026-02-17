# Deployment Guide

## Running Locally

### 1. Environment Setup

```bash
cp .env.example .env
# Set GEMINI_API_KEY in the .env file
```

### 2. Run with Gradle

```bash
./gradlew :arc-app:bootRun
```

The API will start at `http://localhost:8080`.

## Docker Deployment

### Docker Only

```bash
# Build JAR
./gradlew :arc-app:bootJar

# Build Docker image
docker build -t arc-reactor:latest .

# Run
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-api-key \
  arc-reactor:latest
```

### Docker Compose (Recommended)

Run together with PostgreSQL:

```bash
# Set up .env file
cp .env.example .env
# Set GEMINI_API_KEY

# Build JAR and run
./gradlew :arc-app:bootJar
docker compose up -d
```

`docker-compose.yml` automatically configures the following:
- **app**: Arc Reactor (port 8080)
- **postgres**: PostgreSQL 16 (port 5432, persistent volume)
- The app starts once PostgreSQL reaches a healthy state
- `JdbcMemoryStore` is automatically activated (DataSource detected)

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GEMINI_API_KEY` | Yes | - | Google Gemini API key |
| `SPRING_AI_OPENAI_API_KEY` | - | - | OpenAI API key (when switching provider) |
| `SPRING_AI_ANTHROPIC_API_KEY` | - | - | Anthropic API key (when switching provider) |
| `DB_HOST` | - | `localhost` | PostgreSQL host |
| `DB_PORT` | - | `5432` | PostgreSQL port |
| `DB_NAME` | - | `arc_reactor` | Database name |
| `DB_USERNAME` | - | `arc` | DB username |
| `DB_PASSWORD` | - | `arc_password` | DB password |
| `SERVER_PORT` | - | `8080` | Server port |

## Production Checklist

### Security

- [ ] Add API authentication (Spring Security or custom filter)
- [ ] Configure CORS (set allowed domains in `application.yml`)
- [ ] Adjust rate limit (`arc.reactor.guard.rate-limit-per-minute`)
- [ ] Never commit `.env` files to Git

### Performance

- [ ] Adjust `max-concurrent-requests` to match server specs
- [ ] Set `request-timeout-ms` appropriately (default 30 seconds)
- [ ] Set `max-context-window-tokens` to match the LLM model

### Monitoring

- [ ] Register an `AgentMetrics` implementation (Prometheus, Datadog, etc.)
- [ ] Configure logging level (`logging.level.com.arc.reactor=INFO`)
- [ ] Add audit logging via Hooks

### Database

- [ ] Verify Flyway migrations when using PostgreSQL
- [ ] Configure session TTL (`memory.session-ttl`)
- [ ] Schedule periodic cleanup

## Changing LLM Provider

Change the dependency in `build.gradle.kts`:

```kotlin
// Google Gemini (default)
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

// OpenAI
// implementation("org.springframework.ai:spring-ai-starter-model-openai")

// Anthropic
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")

// Azure OpenAI
// implementation("org.springframework.ai:spring-ai-starter-model-azure-openai")
```

Add the corresponding provider configuration in `application.yml`.
