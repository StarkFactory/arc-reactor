# Start All: Arc Reactor + MCP Servers 전체 기동 및 연결

Arc Reactor 서버와 MCP 서버(Swagger, Atlassian)를 기동하고 등록한다.

## 환경 파일 선택

| 파일 | 용도 | 비고 |
|------|------|------|
| `.env` | 개발 환경 (기본) | 로컬 토큰, 테스트용 |
| `.env.prod` | 프로덕션 환경 | ihunet Atlassian, Slack Reactor 앱 |
| `.env.local` | 개인 오버라이드 | 있으면 .env 위에 덮어씀 |

**환경 파일 로드 방법:**
```bash
# 방법 1: 수동 source
source /Users/stark/ai/arc-reactor/.env.prod

# 방법 2: set -a로 export까지 한번에
set -a && source /Users/stark/ai/arc-reactor/.env.prod && set +a
```

## Phase 1: 사전 점검

```bash
# 포트 충돌 확인 (8080, 8081, 8085)
for port in 8080 8081 8085; do
  lsof -i :$port -t 2>/dev/null && echo "⚠ Port $port already in use" || echo "✓ Port $port available"
done
```

환경변수 확인 (최소 `GEMINI_API_KEY` 필수):
```bash
[ -n "$GEMINI_API_KEY" ] && echo "✓ GEMINI_API_KEY set" || echo "✗ GEMINI_API_KEY missing"
[ -n "$ATLASSIAN_API_TOKEN" ] && echo "✓ ATLASSIAN_API_TOKEN set" || echo "✗ ATLASSIAN_API_TOKEN missing"
[ -n "$SLACK_BOT_TOKEN" ] && echo "✓ SLACK_BOT_TOKEN set" || echo "✗ SLACK_BOT_TOKEN missing"
```

## Phase 2: DB 기동 (Docker — DB/Redis만)

**중요: DB와 Redis만 Docker로 실행한다. 앱 자체는 Docker로 실행하지 않는다.**

```bash
cd /Users/stark/ai/arc-reactor
docker compose up -d db redis
```

DB healthy 확인:
```bash
docker compose ps  # db, redis 모두 healthy 확인
psql -h localhost -U arc -d arcreactor -c "SELECT 1;" 2>/dev/null && echo "✓ DB connected" || echo "✗ DB not ready"
```

**DB 초기 세팅 (최초 1회 또는 스키마 불일치 시):**
```bash
# pgvector 확장 설치 (Flyway 전에 필요)
psql -h localhost -U arc -d arcreactor -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

**DB 스키마 리셋 (Flyway checksum 불일치 시):**
```bash
psql -h localhost -U arc -d postgres -c "DROP DATABASE arcreactor;"
psql -h localhost -U arc -d postgres -c "CREATE DATABASE arcreactor OWNER arc;"
psql -h localhost -U arc -d arcreactor -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

## Phase 3: MCP 서버 기동

순서: 의존성 없는 MCP 서버들을 먼저 기동한 뒤 Arc Reactor를 마지막에 올린다.

### 3-1. Swagger MCP Server (포트 8081)

```bash
cd /Users/stark/ai/swagger-mcp-server
SPRING_DATASOURCE_URL="jdbc:h2:file:./data/swagger-mcp/catalog;MODE=PostgreSQL;AUTO_SERVER=TRUE" \
SPRING_DATASOURCE_USERNAME=sa SPRING_DATASOURCE_PASSWORD="" \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver \
SPRING_FLYWAY_ENABLED=false \
./gradlew bootRun &
```

기동 확인:
```bash
curl -sf http://localhost:8081/actuator/health || echo "swagger not ready"
```

### 3-2. Atlassian MCP Server (포트 8085)

필수 환경변수 (`.env.prod`에서 source):
- `ATLASSIAN_BASE_URL` — Atlassian 사이트 URL
- `ATLASSIAN_CLOUD_ID` — Cloud ID
- `ATLASSIAN_USERNAME` — 토큰 발급 계정 이메일
- `ATLASSIAN_API_TOKEN` / `JIRA_API_TOKEN` / `CONFLUENCE_API_TOKEN`
- `JIRA_USE_API_GATEWAY=true` / `CONFLUENCE_USE_API_GATEWAY=true` (granular 토큰 시 필수)

```bash
cd /Users/stark/ai/atlassian-mcp-server
SPRING_DATASOURCE_URL="" SPRING_FLYWAY_ENABLED=false \
./gradlew bootRun &
```

기동 확인 (관리 포트 8086):
```bash
curl -sf http://localhost:8086/actuator/health || echo "atlassian not ready"
```

### 3-3. Arc Reactor (포트 8080)

```bash
cd /Users/stark/ai/arc-reactor
ARC_REACTOR_ADMIN_ENABLED=true \
ARC_REACTOR_ADMIN_TIMESCALE_ENABLED=true \
ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES=true \
./gradlew bootRun &
```

기동 확인:
```bash
curl -sf http://localhost:8080/actuator/health || echo "arc-reactor not ready"
```

## Phase 4: MCP 서버 등록

모든 서버가 healthy 상태인지 확인 후, Arc Reactor에 MCP 서버를 등록한다:

```bash
# Swagger MCP (port 8081)
curl -s -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"swagger-mcp-server","transportType":"SSE","config":{"url":"http://localhost:8081/sse"}}'

# Atlassian MCP (port 8085)
curl -s -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"atlassian-mcp-server","transportType":"SSE","config":{"url":"http://localhost:8085/sse"}}'
```

## Phase 5: 연결 검증

```bash
# 등록된 MCP 서버 목록 확인
curl -s http://localhost:8080/api/mcp/servers | python3 -m json.tool

# 각 서버의 도구 목록 확인
curl -s http://localhost:8080/api/mcp/servers/swagger-mcp-server/tools | python3 -m json.tool
curl -s http://localhost:8080/api/mcp/servers/atlassian-mcp-server/tools | python3 -m json.tool
```

## Phase 6: 완료 보고

각 서버 상태를 테이블로 보고:

| Server | Port | Status | Tools |
|--------|------|--------|-------|
| arc-reactor | 8080 | ? | - |
| swagger-mcp | 8081 | ? | N개 |
| atlassian-mcp | 8085 | ? | N개 |

## Shutdown

모든 서버를 종료할 때:

```bash
# Gradle 데몬으로 실행된 Spring Boot 프로세스 종료
for port in 8080 8081 8085; do
  pid=$(lsof -i :$port -t 2>/dev/null)
  [ -n "$pid" ] && kill $pid && echo "Killed port $port (PID $pid)"
done

# Docker 서비스 종료
cd /Users/stark/ai/arc-reactor && docker compose down
```

## 핵심 규칙: Docker는 DB/Redis만

> **Arc Reactor 앱을 `docker compose up app`으로 실행하면 안 된다.**

이유:
1. **MCP 연결 실패**: Docker 컨테이너 안에서 `localhost:8081`, `localhost:8085`는 컨테이너 자신을 가리킨다. 네이티브로 띄운 MCP 서버에 접근 불가.
2. **Slack Socket Mode 실패**: Docker 네트워크 내부에서 Slack WebSocket 연결이 불안정하거나 타임아웃 발생.
3. **환경변수 누락**: docker-compose.yml에 정의된 환경변수만 전달되므로, `.env.prod`의 신규 변수(deny list, gateway 등)가 빠질 수 있다.
4. **핫 리로드 불가**: 코드 수정 시 매번 Docker 이미지 재빌드 필요.

**올바른 구성:**
```
Docker: db(PostgreSQL+pgvector) + redis
네이티브: arc-reactor + atlassian-mcp-server + swagger-mcp-server
```

**Docker로 전체를 올리고 싶다면** (CI/프로덕션):
- docker-compose.yml에 MCP 서버 서비스를 추가하고 Docker 네트워크 내부 주소를 사용해야 한다.
- 예: `http://atlassian-mcp:8085/sse` (서비스명으로 접근)
- 현재 docker-compose.yml에는 MCP 서버가 포함되어 있지 않으므로 별도 구성 필요.

## Gotcha (실제 기동 시 발견된 이슈들)

1. **Swagger MCP + PostgreSQL 환경변수 충돌**: `SPRING_DATASOURCE_URL`이 PostgreSQL로 설정되어 있으면 Swagger MCP(H2 사용)가 실패한다. 기동 시 반드시 H2로 오버라이드.

2. **Atlassian MCP 관리 포트**: management.server.port가 8086으로 설정됨. health check는 8086 포트로.

3. **Granular 토큰 판별**: 토큰이 `ATATT3xFfGF0`으로 시작하면 granular → `*_USE_API_GATEWAY=true` 필수. 없으면 401 에러.

4. **SSRF 보호**: localhost MCP 등록 시 `ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES=true` 필요.

5. **다른 PC에서 실행 시 체크리스트:**
   - `.env.prod` 파일이 있는지 확인 (git에 포함 안 됨, 수동 복사 필요)
   - Docker Desktop이 실행 중인지 확인
   - `docker compose up -d db redis`로 DB 먼저 기동
   - DB extension: `CREATE EXTENSION IF NOT EXISTS vector;` 실행
   - 3개 레포 모두 clone: `arc-reactor`, `atlassian-mcp-server`, `swagger-mcp-server`
   - JDK 21 설치 확인: `java -version`

6. **환경 파일 위치**: `.env.prod`는 arc-reactor 루트에만 존재. atlassian-mcp-server와 swagger-mcp-server는 별도 .env 없이 `source`로 환경변수를 로드한 셸에서 실행.
