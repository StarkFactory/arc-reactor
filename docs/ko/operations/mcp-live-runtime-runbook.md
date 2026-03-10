# MCP Live 연동 런타임 런북

작성일: 2026-03-10

이 문서는 Arc Reactor와 외부 MCP 서버를 함께 띄워서 실제 Jira, Confluence, Bitbucket, Swagger, Slack 흐름을 재현할 때 필요한 최소 운영 절차를 정리한다.

## 1. 운영 원칙

- 비밀값은 모두 런타임 환경 변수 또는 시크릿 매니저로만 주입한다.
- MCP 서버 등록 정보는 `application.yml`이 아니라 관리자 API `POST /api/mcp/servers`, `PUT /api/mcp/servers/{name}`로 관리한다.
- 업스트림 MCP 서버가 admin HMAC를 요구하면 Arc Reactor에 등록한 서버 config에도 같은 `adminHmacSecret`과 `adminHmacWindowSeconds`를 넣어야 한다.
- allowlist는 빈 상태로 두지 말고 운영 범위에 맞는 Jira 프로젝트, Confluence space, Bitbucket repository, Swagger source만 열어 둔다.

## 2. Arc Reactor 런타임 준비

기본 런타임:

```bash
export GEMINI_API_KEY=replace-with-provider-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -hex 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
export ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES=atlassian,swagger
```

Slack local tool까지 같이 검증할 때:

```bash
export SLACK_BOT_TOKEN=xoxb-...
export ARC_REACTOR_SLACK_TOOLS_ENABLED=true
export ARC_REACTOR_SLACK_TOOLS_SCOPE_AWARE_ENABLED=true
```

Slack Events API 또는 Socket Mode까지 사용할 때만 아래를 추가한다.

```bash
export ARC_REACTOR_SLACK_ENABLED=true
export SLACK_APP_TOKEN=xapp-...
export SLACK_SIGNING_SECRET=replace-with-signing-secret
```

## 3. 동반 MCP 서버 하드닝

### Swagger MCP 서버

Swagger MCP 서버 프로세스에는 아래 admin hardening 값을 같이 준다.

```bash
export SWAGGER_MCP_ADMIN_TOKEN=replace-with-swagger-admin-token
export SWAGGER_MCP_ADMIN_HMAC_REQUIRED=true
export SWAGGER_MCP_ADMIN_HMAC_SECRET=replace-with-shared-hmac-secret
export SWAGGER_MCP_ADMIN_HMAC_WINDOW_SECONDS=300
```

### Atlassian MCP 서버

Atlassian MCP 서버 프로세스에도 같은 방식으로 admin hardening 값을 준다.

```bash
export MCP_ADMIN_TOKEN=replace-with-atlassian-admin-token
export MCP_ADMIN_HMAC_REQUIRED=true
export MCP_ADMIN_HMAC_SECRET=replace-with-shared-hmac-secret
export MCP_ADMIN_HMAC_WINDOW_SECONDS=300
```

실제 live 검증에서 중요했던 상호운용 메모:

- Jira 프로젝트 탐색이 비어 있으면 `JIRA_USE_API_GATEWAY=true`를 먼저 확인한다.
- Bitbucket 연결이 불안정하면 `BITBUCKET_AUTH_MODE=BASIC`을 먼저 확인한다.
- Atlassian 자격증명과 allowlist 키 이름은 동반 MCP 서버 저장소의 런북에 맞춰 설정한다.

## 4. MCP 서버 등록 또는 갱신

Arc Reactor 관리 API에 등록하는 config에는 admin token과 HMAC 설정을 같이 넣는다.

Swagger MCP 서버 등록 예시:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "swagger",
    "description": "Swagger MCP server",
    "transportType": "SSE",
    "config": {
      "url": "http://localhost:18091/sse",
      "adminUrl": "http://localhost:18091",
      "adminToken": "'"$SWAGGER_MCP_ADMIN_TOKEN"'",
      "adminHmacRequired": true,
      "adminHmacSecret": "'"$SHARED_MCP_HMAC_SECRET"'",
      "adminHmacWindowSeconds": 300
    },
    "autoConnect": true
  }'
```

Atlassian MCP 서버 등록 예시:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "atlassian",
    "description": "Atlassian MCP server",
    "transportType": "SSE",
    "config": {
      "url": "http://localhost:18092/sse",
      "adminUrl": "http://localhost:18092",
      "adminToken": "'"$MCP_ADMIN_TOKEN"'",
      "adminHmacRequired": true,
      "adminHmacSecret": "'"$SHARED_MCP_HMAC_SECRET"'",
      "adminHmacWindowSeconds": 300
    },
    "autoConnect": true
  }'
```

이미 등록된 서버를 수정할 때는 같은 `config` 구조로 `PUT /api/mcp/servers/{name}`를 사용한다.

## 5. 접근 정책 적용

Swagger는 published source만 열어 두는 구성이 안전하다.

```bash
curl -X PUT http://localhost:8080/api/mcp/servers/swagger/access-policy \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "allowedSourceNames": ["replace-with-published-source"],
    "publishedOnly": true,
    "allowPreviewReads": false,
    "allowPreviewWrites": false,
    "allowDirectUrlLoads": false
  }'
```

Atlassian은 필요한 범위만 allowlist에 넣는다.

```bash
curl -X PUT http://localhost:8080/api/mcp/servers/atlassian/access-policy \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "allowedJiraProjectKeys": ["PROJ"],
    "allowedConfluenceSpaceKeys": ["ENG"],
    "allowedBitbucketRepositories": ["service-repo"]
  }'
```

## 6. 운영 검증 순서

### 1) 기본 상태

```bash
curl http://localhost:8080/actuator/health
curl -H "Authorization: Bearer $ADMIN_JWT" http://localhost:8080/api/mcp/servers
```

기대 상태:

- Arc Reactor health가 `UP`
- `swagger`, `atlassian` 서버가 모두 `CONNECTED`

### 2) Control plane readiness

```bash
curl -X POST http://localhost:8080/api/mcp/servers/swagger/preflight \
  -H "Authorization: Bearer $ADMIN_JWT"

curl -X POST http://localhost:8080/api/mcp/servers/atlassian/preflight \
  -H "Authorization: Bearer $ADMIN_JWT"
```

기대 상태:

- 두 응답 모두 `ok=true`
- 운영 배포 기준은 `readyForProduction=true`

### 3) Grounded 응답 스모크 테스트

- Swagger summary 질문이 `spec_summary`로 처리되는지 확인
- Swagger detail 질문이 `spec_detail`로 처리되는지 확인
- Jira 또는 Bitbucket 질문이 source link와 함께 grounded 응답으로 끝나는지 확인
- Slack `send_message` 성공 시 전달 확인 문구가 반환되고, 메타데이터에 `deliveryAcknowledged=true`가 남는지 확인

## 7. 장애 징후와 우선 확인 포인트

- preflight가 `readyForProduction=false`이고 `admin_hmac`만 `WARN`이면 동반 MCP 서버 HMAC 설정 또는 Arc Reactor 등록 config를 먼저 비교한다.
- Bitbucket connectivity가 실패하면 auth mode와 allowlist repository 이름을 먼저 본다.
- Jira connectivity는 통과하지만 프로젝트가 비어 있으면 gateway 사용 여부와 토큰 scope를 먼저 본다.
- Slack 메시지는 전송됐는데 grounded가 `false`인 경우는 정상일 수 있다. 이 경우는 전달 영수증이지 지식 답변이 아니다.

## 8. 연관 문서

- [MCP Control Plane 연동 작업 및 실제 검증 보고서 (2026-03-10)](mcp-control-plane-integration-report-2026-03-10.md)
- [보안 검증 플레이북](security-validation-playbook.md)
- [배포 가이드](../getting-started/deployment.md)
