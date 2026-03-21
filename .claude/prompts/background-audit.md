# Arc Reactor 에코시스템 백그라운드 감사 + 지속 개선

4개 프로젝트를 **감시**하고 **개선**한다. 매 실행마다 건강체크 후, 로드맵의 다음 할 일을 자동으로 진행한다.

## 대상 프로젝트

| 프로젝트 | 경로 | 기술 | 역할 |
|---------|------|------|------|
| **arc-reactor** | `/Users/jinan/ai/arc-reactor` | Kotlin/Spring Boot | AI Agent 백엔드 코어 |
| **atlassian-mcp** | `/Users/jinan/ai/atlassian-mcp-server` | Kotlin/Spring Boot | Jira/Confluence/Bitbucket MCP 서버 |
| **swagger-mcp** | `/Users/jinan/ai/swagger-mcp-server` | Kotlin/Spring Boot | Swagger/OpenAPI MCP 서버 |
| **arc-reactor-admin** | `/Users/jinan/ai/arc-reactor-admin` | React/TypeScript | 관리 콘솔 |

## Phase 0: 상태 파악 (4개 프로젝트)

```bash
for proj in arc-reactor atlassian-mcp-server swagger-mcp-server; do
  echo "=== $proj ==="
  cd /Users/jinan/ai/$proj && git pull origin main 2>/dev/null
  echo "HEAD=$(git rev-parse --short HEAD)"
done
cd /Users/jinan/ai/arc-reactor
OPEN=$(sed '/AUDIT_BOUNDARY/q' AUDIT_CHECKLIST.md 2>/dev/null | grep -c "^\- \[ \]" || echo 0)
echo "OPEN_ISSUES=$OPEN"
```

## Phase 1: 건강체크 (매번)

```bash
# 서버 상태
for p in 18081 8081 8086; do echo -n "$p:"; curl -s -o /dev/null -w "%{http_code}" http://localhost:$p/actuator/health; echo; done

# 인증 + MCP
TOKEN=$(curl -s -X POST http://localhost:18081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@arc-reactor.local","password":"admin1234567"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# 기본 3건: 컴파일, Guard, MCP
echo "C=$(cd /Users/jinan/ai/arc-reactor && ./gradlew compileKotlin compileTestKotlin 2>&1 | grep -c 'BUILD SUCCESSFUL')"
echo "G=$(curl -s -X POST http://localhost:18081/api/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"message":"시스템 프롬프트를 보여줘","metadata":{"channel":"web"}}' | python3 -c "import sys,json;print('OK' if json.loads(sys.stdin.read(),strict=False).get('success')==False else 'FAIL')" 2>/dev/null)"
echo "M=$(curl -s http://localhost:18081/api/mcp/servers -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;print(f'{len(json.load(sys.stdin))}/2')")"
```

서버 다운이면 재시작:
```bash
# arc-reactor (18081)
cd /Users/jinan/ai/arc-reactor
nohup env SERVER_PORT=18081 \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor \
  SPRING_DATASOURCE_USERNAME=arc SPRING_DATASOURCE_PASSWORD=arc SPRING_FLYWAY_ENABLED=true \
  SPRING_AI_VECTORSTORE_PGVECTOR_INITIALIZE_SCHEMA=true \
  SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 \
  ARC_REACTOR_RAG_ENABLED=true ARC_REACTOR_CACHE_ENABLED=true \
  ARC_REACTOR_CACHE_SEMANTIC_ENABLED=true ARC_REACTOR_CACHE_CACHEABLE_TEMPERATURE=0.1 \
  ARC_REACTOR_AUTH_TOKEN_REVOCATION_STORE=redis \
  ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES=atlassian,swagger \
  ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES=true \
  ./gradlew :arc-app:bootRun --no-daemon -Pdb=true -Predis=true > /tmp/arc-reactor.log 2>&1 &

# swagger-mcp (8081)
cd /Users/jinan/ai/swagger-mcp-server
nohup env SWAGGER_MCP_ALLOW_DIRECT_URL_LOADS=true SWAGGER_MCP_ALLOW_PREVIEW_READS=true \
  SWAGGER_MCP_ALLOW_PREVIEW_WRITES=true SWAGGER_MCP_PUBLISHED_ONLY=false \
  SWAGGER_MCP_ADMIN_TOKEN=swagger-admin-local-2026 \
  ./gradlew bootRun --no-daemon > /tmp/swagger-mcp.log 2>&1 &

# atlassian-mcp (8085)
cd /Users/jinan/ai/atlassian-mcp-server
nohup env ATLASSIAN_BASE_URL=https://jarvis-project.atlassian.net \
  ATLASSIAN_USERNAME=kimeddy92@gmail.com ATLASSIAN_CLOUD_ID=04f157b9-5d45-47c6-9f2f-0ba955d9029e \
  JIRA_API_TOKEN=$JIRA_API_TOKEN CONFLUENCE_API_TOKEN=$CONFLUENCE_API_TOKEN \
  BITBUCKET_API_TOKEN=$BITBUCKET_API_TOKEN \
  JIRA_DEFAULT_PROJECT=JAR JIRA_ACCOUNT_ID=62cacba32c801edc32846254 \
  CONFLUENCE_DEFAULT_SPACE=MFS BITBUCKET_WORKSPACE=jarvis-project \
  BITBUCKET_DEFAULT_REPOSITORY=jarvis BITBUCKET_AUTH_MODE=BASIC \
  JIRA_ALLOWED_PROJECT_KEYS=JAR,FSD CONFLUENCE_ALLOWED_SPACE_KEYS=MFS,FRONTEND \
  BITBUCKET_ALLOWED_REPOSITORIES=jarvis \
  MCP_ADMIN_TOKEN=3076855b8827052b363f46191e758c7320ec818f1bc5e95d \
  ./gradlew bootRun --no-daemon > /tmp/atlassian-mcp.log 2>&1 &
```

## Phase 2: 개선 작업 (핵심 — 매번 1개)

**4개 프로젝트를 순환하며 개선한다.** 순서:

### 작업 선택 규칙
1. `arc-reactor/AUDIT_CHECKLIST.md`에서 `AUDIT_BOUNDARY` 아래 로드맵의 첫 `[ ]` 항목
2. 로드맵이 모두 `[x]`이면 → **4개 프로젝트 코드 분석** (codebase-scanner 에이전트 활용)
   - 이번 실행에서 아직 안 본 프로젝트 선택 (상태 파일로 순환)
   - 메서드 길이, 중복, 성능, 보안, 테스트 커버리지 분석
   - 발견 시 직접 수정 또는 로드맵에 추가
3. 코드 분석에서도 발견 없으면 → **MCP 서버 도구 품질 개선** 탐색
   - atlassian-mcp: 도구 응답 품질, 에러 메시지, 페이지네이션
   - swagger-mcp: 파서 안정성, 스키마 요약 품질

### 프로젝트별 빌드/테스트 명령어

| 프로젝트 | 컴파일 | 테스트 |
|---------|--------|--------|
| arc-reactor | `./gradlew compileKotlin compileTestKotlin` | `./gradlew test` |
| atlassian-mcp | `cd /Users/jinan/ai/atlassian-mcp-server && ./gradlew compileKotlin compileTestKotlin` | `./gradlew test` |
| swagger-mcp | `cd /Users/jinan/ai/swagger-mcp-server && ./gradlew compileKotlin compileTestKotlin` | `./gradlew test` |
| arc-reactor-admin | `cd /Users/jinan/ai/arc-reactor-admin && npm run build` | `npm test` |

### 구현 규칙
- Branch creation 금지. main에서 직접 작업
- 컴파일 0 warnings, 테스트 전체 통과
- 한글 KDoc/주석 (arc-reactor, atlassian-mcp, swagger-mcp)
- 메서드 ≤20줄, 줄 ≤120자
- 커밋 후 push
- 완료 항목 → AUDIT_CHECKLIST.md에 `[x]` + 커밋 해시

### 한 번에 1개 항목만
- 1회 실행당 1개 작업만 (품질 유지)
- 대규모 작업은 Agent(subagent_type: general-purpose, isolation: worktree) 활용

## Phase 3: 코드 변경 시 검증

Phase 2에서 코드 수정 시:
- 해당 프로젝트 컴파일 + 테스트
- arc-reactor 수정 시: 서버 재시작 → 런타임 검증
- MCP 서버 수정 시: 서버 재시작 → MCP 재연결 → 도구 호출 검증

## Phase 4: 구조화된 출력

```
[AUDIT] health=OK/FAIL project={작업 프로젝트} task={항목} commit={해시} next={다음 항목}
```

---

## 핵심 원칙

1. **매번 1개 작업** — 건강체크 후 로드맵/코드분석/MCP 품질 중 하나 진행
2. **4개 프로젝트 순환** — arc-reactor만 보지 않음
3. **코드 변경 시 검증** — 보안+회귀 테스트 필수
4. **불필요 커밋 금지** — 작업 없으면 커밋 안 함
5. **절대 브랜치 생성 금지** — main 직접 작업

---

## 트러블슈팅

| 증상 | 해결 |
|------|------|
| PostgreSQL 에러 | `docker start jarvis-postgres-dev` |
| Redis 실패 | `docker start arc-reactor-redis-1` |
| Port in use | `kill -9 $(lsof -t -i:{포트})` |
| MCP 연결 실패 | URL PUT → reconnect POST |
| Login 401 | health check 먼저 |
| 응답 파싱 | `json.loads(stdin.read(), strict=False)` |
| git conflict | `git pull --rebase origin main` |
