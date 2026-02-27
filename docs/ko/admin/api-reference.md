# 관리자 API 레퍼런스

이 문서는 Arc Reactor의 모든 관리자 전용 엔드포인트를 컨트롤러 소스 코드를 기반으로 작성하였습니다. 경로, 요청 필드, 응답 필드, 상태 코드는 실제 구현을 반영합니다.

---

## 인증

### 인증 비활성화 (기본값)

`arc.reactor.auth.enabled=false`(기본값)인 경우 `JwtAuthWebFilter`가 등록되지 않습니다. `isAdmin()` 검사는 exchange에서 role 속성을 읽는데, 설정되지 않았으므로 `null`입니다. `null` role은 admin으로 취급되므로 **모든 요청이 admin으로 처리**되며 토큰이 필요 없습니다.

로컬 개발 및 폐쇄망 배포에 적합한 기본값입니다.

### 인증 활성화 (프로덕션)

`arc.reactor.auth.enabled=true` 및 `arc.reactor.auth.jwt-secret=<secret>`을 설정합니다 (최소 32바이트; `openssl rand -base64 32`로 생성).

**1단계: 회원가입 또는 로그인**

```
POST /api/auth/register
POST /api/auth/login
```

두 엔드포인트 모두 응답 본문에 `token` 필드를 반환합니다.

**2단계: 토큰 전송**

이후 모든 요청에 토큰을 포함합니다:

```
Authorization: Bearer <token>
```

유효하지 않은 토큰으로 보호된 엔드포인트에 접근하면 `401 Unauthorized`를 반환합니다. 유효한 토큰이지만 권한이 부족한 경우(USER가 admin 엔드포인트 접근 시) `403 Forbidden`을 반환합니다.

### 필수 헤더

| 헤더 | 필수 여부 | 설명 |
|------|---------|------|
| `Content-Type` | 쓰기 작업 시 필수 | `application/json` 이어야 합니다 |
| `Authorization` | 인증 활성화 시 필수 | `Bearer <JWT>` |
| `X-Tenant-Id` | 선택 (arc-admin 모듈) | 테넌트 격리 식별자. 영숫자, 하이픈, 언더스코어. 최대 64자 |

### Admin vs User 접근

- **Admin 필수**: 설정을 생성·수정·삭제하는 엔드포인트. 인증이 비활성화된 경우 모든 호출자가 admin으로 처리됩니다.
- **인증 필요**: 유효한 JWT가 필요하지만 ADMIN role은 요구하지 않는 엔드포인트.
- **공개**: 인증 불필요.

### 오류 응답 형식

모든 오류 응답은 아래 표준 DTO를 사용합니다:

```json
{
  "error": "오류 설명",
  "details": "선택적 추가 정보",
  "timestamp": "2026-02-28T12:00:00Z"
}
```

`403 Forbidden` 응답은 항상 본문을 포함하며 빈 응답을 반환하지 않습니다.

---

## 인증 엔드포인트

> **활성화 조건**: `arc.reactor.auth.enabled=true`인 경우에만 등록됩니다.

### POST /api/auth/register

새 사용자 계정을 등록하고 JWT를 받습니다.

**인증**: 공개

**요청 본문**:
```json
{
  "email": "user@example.com",
  "password": "mypassword123",
  "name": "홍길동"
}
```

| 필드 | 타입 | 필수 | 제약 조건 |
|------|------|------|---------|
| `email` | string | 예 | 유효한 이메일 형식 |
| `password` | string | 예 | 최소 8자 |
| `name` | string | 예 | 공백 불가 |

**응답 `201 Created`**:
```json
{
  "token": "<JWT>",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "name": "홍길동",
    "role": "USER"
  }
}
```

**응답 코드**: `201` 성공 | `400` 유효성 오류 | `409` 이메일 이미 등록됨

---

### POST /api/auth/login

이메일과 비밀번호로 인증하고 JWT를 받습니다.

**인증**: 공개

**요청 본문**:
```json
{
  "email": "user@example.com",
  "password": "mypassword123"
}
```

**응답 `200 OK`**:
```json
{
  "token": "<JWT>",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "name": "홍길동",
    "role": "USER"
  }
}
```

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `401` 잘못된 자격증명

---

### GET /api/auth/me

현재 인증된 사용자의 프로필을 조회합니다.

**인증**: 인증 필요 (유효한 JWT 필요)

**응답 `200 OK`**:
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "name": "홍길동",
  "role": "USER"
}
```

**응답 코드**: `200` 성공 | `401` JWT 없음 또는 유효하지 않음 | `404` 사용자 없음

---

### POST /api/auth/change-password

현재 사용자의 비밀번호를 변경합니다.

**인증**: 인증 필요 (유효한 JWT 필요)

**요청 본문**:
```json
{
  "currentPassword": "oldpassword",
  "newPassword": "newpassword123"
}
```

**응답 `200 OK`**:
```json
{
  "message": "Password changed successfully"
}
```

**응답 코드**: `200` 성공 | `400` 현재 비밀번호 오류 또는 미지원 AuthProvider | `401` 미인증 | `404` 사용자 없음

---

## 페르소나 관리

기본 경로: `/api/personas`

페르소나는 이름이 있는 시스템 프롬프트 설정입니다. 채팅 요청에 페르소나나 프롬프트 템플릿이 지정되지 않은 경우 기본 페르소나가 적용됩니다.

### GET /api/personas

모든 페르소나를 조회합니다.

**인증**: 공개

**응답 `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "친절한 어시스턴트",
    "systemPrompt": "당신은 도움이 되는 AI 어시스턴트입니다...",
    "isDefault": true,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

---

### GET /api/personas/{personaId}

ID로 페르소나를 조회합니다.

**인증**: 공개

**응답 `200 OK`**: 단일 `PersonaResponse` 객체 (목록 항목과 동일한 형태)

**응답 코드**: `200` 성공 | `404` 없음

---

### POST /api/personas

새 페르소나를 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "고객 지원",
  "systemPrompt": "당신은 고객 지원 에이전트입니다...",
  "isDefault": false
}
```

| 필드 | 타입 | 필수 | 기본값 | 제약 조건 |
|------|------|------|-------|---------|
| `name` | string | 예 | — | 공백 불가 |
| `systemPrompt` | string | 예 | — | 공백 불가 |
| `isDefault` | boolean | 아니오 | `false` | — |

**응답 `201 Created`**: `PersonaResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 유효성 오류 | `403` admin 필요

---

### PUT /api/personas/{personaId}

기존 페르소나를 업데이트합니다. 제공된 필드만 변경됩니다 (부분 업데이트).

**인증**: Admin 필수

**요청 본문** (모든 필드 선택):
```json
{
  "name": "업데이트된 이름",
  "systemPrompt": "업데이트된 프롬프트...",
  "isDefault": true
}
```

**응답 `200 OK`**: 업데이트된 `PersonaResponse` 객체

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요 | `404` 없음

---

### DELETE /api/personas/{personaId}

페르소나를 삭제합니다. 멱등성 — 존재하지 않아도 `204`를 반환합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

**응답 코드**: `204` 삭제됨 | `403` admin 필요

---

## 프롬프트 템플릿

기본 경로: `/api/prompt-templates`

버전이 있는 시스템 프롬프트 템플릿입니다. 각 템플릿은 여러 버전을 가지며 상태는 `DRAFT` → `ACTIVE` → `ARCHIVED` 순서로 진행됩니다. 한 번에 하나의 버전만 `ACTIVE` 상태가 될 수 있습니다.

### GET /api/prompt-templates

모든 프롬프트 템플릿을 조회합니다.

**인증**: 공개

**응답 `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "지원 봇 v2",
    "description": "고객 지원 템플릿",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

---

### GET /api/prompt-templates/{templateId}

템플릿과 모든 버전을 조회합니다.

**인증**: 공개

**응답 `200 OK`**:
```json
{
  "id": "uuid",
  "name": "지원 봇 v2",
  "description": "고객 지원 템플릿",
  "activeVersion": {
    "id": "version-uuid",
    "templateId": "uuid",
    "version": 3,
    "content": "당신은 지원 에이전트입니다...",
    "status": "ACTIVE",
    "changeLog": "어조 개선",
    "createdAt": 1700000000000
  },
  "versions": [...],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```

**응답 코드**: `200` 성공 | `404` 없음

---

### POST /api/prompt-templates

새 프롬프트 템플릿을 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "지원 봇 v2",
  "description": "고객 지원 템플릿"
}
```

**응답 `201 Created`**: `TemplateResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 유효성 오류 | `403` admin 필요

---

### PUT /api/prompt-templates/{templateId}

템플릿 메타데이터를 업데이트합니다. 부분 업데이트.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요 | `404` 없음

---

### DELETE /api/prompt-templates/{templateId}

템플릿과 모든 버전을 삭제합니다. 멱등성.

**인증**: Admin 필수

**응답**: `204 No Content`

---

### POST /api/prompt-templates/{templateId}/versions

템플릿의 새 버전을 생성합니다. 새 버전은 `DRAFT` 상태로 시작합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "content": "당신은 전문 지원 에이전트입니다...",
  "changeLog": "공감 표현 추가"
}
```

**응답 `201 Created`**: `VersionResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 유효성 오류 | `403` admin 필요 | `404` 템플릿 없음

---

### PUT /api/prompt-templates/{templateId}/versions/{versionId}/activate

버전을 활성화합니다. 기존 활성 버전은 자동으로 아카이브됩니다.

**인증**: Admin 필수

**응답 `200 OK`**: `status: "ACTIVE"`인 `VersionResponse` 객체

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 템플릿 또는 버전 없음

---

### PUT /api/prompt-templates/{templateId}/versions/{versionId}/archive

버전을 아카이브합니다.

**인증**: Admin 필수

**응답 `200 OK`**: `status: "ARCHIVED"`인 `VersionResponse` 객체

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

## MCP 서버 관리

기본 경로: `/api/mcp/servers`

동적 MCP(Model Context Protocol) 서버 등록 및 수명 주기 관리. MCP 서버는 에이전트에 도구를 제공합니다. 지원 전송 타입: `SSE`, `STDIO`. HTTP 전송은 지원하지 않습니다.

### GET /api/mcp/servers

등록된 모든 MCP 서버를 연결 상태와 함께 조회합니다.

**인증**: 공개

**응답 `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "my-mcp-server",
    "description": "Jira 및 Confluence 도구",
    "transportType": "SSE",
    "autoConnect": true,
    "status": "CONNECTED",
    "toolCount": 12,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

상태 값: `PENDING`, `CONNECTED`, `DISCONNECTED`, `FAILED`

---

### POST /api/mcp/servers

새 MCP 서버를 등록하고 선택적으로 연결합니다.

**인증**: Admin 필수

**요청 본문 (SSE 전송)**:
```json
{
  "name": "my-mcp-server",
  "description": "Jira 도구 제공",
  "transportType": "SSE",
  "config": {
    "url": "http://localhost:8081/sse",
    "adminUrl": "http://localhost:8081",
    "adminToken": "secret-admin-token"
  },
  "autoConnect": true
}
```

**요청 본문 (STDIO 전송)**:
```json
{
  "name": "fs-server",
  "transportType": "STDIO",
  "config": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
  },
  "autoConnect": true
}
```

| 필드 | 타입 | 필수 | 기본값 | 제약 조건 |
|------|------|------|-------|---------|
| `name` | string | 예 | — | 공백 불가, 최대 100자 |
| `description` | string | 아니오 | `null` | 최대 500자 |
| `transportType` | string | 아니오 | `"SSE"` | `SSE` 또는 `STDIO` |
| `config` | object | 아니오 | `{}` | 최대 20개 항목 |
| `version` | string | 아니오 | `null` | — |
| `autoConnect` | boolean | 아니오 | `true` | — |

**응답 `201 Created`**: `McpServerResponse` 객체

**응답 코드**: `201` 등록됨 | `400` 잘못된 전송 타입 또는 보안 허용 목록에 없음 | `403` admin 필요 | `409` 서버 이름 이미 존재

---

### GET /api/mcp/servers/{name}

연결 상태 및 사용 가능한 도구를 포함한 서버 상세 정보를 조회합니다.

**인증**: 공개

**응답 `200 OK`**:
```json
{
  "id": "uuid",
  "name": "my-mcp-server",
  "description": "Jira 도구 제공",
  "transportType": "SSE",
  "config": { "url": "http://localhost:8081/sse" },
  "version": null,
  "autoConnect": true,
  "status": "CONNECTED",
  "tools": ["jira_create_issue", "jira_search_issues"],
  "createdAt": 1700000000000,
  "updatedAt": 1700000000000
}
```

**응답 코드**: `200` 성공 | `404` 없음

---

### PUT /api/mcp/servers/{name}

MCP 서버 설정을 업데이트합니다. 전송 변경 적용을 위해 재연결이 필요합니다.

**인증**: Admin 필수

**요청 본문** (모든 필드 선택):
```json
{
  "description": "업데이트된 설명",
  "config": { "url": "http://new-host:8081/sse" },
  "autoConnect": false
}
```

**응답 `200 OK`**: 업데이트된 `McpServerResponse` 객체

**응답 코드**: `200` 성공 | `400` 잘못된 전송 타입 | `403` admin 필요 | `404` 없음

---

### DELETE /api/mcp/servers/{name}

MCP 서버를 연결 해제하고 제거합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

**응답 코드**: `204` 제거됨 | `403` admin 필요 | `404` 없음

---

### POST /api/mcp/servers/{name}/connect

등록된 MCP 서버에 연결합니다.

**인증**: Admin 필수

**응답 `200 OK`**:
```json
{
  "status": "CONNECTED",
  "tools": ["tool_one", "tool_two"]
}
```

**응답 코드**: `200` 연결됨 | `403` admin 필요 | `404` 없음 | `503` 연결 실패

---

### POST /api/mcp/servers/{name}/disconnect

MCP 서버에서 연결을 해제합니다 (제거하지 않음).

**인증**: Admin 필수

**응답 `200 OK`**:
```json
{
  "status": "DISCONNECTED"
}
```

**응답 코드**: `200` 연결 해제됨 | `403` admin 필요 | `404` 없음

---

## MCP 접근 정책

기본 경로: `/api/mcp/servers/{name}/access-policy`

MCP 서버의 `/admin/access-policy` 엔드포인트에 대한 프록시 컨트롤러 (예: Atlassian MCP 서버). MCP 서버의 `config`에 `adminToken`과 `adminUrl` 또는 `url`이 포함되어 있어야 합니다.

### GET /api/mcp/servers/{name}/access-policy

MCP 서버의 관리자 API에서 현재 접근 정책을 가져옵니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `400` 잘못된 서버 설정 | `403` admin 필요 | `404` 서버 없음 | `504` 업스트림 타임아웃

---

### PUT /api/mcp/servers/{name}/access-policy

MCP 서버의 관리자 API에서 접근 정책을 업데이트합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "allowedJiraProjectKeys": ["PROJ", "MYTEAM"],
  "allowedConfluenceSpaceKeys": ["ENG", "DOCS"]
}
```

| 필드 | 타입 | 필수 | 제약 조건 |
|------|------|------|---------|
| `allowedJiraProjectKeys` | array of string | 아니오 | 최대 200개. 각 키: `^[A-Z][A-Z0-9_]*$`, 최대 50자, 앞뒤 공백 없음 |
| `allowedConfluenceSpaceKeys` | array of string | 아니오 | 최대 200개. 각 키: `^[A-Za-z0-9][A-Za-z0-9_-]*$`, 최대 64자 |

**응답 코드**: `200` 업데이트됨 | `400` 유효성 오류 또는 잘못된 서버 설정 | `403` admin 필요 | `404` 서버 없음

---

### DELETE /api/mcp/servers/{name}/access-policy

MCP 서버의 동적 접근 정책을 초기화하여 환경 기본값으로 되돌립니다.

**인증**: Admin 필수

**응답 코드**: `200` 초기화됨 | `400` 잘못된 서버 설정 | `403` admin 필요 | `404` 서버 없음

---

## 도구 정책

기본 경로: `/api/tool-policy`

> **활성화 조건**: `arc.reactor.tool-policy.dynamic.enabled=true`

어떤 도구가 "쓰기" 도구로 분류되는지, 어떤 채널에서 쓰기 접근이 거부되는지 제어합니다.

### GET /api/tool-policy

현재 도구 정책 상태를 조회합니다 (유효 정책 및 저장된 정책 모두).

**인증**: Admin 필수

**응답 `200 OK`**:
```json
{
  "configEnabled": true,
  "dynamicEnabled": true,
  "effective": {
    "enabled": true,
    "writeToolNames": ["jira_create_issue"],
    "denyWriteChannels": ["readonly-channel"],
    "allowWriteToolNamesInDenyChannels": [],
    "allowWriteToolNamesByChannel": {},
    "denyWriteMessage": "Error: This tool is not allowed in this channel",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  },
  "stored": null
}
```

**응답 코드**: `200` 성공 | `403` admin 필요

---

### PUT /api/tool-policy

저장된 도구 정책을 업데이트합니다. 즉시 적용됩니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "enabled": true,
  "writeToolNames": ["jira_create_issue"],
  "denyWriteChannels": ["readonly-channel"],
  "allowWriteToolNamesInDenyChannels": [],
  "allowWriteToolNamesByChannel": {
    "special-channel": ["jira_create_issue"]
  },
  "denyWriteMessage": "이 채널에서는 쓰기 도구를 사용할 수 없습니다"
}
```

| 필드 | 타입 | 필수 | 기본값 | 제약 조건 |
|------|------|------|-------|---------|
| `enabled` | boolean | 아니오 | `false` | — |
| `writeToolNames` | array of string | 아니오 | `[]` | 최대 500개 |
| `denyWriteChannels` | array of string | 아니오 | `[]` | 최대 50개. 소문자로 저장 |
| `allowWriteToolNamesInDenyChannels` | array of string | 아니오 | `[]` | 최대 500개 |
| `allowWriteToolNamesByChannel` | object | 아니오 | `{}` | 최대 200개 채널 |
| `denyWriteMessage` | string | 아니오 | (기본 메시지) | 최대 500자 |

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요

---

### DELETE /api/tool-policy

저장된 도구 정책을 삭제하여 설정 파일 기본값으로 복원합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

---

## 출력 가드 규칙

기본 경로: `/api/output-guard/rules`

LLM 출력에서 민감한 내용을 검사하고 선택적으로 차단하거나 마스킹하는 동적 정규식 기반 규칙입니다. 규칙은 우선순위 오름차순으로 적용됩니다.

작업: `BLOCK` (응답 거부), `MASK` (매칭된 텍스트 교체), `LOG` (수정 없이 로깅).

### GET /api/output-guard/rules

모든 출력 가드 규칙을 조회합니다.

**인증**: Admin 필수

**응답 `200 OK`**:
```json
[
  {
    "id": "uuid",
    "name": "PII 신용카드",
    "pattern": "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b",
    "action": "MASK",
    "priority": 10,
    "enabled": true,
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000
  }
]
```

---

### GET /api/output-guard/rules/audits

출력 가드 규칙 감사 로그를 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**: `limit` (기본값 100, 최대 1000)

**응답 코드**: `200` 성공 | `403` admin 필요

---

### POST /api/output-guard/rules

새 출력 가드 규칙을 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "PII 신용카드",
  "pattern": "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b",
  "action": "MASK",
  "priority": 10,
  "enabled": true
}
```

| 필드 | 타입 | 필수 | 기본값 | 제약 조건 |
|------|------|------|-------|---------|
| `name` | string | 예 | — | 공백 불가, 최대 120자 |
| `pattern` | string | 예 | — | 공백 불가, 유효한 Java 정규식, 최대 5000자 |
| `action` | string | 아니오 | `"MASK"` | `BLOCK`, `MASK`, 또는 `LOG` (대소문자 구분 없음) |
| `priority` | integer | 아니오 | `100` | 최소 1, 최대 10000 |
| `enabled` | boolean | 아니오 | `true` | — |

**응답 `201 Created`**: `OutputGuardRuleResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 잘못된 action 또는 정규식 | `403` admin 필요

---

### PUT /api/output-guard/rules/{id}

출력 가드 규칙을 업데이트합니다. 부분 업데이트.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `400` 잘못된 action 또는 정규식 | `403` admin 필요 | `404` 없음

---

### DELETE /api/output-guard/rules/{id}

출력 가드 규칙을 삭제합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

**응답 코드**: `204` 삭제됨 | `403` admin 필요 | `404` 없음

---

### POST /api/output-guard/rules/simulate

주어진 콘텐츠 문자열에 대해 출력 가드 정책을 시뮬레이션합니다 (드라이런). 규칙 상태는 변경되지 않습니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "content": "내 신용카드는 4111 1111 1111 1111입니다",
  "includeDisabled": false
}
```

**응답 `200 OK`**:
```json
{
  "originalContent": "내 신용카드는 4111 1111 1111 1111입니다",
  "resultContent": "내 신용카드는 [MASKED]입니다",
  "blocked": false,
  "modified": true,
  "blockedByRuleId": null,
  "blockedByRuleName": null,
  "matchedRules": [...],
  "invalidRules": []
}
```

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요

---

## 인텐트 분류

기본 경로: `/api/intents`

> **활성화 조건**: `arc.reactor.intent.enabled=true`

사용자 요청 분류 및 인텐트별 에이전트 프로필 적용을 위한 인텐트 정의를 관리합니다.

### GET /api/intents

모든 인텐트 정의를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

### GET /api/intents/{intentName}

이름으로 인텐트 정의를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

### POST /api/intents

새 인텐트 정의를 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "order_inquiry",
  "description": "사용자가 기존 주문에 대해 문의하고 있습니다",
  "examples": ["주문이 어디 있나요?", "배송 추적해주세요"],
  "keywords": ["주문", "배송", "추적"],
  "profile": {
    "model": null,
    "temperature": 0.2,
    "maxToolCalls": 5,
    "allowedTools": ["order_lookup"],
    "systemPrompt": null,
    "responseFormat": null
  },
  "enabled": true
}
```

**프로필 필드** (모두 null 가능 — `null`은 전역 기본값 사용):

| 필드 | 타입 | 설명 |
|------|------|------|
| `model` | string | LLM 제공자 재정의 (예: `"gemini"`, `"openai"`, `"anthropic"`) |
| `temperature` | number | 온도 재정의 |
| `maxToolCalls` | integer | 최대 도구 호출 수 재정의 |
| `allowedTools` | array of string | 도구 허용 목록 (`null` = 모든 도구 허용) |
| `systemPrompt` | string | 시스템 프롬프트 재정의 |
| `responseFormat` | string | `TEXT`, `JSON`, 또는 `YAML` |

**응답 `201 Created`**: `IntentResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 유효성 오류 | `403` admin 필요 | `409` 이름 이미 존재

---

### PUT /api/intents/{intentName}

기존 인텐트 정의를 업데이트합니다. 부분 업데이트.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

### DELETE /api/intents/{intentName}

인텐트 정의를 삭제합니다. 멱등성.

**인증**: Admin 필수

**응답**: `204 No Content`

---

## 인간-루프 (HITL) 승인

기본 경로: `/api/approvals`

> **활성화 조건**: `arc.reactor.approval.enabled=true`

대기 중인 도구 호출 승인 요청을 관리합니다.

### GET /api/approvals

대기 중인 승인 요청을 조회합니다. Admin은 모든 항목을, 일반 사용자는 자신의 항목만 볼 수 있습니다.

**인증**: 인증 필요 (Admin은 모두 조회; 사용자는 자신의 것만)

**응답 코드**: `200` 성공 | `403` 미인증

---

### POST /api/approvals/{id}/approve

대기 중인 도구 호출을 승인합니다. 선택적으로 도구 인수를 재정의할 수 있습니다.

**인증**: Admin 필수

**요청 본문** (선택):
```json
{
  "modifiedArguments": {
    "issueId": "PROJ-123"
  }
}
```

**응답 `200 OK`**:
```json
{
  "success": true,
  "message": "Approved"
}
```

**응답 코드**: `200` 항상 반환 | `403` admin 필요

---

### POST /api/approvals/{id}/reject

대기 중인 도구 호출을 거부합니다.

**인증**: Admin 필수

**요청 본문** (선택):
```json
{
  "reason": "보안 정책으로 인해 거부"
}
```

**응답 코드**: `200` 항상 반환 | `403` admin 필요

---

## 스케줄러 (크론 작업)

기본 경로: `/api/scheduler/jobs`

> **활성화 조건**: `DynamicSchedulerService` 빈이 있는 경우

크론 표현식을 사용한 MCP 도구 실행 예약 관리.

### GET /api/scheduler/jobs

모든 예약된 작업을 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

### POST /api/scheduler/jobs

새 예약 작업을 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "일일 Jira 동기화",
  "description": "매일 아침 Jira 이슈 동기화",
  "cronExpression": "0 9 * * MON-FRI",
  "timezone": "Asia/Seoul",
  "mcpServerName": "jira-server",
  "toolName": "jira_sync_issues",
  "toolArguments": { "projectKey": "PROJ" },
  "slackChannelId": "C0123456",
  "enabled": true
}
```

| 필드 | 타입 | 필수 | 기본값 | 제약 조건 |
|------|------|------|-------|---------|
| `name` | string | 예 | — | 공백 불가, 최대 200자 |
| `cronExpression` | string | 예 | — | 유효한 크론 표현식 |
| `timezone` | string | 아니오 | `"Asia/Seoul"` | 유효한 타임존 ID |
| `mcpServerName` | string | 예 | — | 등록된 MCP 서버여야 함 |
| `toolName` | string | 예 | — | 공백 불가 |
| `toolArguments` | object | 아니오 | `{}` | — |
| `enabled` | boolean | 아니오 | `true` | — |

**응답 `201 Created`**: `ScheduledJobResponse` 객체

---

### GET /api/scheduler/jobs/{id}

예약 작업 상세 정보를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

### PUT /api/scheduler/jobs/{id}

예약 작업을 업데이트합니다 (전체 교체).

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요 | `404` 없음

---

### DELETE /api/scheduler/jobs/{id}

예약 작업을 삭제합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

---

### POST /api/scheduler/jobs/{id}/trigger

크론 스케줄을 우회하여 예약 작업을 즉시 실행합니다.

**인증**: Admin 필수

**응답 `200 OK`**:
```json
{
  "result": "triggered"
}
```

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

## RAG / 문서 관리

기본 경로: `/api/documents`

> **활성화 조건**: `arc.reactor.rag.enabled=true` + VectorStore 빈

벡터 스토어(RAG 지식 베이스)의 문서를 관리합니다.

### POST /api/documents

단일 문서를 벡터 스토어에 추가합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "content": "반품 정책은 30일 이내 반품을 허용합니다...",
  "metadata": {
    "source": "policy-doc",
    "category": "returns"
  }
}
```

| 필드 | 타입 | 필수 | 제약 조건 |
|------|------|------|---------|
| `content` | string | 예 | 공백 불가, 최대 100000자 |
| `metadata` | object | 아니오 | 최대 50개 항목 |

**응답 `201 Created`**: `DocumentResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 유효성 오류 | `403` admin 필요

---

### POST /api/documents/batch

여러 문서를 한 번에 추가합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "documents": [
    { "content": "첫 번째 문서 내용" },
    { "content": "두 번째 문서 내용", "metadata": { "tag": "v1" } }
  ]
}
```

| 필드 | 제약 조건 |
|------|---------|
| `documents` | 비어있지 않음, 최대 100개 |

**응답 `201 Created`**: `{ "count": 2, "ids": ["uuid-1", "uuid-2"] }`

---

### POST /api/documents/search

의미론적 유사도로 문서를 검색합니다.

**인증**: 공개

**요청 본문**:
```json
{
  "query": "반품 정책이 무엇인가요?",
  "topK": 5,
  "similarityThreshold": 0.7
}
```

**응답 `200 OK`**: `SearchResultResponse` 객체 배열

**응답 코드**: `200` 성공 | `400` 유효성 오류

---

### DELETE /api/documents

ID로 문서를 삭제합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "ids": ["uuid-1", "uuid-2"]
}
```

**응답**: `204 No Content`

**응답 코드**: `204` 삭제됨 | `403` admin 필요

---

## RAG 인제스션 정책

기본 경로: `/api/rag-ingestion/policy`

> **활성화 조건**: `arc.reactor.rag.ingestion.dynamic.enabled=true`

에이전트 대화를 RAG 인제스션 후보로 자동 캡처하는 것을 제어합니다.

### GET /api/rag-ingestion/policy

현재 RAG 인제스션 정책 상태를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

### PUT /api/rag-ingestion/policy

저장된 RAG 인제스션 정책을 업데이트합니다. 즉시 적용됩니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "enabled": true,
  "requireReview": true,
  "allowedChannels": ["web", "slack"],
  "minQueryChars": 10,
  "minResponseChars": 20,
  "blockedPatterns": ["password", "secret"]
}
```

**응답 코드**: `200` 성공 | `400` 유효성 오류 | `403` admin 필요

---

### DELETE /api/rag-ingestion/policy

저장된 RAG 인제스션 정책을 삭제하여 설정 기본값으로 복원합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

---

## RAG 인제스션 후보

기본 경로: `/api/rag-ingestion/candidates`

> **활성화 조건**: `arc.reactor.rag.ingestion.enabled=true`

벡터 스토어에 인제스트되기 전에 캡처된 대화 후보를 검토합니다.

후보 상태: `PENDING`, `INGESTED`, `REJECTED`

### GET /api/rag-ingestion/candidates

RAG 인제스션 후보를 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**: `status`, `channel`, `limit` (기본값 100, 최대 500)

**응답 코드**: `200` 성공 | `403` admin 필요

---

### POST /api/rag-ingestion/candidates/{id}/approve

후보를 승인하고 VectorStore에 인제스트합니다.

**인증**: Admin 필수

**요청 본문** (선택): `{ "comment": "품질 좋은 Q&A" }`

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음 | `409` 이미 검토됨 | `503` VectorStore 미설정

---

### POST /api/rag-ingestion/candidates/{id}/reject

후보를 거부합니다.

**인증**: Admin 필수

**요청 본문** (선택): `{ "comment": "품질 불충분" }`

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음 | `409` 이미 검토됨

---

## 프롬프트 랩

기본 경로: `/api/prompt-lab`

> **활성화 조건**: `ExperimentStore` 빈이 있는 경우

프롬프트 최적화 실험. 프롬프트 템플릿 버전 간 A/B 테스트를 실행합니다.

실험 상태: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`

### POST /api/prompt-lab/experiments

새 실험을 생성합니다.

**인증**: Admin 필수

**응답 `201 Created`**: `ExperimentResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 한도 초과 | `403` admin 필요

---

### GET /api/prompt-lab/experiments

실험 목록을 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**: `status`, `templateId`

**응답 코드**: `200` 성공 | `403` admin 필요

---

### POST /api/prompt-lab/experiments/{id}/run

실험을 비동기로 실행합니다. `PENDING` 상태인 실험이어야 합니다.

**인증**: Admin 필수

**응답 `202 Accepted`**: `{ "status": "RUNNING", "experimentId": "uuid" }`

**응답 코드**: `202` 시작됨 | `400` PENDING 상태 아님 | `429` 최대 동시 실험 수 초과

---

### POST /api/prompt-lab/experiments/{id}/cancel

실행 중인 실험을 취소합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `400` RUNNING 상태 아님 | `403` admin 필요

---

### GET /api/prompt-lab/experiments/{id}/status

실험의 현재 실행 상태를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 없음

---

### GET /api/prompt-lab/experiments/{id}/trials

실험의 트라이얼 데이터를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

### GET /api/prompt-lab/experiments/{id}/report

완료된 실험의 분석 보고서를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 보고서 없음

---

### DELETE /api/prompt-lab/experiments/{id}

실험을 삭제합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

---

### POST /api/prompt-lab/experiments/{id}/activate

완료된 실험의 보고서에서 권장 버전을 활성화합니다.

**인증**: Admin 필수

**응답 `200 OK`**:
```json
{
  "activated": true,
  "templateId": "template-uuid",
  "versionId": "version-uuid",
  "versionNumber": 3
}
```

---

### POST /api/prompt-lab/auto-optimize

템플릿에 대한 전체 자동 최적화 파이프라인을 실행합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "templateId": "template-uuid",
  "candidateCount": 3,
  "judgeModel": null
}
```

**응답 `202 Accepted`**: `{ "status": "STARTED", "templateId": "...", "jobId": "..." }`

---

### POST /api/prompt-lab/analyze

템플릿의 피드백을 분석하여 현재 프롬프트의 약점을 파악합니다.

**인증**: Admin 필수

**요청 본문**: `{ "templateId": "template-uuid", "maxSamples": 50 }`

**응답 코드**: `200` 성공 | `403` admin 필요

---

## 감사 로그

기본 경로: `/api/admin/audits`

시스템 전체 admin 작업에 대한 통합 감사 로그.

### GET /api/admin/audits

admin 감사 로그를 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**:

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `limit` | integer | 100 | 최소 1, 최대 1000 |
| `category` | string | — | 카테고리 필터 (예: `mcp_server`, `tool_policy`) |
| `action` | string | — | 작업 필터 (예: `CREATE`, `UPDATE`, `DELETE`) |

**응답 `200 OK`**:
```json
[
  {
    "id": "uuid",
    "category": "mcp_server",
    "action": "CREATE",
    "actor": "admin-user-id",
    "resourceType": "mcp_server",
    "resourceId": "my-mcp-server",
    "detail": "transport=SSE, autoConnect=true",
    "createdAt": 1700000000000
  }
]
```

**알려진 카테고리**: `mcp_server`, `mcp_access_policy`, `tool_policy`, `rag_ingestion_policy`, `rag_ingestion_candidate`, `output_guard_rule`

**응답 코드**: `200` 성공 | `403` admin 필요

---

## 운영 대시보드

기본 경로: `/api/ops`

MCP 상태 요약 및 Micrometer 지표를 제공하는 운영 대시보드.

### GET /api/ops/dashboard

MCP 서버 상태와 선택된 지표를 포함한 운영 스냅샷을 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**: `names` (지표 이름 배열, 선택 사항)

**응답 `200 OK`**:
```json
{
  "generatedAt": 1700000000000,
  "ragEnabled": false,
  "mcp": {
    "total": 3,
    "statusCounts": {
      "CONNECTED": 2,
      "FAILED": 1
    }
  },
  "metrics": [
    {
      "name": "arc.agent.executions",
      "meterCount": 1,
      "measurements": {
        "count": 42.0
      }
    }
  ]
}
```

**응답 코드**: `200` 성공 | `403` admin 필요

---

### GET /api/ops/metrics/names

Micrometer에 등록된 모든 사용 가능한 지표 이름을 조회합니다.

**인증**: Admin 필수

**응답 `200 OK`**: 지표 이름 문자열 배열 (`arc.*`, `jvm.*`, `process.*`, `system.*`으로 필터링)

---

## 피드백

기본 경로: `/api/feedback`

> **활성화 조건**: `FeedbackStore` 빈이 있는 경우

에이전트 응답에 대한 사용자 피드백을 수집하고 분석합니다.

평가 값: `POSITIVE`, `NEGATIVE`, `NEUTRAL`

### POST /api/feedback

에이전트 응답에 대한 피드백을 제출합니다. `runId`가 제공되면 해당 실행의 메타데이터로 자동 보강됩니다.

**인증**: 공개

**요청 본문**:
```json
{
  "rating": "POSITIVE",
  "query": "반품 정책이 무엇인가요?",
  "response": "30일 이내 반품이 가능합니다...",
  "comment": "매우 도움이 됐습니다!",
  "runId": "run-uuid",
  "sessionId": "session-uuid"
}
```

`rating`만 필수입니다. 다른 모든 필드는 선택 사항입니다.

**응답 `201 Created`**: `FeedbackResponse` 객체

**응답 코드**: `201` 생성됨 | `400` 잘못된 rating 값

---

### GET /api/feedback

피드백 목록을 선택적 필터와 함께 조회합니다.

**인증**: Admin 필수

**쿼리 파라미터**: `rating`, `from` (ISO 8601), `to` (ISO 8601), `intent`, `sessionId`, `templateId`

**응답 코드**: `200` 성공 | `400` 잘못된 파라미터 | `403` admin 필요

---

### GET /api/feedback/export

모든 피드백을 eval-testing 스키마 형식으로 내보냅니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

### GET /api/feedback/{feedbackId}

ID로 단일 피드백 항목을 조회합니다.

**인증**: 공개

**응답 코드**: `200` 성공 | `404` 없음

---

### DELETE /api/feedback/{feedbackId}

피드백 항목을 삭제합니다.

**인증**: Admin 필수

**응답**: `204 No Content`

---

## Admin 모듈 (arc-admin)

arc-admin 모듈은 플랫폼 수준 관리 및 멀티테넌트 관리를 제공합니다. 모든 엔드포인트는 `arc.reactor.admin.enabled=true` 및 `DataSource` 빈이 필요합니다.

TenantAdminController의 엔드포인트는 `X-Tenant-Id` 요청 헤더에서 테넌트를 확인합니다.

---

### 지표 인제스션

기본 경로: `/api/admin/metrics/ingest`

> **활성화 조건**: `arc.reactor.admin.enabled=true`

MCP 서버 및 외부 소스에서 지표 이벤트를 수신합니다.

#### POST /api/admin/metrics/ingest/mcp-health

단일 MCP 서버 헬스 이벤트를 인제스트합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "tenantId": "tenant-abc",
  "serverName": "jira-server",
  "status": "CONNECTED",
  "responseTimeMs": 120,
  "toolCount": 12
}
```

**응답 `202 Accepted`**: `{ "status": "accepted" }`

**응답 코드**: `202` 수락됨 | `403` admin 필요 | `503` 버퍼 가득 참

---

#### POST /api/admin/metrics/ingest/tool-call

단일 도구 호출 이벤트를 인제스트합니다.

**인증**: Admin 필수

**응답 코드**: `202` 수락됨 | `403` admin 필요 | `503` 버퍼 가득 참

---

#### POST /api/admin/metrics/ingest/eval-result

단일 eval 결과 이벤트를 인제스트합니다.

**인증**: Admin 필수

**응답 코드**: `202` 수락됨 | `403` admin 필요 | `503` 버퍼 가득 참

---

#### POST /api/admin/metrics/ingest/eval-results

단일 eval 실행의 결과를 일괄 인제스트합니다. 배치당 최대 1000개.

**인증**: Admin 필수

**응답 `200 OK`**: `{ "evalRunId": "...", "accepted": 1, "dropped": 0 }`

**응답 코드**: `200` 요약 | `400` 빈 목록 또는 크기 초과 | `403` admin 필요

---

#### POST /api/admin/metrics/ingest/batch

MCP 헬스 이벤트를 일괄 인제스트합니다. 배치당 최대 1000개.

**인증**: Admin 필수

**응답 코드**: `200` 요약 | `400` 크기 초과 | `403` admin 필요

---

### 플랫폼 Admin

기본 경로: `/api/admin/platform`

> **활성화 조건**: `arc.reactor.admin.enabled=true` + DataSource

#### GET /api/admin/platform/health

플랫폼 헬스 대시보드를 조회합니다.

**인증**: Admin 필수

**응답 코드**: `200` 성공 | `403` admin 필요

---

#### GET /api/admin/platform/tenants

모든 테넌트를 조회합니다.

**인증**: Admin 필수

---

#### POST /api/admin/platform/tenants

새 테넌트를 생성합니다.

**인증**: Admin 필수

**요청 본문**:
```json
{
  "name": "Acme Corp",
  "slug": "acme-corp",
  "plan": "FREE"
}
```

| 필드 | 제약 조건 |
|------|---------|
| `name` | 공백 불가, 최대 200자 |
| `slug` | 2-50자, `^[a-z0-9][a-z0-9-]*[a-z0-9]$` 패턴 |
| `plan` | `FREE`, `PRO`, `ENTERPRISE` (대소문자 무시) |

**응답 코드**: `201` 생성됨 | `400` 잘못된 plan 또는 중복 슬러그 | `403` admin 필요

---

#### POST /api/admin/platform/tenants/{id}/suspend

테넌트를 정지합니다.

**인증**: Admin 필수

---

#### POST /api/admin/platform/tenants/{id}/activate

정지된 테넌트를 활성화합니다.

**인증**: Admin 필수

---

#### GET /api/admin/platform/tenants/analytics

모든 테넌트의 분석 요약을 조회합니다 (현재 월 사용량 및 할당량).

**인증**: Admin 필수

---

#### GET /api/admin/platform/pricing

모든 모델 요금 항목을 조회합니다.

**인증**: Admin 필수

---

#### POST /api/admin/platform/pricing

모델 요금 항목을 생성하거나 업데이트합니다 (upsert).

**인증**: Admin 필수

---

#### GET /api/admin/platform/alerts/rules

모든 알림 규칙을 조회합니다.

**인증**: Admin 필수

---

#### POST /api/admin/platform/alerts/rules

알림 규칙을 생성하거나 업데이트합니다 (upsert).

**인증**: Admin 필수

---

#### DELETE /api/admin/platform/alerts/rules/{id}

알림 규칙을 삭제합니다.

**인증**: Admin 필수

**응답 코드**: `204` 삭제됨 | `403` admin 필요 | `404` 없음

---

#### GET /api/admin/platform/alerts

현재 활성화된 모든 알림을 조회합니다 (플랫폼 전체).

**인증**: Admin 필수

---

#### POST /api/admin/platform/alerts/{id}/resolve

활성 알림을 해결합니다.

**인증**: Admin 필수

---

#### POST /api/admin/platform/alerts/evaluate

모든 테넌트에 대해 즉시 알림 규칙 평가를 트리거합니다.

**인증**: Admin 필수

**응답 `200 OK`**: `{ "status": "evaluation complete" }`

---

### 테넌트 Admin

기본 경로: `/api/admin/tenant`

> **활성화 조건**: `arc.reactor.admin.enabled=true` + DataSource

테넌트 범위 대시보드 및 내보내기. `X-Tenant-Id` 요청 헤더에서 테넌트를 확인합니다.

#### GET /api/admin/tenant/overview

테넌트 개요 대시보드를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 테넌트 없음

---

#### GET /api/admin/tenant/usage

테넌트 사용량 대시보드를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**쿼리 파라미터**: `fromMs` (epoch ms), `toMs` (epoch ms)

---

#### GET /api/admin/tenant/quality

테넌트 품질 대시보드를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

---

#### GET /api/admin/tenant/tools

테넌트 도구 대시보드를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

---

#### GET /api/admin/tenant/cost

테넌트 비용 대시보드를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

---

#### GET /api/admin/tenant/slo

테넌트 SLO(서비스 수준 목표) 상태를 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 테넌트 없음

---

#### GET /api/admin/tenant/alerts

해당 테넌트의 활성 알림을 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

---

#### GET /api/admin/tenant/quota

테넌트의 현재 월 할당량 사용량을 조회합니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**응답 `200 OK`**:
```json
{
  "quota": {
    "maxRequestsPerMonth": 10000,
    "maxTokensPerMonth": 5000000
  },
  "usage": {
    "requests": 3420,
    "tokens": 1230000,
    "costUsd": "1.234000"
  },
  "requestUsagePercent": 34.2,
  "tokenUsagePercent": 24.6
}
```

**응답 코드**: `200` 성공 | `403` admin 필요 | `404` 테넌트 없음

---

#### GET /api/admin/tenant/export/executions

실행 내역을 CSV로 내보냅니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**쿼리 파라미터**: `fromMs`, `toMs`

**응답**: CSV 파일 (Content-Disposition: attachment; filename=executions.csv)

---

#### GET /api/admin/tenant/export/tools

도구 호출 내역을 CSV로 내보냅니다.

**인증**: Admin 필수 | **헤더**: `X-Tenant-Id: <tenantId>`

**응답**: CSV 파일 (Content-Disposition: attachment; filename=tool_calls.csv)

---

## 오류 응답 참조

모든 오류 응답은 표준 형식을 사용합니다:

```json
{
  "error": "사람이 읽을 수 있는 오류 설명",
  "details": "선택적 추가 정보",
  "timestamp": "2026-02-28T12:00:00Z"
}
```

| HTTP 상태 | 발생 시점 |
|----------|---------|
| `400 Bad Request` | 유효성 실패, 잘못된 파라미터 값, 제약 조건 위반 |
| `401 Unauthorized` | 누락되거나 유효하지 않은 JWT (인증 활성화 시) |
| `403 Forbidden` | 비-admin 사용자가 admin 엔드포인트 접근 시도 |
| `404 Not Found` | 리소스 없음 |
| `409 Conflict` | 리소스 이미 존재 (중복 이름 또는 이메일) |
| `429 Too Many Requests` | 요청 속도 제한 초과 (로그인 엔드포인트 또는 동시 실험 한도) |
| `503 Service Unavailable` | 의존 서비스 사용 불가 (VectorStore 미설정, 지표 버퍼 가득 참) |
| `504 Gateway Timeout` | 업스트림 MCP 관리자 API 타임아웃 |

`403` 응답은 항상 본문을 포함합니다 (`"error": "Admin access required"`). 빈 응답을 반환하지 않습니다.
