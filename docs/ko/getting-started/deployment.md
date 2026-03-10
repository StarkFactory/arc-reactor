# 배포 가이드

이 문서는 로컬 실행, Docker 단독 실행, Docker Compose 기준 배포를 다룹니다.

## 런타임 필수 전제 (현재 기본값)

Arc Reactor는 기동 preflight에서 아래 값을 필수로 검증합니다.

- `GEMINI_API_KEY` (또는 활성화한 다른 provider 키)
- `ARC_REACTOR_AUTH_JWT_SECRET` (최소 32바이트)
- PostgreSQL datasource 설정
  - `SPRING_DATASOURCE_URL` (`jdbc:postgresql://...`)
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`

> 로컬 비프로덕션 실험에서만 `ARC_REACTOR_POSTGRES_REQUIRED=false`로 PostgreSQL preflight를 끌 수 있습니다.

## Gradle로 로컬 실행

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

대안:

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

## Docker 이미지 실행

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

## Docker Compose 기준 배포 (권장)

```bash
cp .env.example .env
# GEMINI_API_KEY, ARC_REACTOR_AUTH_JWT_SECRET 수정
docker compose up -d --build
```

`docker-compose.yml` 포함 구성:

- `app`: Arc Reactor 런타임
- `db`: pgvector/PostgreSQL 16
- `redis`: Redis 7 (선택형 시멘틱 캐시/분산 토큰 폐기 저장소)
- health check + 의존 순서 기반 기동

필요 시 Redis 기반 기능 활성화:

```bash
export ARC_REACTOR_CACHE_ENABLED=true
export ARC_REACTOR_CACHE_SEMANTIC_ENABLED=true
export ARC_REACTOR_AUTH_TOKEN_REVOCATION_STORE=redis
docker compose up -d --build
```

Redis가 없어도 Arc Reactor는 기동되며 인메모리 기본값을 사용합니다.

중지:

```bash
docker compose down
```

## 필수 환경 변수

| 변수 | 필수 | 설명 |
|------|------|------|
| `GEMINI_API_KEY` | 예 (다른 provider 미사용 시) | Gemini API 키 |
| `ARC_REACTOR_AUTH_JWT_SECRET` | 예 | JWT 서명 시크릿 (최소 32바이트) |
| `SPRING_DATASOURCE_URL` | 예 | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | 예 | PostgreSQL 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | 예 | PostgreSQL 비밀번호 |
| `ARC_REACTOR_CACHE_ENABLED` | 선택 | 응답 캐시 활성화 |
| `ARC_REACTOR_CACHE_SEMANTIC_ENABLED` | 선택 | Redis 시멘틱 캐시 레이어 활성화 |
| `ARC_REACTOR_AUTH_TOKEN_REVOCATION_STORE` | 선택 | `memory` \| `jdbc` \| `redis` |
| `SPRING_AI_OPENAI_API_KEY` | 선택 | OpenAI provider 키 |
| `SPRING_AI_ANTHROPIC_API_KEY` | 선택 | Anthropic provider 키 |

## 프로덕션 체크리스트

### 보안

- [ ] 인증은 항상 활성 상태(legacy `arc.reactor.auth.enabled` 사용 금지)
- [ ] `ARC_REACTOR_AUTH_JWT_SECRET`을 시크릿 매니저로 관리
- [ ] MCP 서버 노출 범위 제한 (`arc.reactor.mcp.security.allowed-server-names`)
- [ ] CORS가 필요하면 명시적 allow-list 구성
- [ ] 무인증 probe가 필요하지 않다면 `ARC_REACTOR_AUTH_PUBLIC_ACTUATOR_HEALTH=false` 유지

### 안정성

- [ ] PostgreSQL + Flyway 활성화
- [ ] `arc.reactor.concurrency.request-timeout-ms`, `tool-call-timeout-ms` 튜닝
- [ ] 트래픽에 맞는 Guard rate limit 튜닝

### 관측성

- [ ] actuator metrics 노출 설정 확인 (`management.endpoints.web.exposure.include`)
- [ ] 필요 시 Prometheus 스크레이핑 구성
- [ ] `arc-admin` 사용 시 메트릭 파이프라인/테넌트 대시보드 검증

## 연관 문서

- [설정 Quickstart](configuration-quickstart.md)
- [설정 레퍼런스](configuration.md)
- [MCP Live 연동 런타임 런북](../operations/mcp-live-runtime-runbook.md)
- [트러블슈팅](troubleshooting.md)
