# 배포 가이드

## 로컬 실행

### 1. 환경 설정

```bash
cp .env.example .env
# .env 파일에서 GEMINI_API_KEY 설정
```

### 2. Gradle로 실행

```bash
./gradlew :arc-app:bootRun
```

API가 `http://localhost:8080`에서 시작됩니다.

## Docker 배포

### Docker만 사용

```bash
# JAR 빌드
./gradlew bootJar

# Docker 이미지 빌드
docker build -t arc-reactor:latest .

# 실행
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-api-key \
  arc-reactor:latest
```

### Docker Compose (추천)

PostgreSQL과 함께 실행합니다:

```bash
# .env 파일 설정
cp .env.example .env
# GEMINI_API_KEY 설정

# JAR 빌드 후 실행
./gradlew bootJar
docker compose up -d
```

`docker-compose.yml`이 다음을 자동 설정합니다:
- **app**: Arc Reactor (포트 8080)
- **postgres**: PostgreSQL 16 (포트 5432, 볼륨 영속화)
- PostgreSQL이 healthy 상태가 되면 app이 시작됨
- `JdbcMemoryStore`가 자동으로 활성화됨 (DataSource 감지)

## 환경 변수

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `GEMINI_API_KEY` | O | - | Google Gemini API 키 |
| `OPENAI_API_KEY` | - | - | OpenAI API 키 (provider 변경 시) |
| `ANTHROPIC_API_KEY` | - | - | Anthropic API 키 (provider 변경 시) |
| `DB_HOST` | - | `localhost` | PostgreSQL 호스트 |
| `DB_PORT` | - | `5432` | PostgreSQL 포트 |
| `DB_NAME` | - | `arc_reactor` | 데이터베이스 이름 |
| `DB_USERNAME` | - | `arc` | DB 사용자 |
| `DB_PASSWORD` | - | `arc_password` | DB 비밀번호 |
| `SERVER_PORT` | - | `8080` | 서버 포트 |

## 프로덕션 체크리스트

### 보안

- [ ] API 인증 추가 (Spring Security 또는 커스텀 필터)
- [ ] CORS 설정 (`application.yml`에서 허용 도메인 설정)
- [ ] Rate Limit 조정 (`arc.reactor.guard.rate-limit-per-minute`)
- [ ] `.env` 파일을 절대 Git에 커밋하지 않기

### 성능

- [ ] `max-concurrent-requests` 서버 스펙에 맞게 조정
- [ ] `request-timeout-ms` 적절히 설정 (기본 30초)
- [ ] `max-context-window-tokens` LLM 모델에 맞게 설정

### 모니터링

- [ ] `AgentMetrics` 구현체 등록 (Prometheus, Datadog 등)
- [ ] 로깅 레벨 설정 (`logging.level.com.arc.reactor=INFO`)
- [ ] Hook으로 감사 로그 추가

### 데이터베이스

- [ ] PostgreSQL 사용 시 Flyway 마이그레이션 확인
- [ ] 세션 TTL 설정 (`memory.session-ttl`)
- [ ] 정기 cleanup 스케줄링

## LLM Provider 변경

`build.gradle.kts`에서 의존성 변경:

```kotlin
// Google Gemini (기본)
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

// OpenAI
// implementation("org.springframework.ai:spring-ai-starter-model-openai")

// Anthropic
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")

// Azure OpenAI
// implementation("org.springframework.ai:spring-ai-starter-model-azure-openai")
```

`application.yml`에서 해당 provider 설정 추가.
