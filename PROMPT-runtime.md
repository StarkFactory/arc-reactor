# Arc Reactor — Runtime Integration Testing Loop

전체 에코시스템(arc-reactor + MCP 서버 + admin UI)을 실제 기동하고, 모든 기능을 검증하며, 발견한 문제를 수정하는 자율 루프.

## 경로 설정

`ECOSYSTEM.md`에서 프로젝트 경로를 읽는다. 없으면 아래 기본값 사용.
**루프 시작 시 반드시 경로 변수를 설정한 뒤 이후 모든 커맨드에서 사용한다.**

```bash
# ECOSYSTEM.md가 있으면 거기서 읽고, 없으면 자동 탐지
ARC_HOME="$(pwd)"                                              # arc-reactor (현재 디렉토리)
SWAGGER_HOME="$(dirname "$ARC_HOME")/swagger-mcp-server"       # 형제 디렉토리
ATLASSIAN_HOME="$(dirname "$ARC_HOME")/atlassian-mcp-server"
ADMIN_HOME="$(dirname "$ARC_HOME")/arc-reactor-admin"

# 경로 존재 확인 — 하나라도 없으면 ECOSYSTEM.md 참조하거나 사용자에게 물어봐
for d in "$ARC_HOME" "$SWAGGER_HOME" "$ATLASSIAN_HOME" "$ADMIN_HOME"; do
  [ -d "$d" ] || echo "WARNING: $d not found"
done
```

## 참조 문서

- `ECOSYSTEM.md` — 프로젝트 경로, 포트, 환경변수, 부팅 순서 (로컬 전용, gitignore됨)
- `CLAUDE.md` — 코드 규약, Gotchas, 테스트 방법
- `KNOWN_ACCEPTABLE.md` — 이미 검토/기각된 정적 분석 항목

---

## 0단계: 상태 확인

`runtime-progress.txt` (마지막 20줄), `git log --oneline -5` 읽기.
이전 반복에서 이미 서비스가 실행 중이면 0~1단계 스킵하고 2단계부터.

---

## 1단계: 인프라 기동

### 1-1. Docker 확인

```bash
# PGVector 확인 (55432 포트)
docker ps | grep arc-reactor-smoke-postgres || \
  docker run -d --name arc-reactor-runtime-pg \
    -e POSTGRES_DB=arcreactor -e POSTGRES_USER=arc -e POSTGRES_PASSWORD=arc \
    -p 55432:5432 pgvector/pgvector:pg16

# Redis 확인 (6379 포트)
docker ps | grep redis || \
  docker run -d --name arc-reactor-runtime-redis \
    -p 6379:6379 redis:7-alpine redis-server --appendonly yes
```

두 컨테이너 모두 healthy 확인 후 진행.

### 1-2. 서비스 빌드

**순서대로**, `--no-daemon` 필수, 동시 Gradle 빌드 금지:

```bash
cd "$ARC_HOME"       && ./gradlew build -x test --no-daemon -Pdb=true
cd "$SWAGGER_HOME"   && ./gradlew build -x test --no-daemon
cd "$ATLASSIAN_HOME" && ./gradlew build -x test --no-daemon
cd "$ADMIN_HOME"     && pnpm install
```

### 1-3. 서비스 기동

**Bash `run_in_background`**로 각 서비스 실행. 로그 파일 기록.

| # | 서비스 | 실행 | 포트 |
|---|--------|------|------|
| 1 | arc-reactor | `cd "$ARC_HOME" && SERVER_PORT=18081 SPRING_PROFILES_ACTIVE=dev SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/arcreactor SPRING_DATASOURCE_USERNAME=arc SPRING_DATASOURCE_PASSWORD=arc SPRING_FLYWAY_ENABLED=true SPRING_AI_VECTORSTORE_PGVECTOR_INITIALIZE_SCHEMA=true ARC_REACTOR_RAG_ENABLED=true ARC_REACTOR_RAG_CHUNKING_ENABLED=true ARC_REACTOR_RAG_INGESTION_ENABLED=true SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 ARC_REACTOR_SLACK_ENABLED=false ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES=true ./gradlew bootRun --no-daemon -Pdb=true > /tmp/arc-reactor.log 2>&1` | 18081 |
| 2 | swagger-mcp | `cd "$SWAGGER_HOME" && ./gradlew bootRun --no-daemon > /tmp/swagger-mcp.log 2>&1` | 8081 |
| 3 | atlassian-mcp | `cd "$ATLASSIAN_HOME" && ./gradlew bootRun --no-daemon > /tmp/atlassian-mcp.log 2>&1` | 8085 |
| 4 | admin-ui | `cd "$ADMIN_HOME" && VITE_PROXY_TARGET=http://localhost:18081 pnpm dev > /tmp/admin-ui.log 2>&1` | 3001 |

### 1-4. 헬스 체크

최대 120초 대기. 10초 간격으로 폴링:

```bash
for url in http://localhost:18081/actuator/health http://localhost:8081/actuator/health http://localhost:8085/actuator/health; do
  for i in $(seq 1 12); do
    curl -sf "$url" && break || sleep 10
  done
done
curl -sf http://localhost:3001 > /dev/null
```

4개 모두 응답하면 다음 단계. 하나라도 실패하면 로그 확인 → 문제 진단 → 수정 → 재시도.

### 1-5. MCP 서버 등록 + 연결

```bash
# JWT 획득 (admin 계정)
TOKEN=$(curl -sf -X POST http://localhost:18081/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ARC_REACTOR_AUTH_ADMIN_EMAIL\",\"password\":\"$ARC_REACTOR_AUTH_ADMIN_PASSWORD\"}" | jq -r '.token')

# swagger-mcp 등록
curl -sf -X POST http://localhost:18081/api/mcp/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"swagger","transportType":"SSE","config":{"url":"http://localhost:8081/sse"}}'

# atlassian-mcp 등록
curl -sf -X POST http://localhost:18081/api/mcp/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"atlassian","transportType":"SSE","config":{"url":"http://localhost:8085/sse"}}'

# 연결 확인
curl -sf http://localhost:18081/api/mcp/servers -H "Authorization: Bearer $TOKEN" | jq '.[] | {name, connected}'
```

---

## 2단계: 기능 검증 (회전 렌즈)

각 반복에서 **렌즈 1개만** 사용. `runtime-progress.txt` 마지막 렌즈 확인 → 다음 번호 (10 → 1 순환).

**JWT 토큰**: 매 반복 시작 시 로그인으로 갱신. `$TOKEN` 변수로 사용.

| # | 렌즈 | 검증 대상 |
|---|------|----------|
| 1 | **Auth** | 회원가입, 로그인, JWT 발급, /me, 비밀번호 변경, 로그아웃, 만료 토큰 거부, 권한 없는 접근 403 |
| 2 | **Chat (basic)** | POST /api/chat (동기), 스트리밍 SSE, 대화 이어가기 (sessionId), 입력 길이 제한, 빈 메시지 거부 |
| 3 | **MCP + Tool Call** | MCP 서버 목록 조회, 도구 목록, 채팅에서 MCP 도구 호출 유도 ("Jira에서 프로젝트 목록 조회해줘"), 도구 결과 확인 |
| 4 | **RAG + Vector** | 문서 업로드 (POST /api/documents), 유사도 검색, 채팅에서 RAG 컨텍스트 반영 확인, 문서 삭제 |
| 5 | **Admin CRUD** | 페르소나 CRUD, 인텐트 CRUD, 프롬프트 템플릿 CRUD + 버전 관리, 출력 가드 규칙 CRUD |
| 6 | **Scheduler** | 스케줄러 작업 생성, 조회, dry-run, 즉시 실행, 삭제 |
| 7 | **Session & Memory** | 세션 목록, 세션 메시지 조회, 세션 내보내기, 사용자 메모리 조회/수정/삭제 |
| 8 | **Approval Flow** | 도구 정책 설정 (write 도구 승인 필수), 채팅으로 write 도구 유도, pending 목록, 승인/거절 |
| 9 | **Admin UI (E2E)** | Playwright로 admin 테스트: 로그인, 대시보드, MCP 서버 관리, 설정 페이지 |
| 10 | **Error & Edge** | 잘못된 JSON, 초대형 입력, 존재하지 않는 ID, 동시 요청 10개, 비인증 접근, rate limit 확인 |

### 검증 방법

각 렌즈별 **curl 요청 시나리오** 작성 → 실행 → 응답 검증:

```bash
# 예시: Chat 검증
RESPONSE=$(curl -sf -X POST http://localhost:18081/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕하세요, 간단한 인사에 답해주세요","sessionId":"test-session-1"}')
echo "$RESPONSE" | jq .

# 기대: 200 OK, response 필드에 텍스트 존재, toolCalls 배열(비어도 OK)
```

렌즈 9 (Admin UI)는 Playwright 사용:

```bash
cd "$ADMIN_HOME" && pnpm test:e2e 2>&1 | tail -30
```

### 발견 기록

이슈 발견 시 `RUNTIME_ISSUES.md`에 기록:

```markdown
- [ ] [P1] `arc-reactor` ChatController — 스트리밍 응답에서 마지막 청크 누락
- [ ] [P2] `atlassian-mcp-server` — Jira 검색 시 빈 JQL에 500 반환 (400이어야 함)
- [ ] [P3] `arc-reactor-admin` — 로그인 실패 시 에러 메시지 미표시
```

---

## 3단계: 수정

### 수정 범위

4개 프로젝트 모두 수정 가능:

| 프로젝트 | 경로 | 테스트 |
|---------|------|--------|
| arc-reactor | `$ARC_HOME` | `./gradlew test --no-daemon` |
| swagger-mcp-server | `$SWAGGER_HOME` | `./gradlew test --no-daemon` |
| atlassian-mcp-server | `$ATLASSIAN_HOME` | `./gradlew test --no-daemon` |
| arc-reactor-admin | `$ADMIN_HOME` | `pnpm test:e2e` |

### 수정 절차

1. 코드 수정 (CLAUDE.md Gotchas 준수)
2. 해당 프로젝트 단위 테스트 실행
3. 서비스 재시작 필요 시: 프로세스 kill → 재빌드 → 재기동
4. curl로 수정 확인
5. `RUNTIME_ISSUES.md` 체크 (`- [x]`)
6. 해당 프로젝트 디렉토리에서 커밋 (`runtime-progress.txt`에 기록)

### Gradle 규칙

- `--no-daemon` 필수
- **동시에 2개 이상 Gradle 프로세스 금지** (포트/lock 충돌)
- 빌드 순서: 수정한 프로젝트만 빌드

### 서비스 재시작

```bash
# 예: arc-reactor 재시작
pkill -f "arc-reactor.*bootRun" || true
sleep 3
cd "$ARC_HOME" && SERVER_PORT=18081 ... ./gradlew bootRun --no-daemon > /tmp/arc-reactor.log 2>&1 &
# 헬스 체크 대기
```

---

## 4단계: 진행 기록

### runtime-progress.txt

```
[Runtime 반복 N] YYYY-MM-DD — 렌즈 X: [렌즈 이름]
- 테스트 N건 실행, 성공 N건, 실패 N건
- 이슈 발견: [간략 설명] 또는 "0건"
- 수정: [간략 설명] 또는 "없음"
```

50줄 초과 시 `runtime-progress-archive.txt`로 이동.

### 커밋

수정한 프로젝트 디렉토리에서 각각 커밋:

```bash
cd "$ARC_HOME"       && git add ... && git commit -m "fix: ..."
cd "$SWAGGER_HOME"   && git add ... && git commit -m "fix: ..."
```

---

## 완료 조건

10개 렌즈 전부 1회 이상 + 마지막 전체 사이클에서 **신규 이슈 0건** → 서비스 종료 → 각 프로젝트 `git push` → `<promise>IMPROVEMENTS COMPLETE</promise>`

### 서비스 종료

```bash
pkill -f "arc-reactor.*bootRun" || true
pkill -f "swagger-mcp.*bootRun" || true
pkill -f "atlassian-mcp.*bootRun" || true
pkill -f "vite.*dev" || true
```

---

## 절대 규칙

1. 기존 테스트를 깨지 않는다
2. main 브랜치 금지
3. **Gradle**: `--no-daemon` 필수, 동시 실행 금지
4. **LLM 비용 주의**: 채팅 테스트 시 짧은 메시지 사용, 불필요한 반복 호출 자제
5. **외부 서비스**: Jira/Confluence 읽기 전용 요청만 (create/update 금지 — 실제 데이터 변경 방지)
6. **서비스 로그**: 문제 발생 시 `/tmp/*.log` 확인
7. progress 50줄 초과 시 archive로 이동, 최근 20줄 유지
8. 수정은 해당 프로젝트 CLAUDE.md 규약 따르기 (없으면 arc-reactor 기준 적용)
