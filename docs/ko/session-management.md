# 세션 & 페르소나 관리

## 한 줄 요약

**세션 REST API로 대화 이력을 관리하고, 페르소나로 시스템 프롬프트를 중앙 관리한다.** 인증 활성 시 사용자별 세션 격리가 자동 적용.

---

## 세션 관리

### API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/sessions` | 세션 목록 조회 |
| `GET` | `/api/sessions/{id}` | 세션 상세 (전체 메시지) |
| `DELETE` | `/api/sessions/{id}` | 세션 삭제 |
| `GET` | `/api/models` | 사용 가능 LLM 모델 목록 |

### GET /api/sessions — 세션 목록

```bash
curl http://localhost:8080/api/sessions
```

```json
[
  {
    "sessionId": "a1b2c3d4-...",
    "messageCount": 12,
    "lastActivity": 1707350413500,
    "preview": "Python이란 뭐야?"
  },
  {
    "sessionId": "e5f6g7h8-...",
    "messageCount": 4,
    "lastActivity": 1707349800000,
    "preview": "3 + 5는 얼마야?"
  }
]
```

- `preview`: 첫 번째 user 메시지의 앞 50자
- `lastActivity`: 마지막 메시지의 epoch milliseconds
- 최근 활동순으로 정렬
- **인증 활성 시**: 현재 사용자의 세션만 반환

### GET /api/sessions/{id} — 세션 상세

```bash
curl http://localhost:8080/api/sessions/a1b2c3d4-...
```

```json
{
  "sessionId": "a1b2c3d4-...",
  "messages": [
    { "role": "user", "content": "안녕하세요", "timestamp": 1707350400000 },
    { "role": "assistant", "content": "안녕하세요! 무엇을 도와드릴까요?", "timestamp": 1707350402300 }
  ]
}
```

- **인증 활성 시**: 다른 사용자의 세션 접근 → 403 Forbidden

### DELETE /api/sessions/{id} — 세션 삭제

```bash
curl -X DELETE http://localhost:8080/api/sessions/a1b2c3d4-...
# → 204 No Content
```

- MemoryStore에서 세션 데이터 완전 삭제
- **인증 활성 시**: 다른 사용자의 세션 삭제 → 403 Forbidden

### GET /api/models — 모델 목록

```bash
curl http://localhost:8080/api/models
```

```json
{
  "models": [
    { "name": "gemini", "isDefault": true },
    { "name": "openai", "isDefault": false },
    { "name": "anthropic", "isDefault": false }
  ],
  "defaultModel": "gemini"
}
```

- `build.gradle.kts`에서 `implementation`으로 등록된 provider만 자동 감지
- `arc.reactor.llm.default-provider` 설정값이 `isDefault: true`로 표시

---

## 세션 아키텍처

### 데이터 흐름

```
[사용자가 메시지 입력]
     │
     ├─→ 프론트엔드: message를 session.messages[]에 추가 → localStorage 저장
     │
     └─→ POST /api/chat/stream { metadata: { sessionId: UUID } }
              │
              ├─→ ConversationManager.loadHistory(sessionId)
              │     → MemoryStore에서 이전 대화 로드
              │     → LLM에 컨텍스트로 전달
              │
              ├─→ LLM 실행 (컨텍스트 + 현재 메시지)
              │
              └─→ ConversationManager.saveHistory(sessionId, content)
                    → MemoryStore에 user + assistant 메시지 저장
```

### 이중 저장 구조

대화 데이터는 **두 곳에 독립적으로** 저장된다:

| 저장소 | 위치 | 내용 | 용도 |
|--------|------|------|------|
| **localStorage** | 브라우저 | 세션 목록 + 전체 메시지 + 메타데이터 | UI 표시, 세션 전환, 오프라인 캐시 |
| **MemoryStore** | 서버 | role + content + timestamp | LLM 대화 컨텍스트 |

- localStorage: `id`, `role`, `content`, `toolsUsed`, `error`, `timestamp`, `durationMs` (풍부한 메타데이터)
- MemoryStore: `role`, `content`, `timestamp` (LLM 컨텍스트에 필요한 최소 정보만)
- 두 저장소는 **동기화되지 않는다** — 각각 독립적으로 저장하고 읽는다

### MemoryStore 구현체

| | InMemoryMemoryStore | JdbcMemoryStore |
|---|---|---|
| **저장** | Caffeine 캐시 (메모리) | PostgreSQL (디스크) |
| **최대 세션** | 1,000 (LRU 자동 삭제) | 무제한 |
| **세션당 최대 메시지** | 50 | 100 |
| **초과 시** | FIFO (가장 오래된 삭제) | FIFO (DELETE + LIMIT) |
| **서버 재시작** | 유실 | 유지 |
| **자동 전환** | 기본 | DataSource 빈 감지 시 자동 활성화 |

### 세션 ID 전달 경로

```
프론트엔드                          백엔드
─────────                          ──────
ChatContext.tsx                     ChatController.kt
  activeSessionId ─────────────→     request.metadata["sessionId"]
  (UUID, localStorage)                    │
                                         ▼
                                  ConversationManager.kt
                                    command.metadata["sessionId"]
                                         │
                                         ▼
                                  MemoryStore.getOrCreate(sessionId)
```

---

## 사용자별 세션 격리

인증 활성 시 자동으로 작동한다.

### 백엔드 격리

```
[JwtAuthWebFilter]
  │ JWT → userId 추출 → exchange.attributes["userId"]
  ▼
[ChatController]
  │ resolveUserId(): exchange > request.userId > "anonymous"
  ▼
[ConversationManager]
  │ addMessage(sessionId, role, content, userId)
  ▼
[MemoryStore]
  │ sessionOwners[sessionId] = userId (최초 메시지 시 기록)
  ▼
[SessionController]
  │ listSessions: listSessionsByUserId(userId) → 해당 사용자 세션만
  │ getSession:   isSessionOwner() 확인 → 불일치 시 403
  │ deleteSession: isSessionOwner() 확인 → 불일치 시 403
```

소유권 판단 로직 (`isSessionOwner`):
- `owner == null` (기존 데이터, userId 미기록) → 접근 허용
- `owner == userId` → 접근 허용
- `owner == "anonymous"` → 접근 허용
- 그 외 → 403 Forbidden

### 프론트엔드 격리

- localStorage 키를 userId별로 분리: `arc-reactor-sessions:{userId}`, `arc-reactor-settings:{userId}`
- `ChatProvider key={user?.id}` — 사용자 변경 시 React 컨텍스트 전체 리마운트
- 로그인/로그아웃 시 세션 데이터 간섭 없음

---

## 페르소나 관리

페르소나는 **이름이 붙은 시스템 프롬프트 템플릿**이다. 관리자가 중앙에서 프롬프트를 관리하고, 사용자는 이름만 보고 선택한다.

### API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/personas` | 전체 목록 |
| `GET` | `/api/personas/{id}` | ID로 조회 |
| `POST` | `/api/personas` | 생성 |
| `PUT` | `/api/personas/{id}` | 수정 (부분 업데이트) |
| `DELETE` | `/api/personas/{id}` | 삭제 |

### GET /api/personas

```json
[
  {
    "id": "default",
    "name": "Default Assistant",
    "systemPrompt": "You are a helpful AI assistant...",
    "isDefault": true,
    "createdAt": 1707350400000,
    "updatedAt": 1707350400000
  },
  {
    "id": "550e8400-...",
    "name": "Python Expert",
    "systemPrompt": "You are an expert Python developer...",
    "isDefault": false,
    "createdAt": 1707350500000,
    "updatedAt": 1707350500000
  }
]
```

### POST /api/personas — 생성

```bash
curl -X POST http://localhost:8080/api/personas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Customer Support",
    "systemPrompt": "You are a customer support agent. Be polite and helpful.",
    "isDefault": false
  }'
```

### PUT /api/personas/{id} — 부분 수정

```bash
# name만 변경 (다른 필드는 유지)
curl -X PUT http://localhost:8080/api/personas/550e8400-... \
  -H "Content-Type: application/json" \
  -d '{ "name": "Python & JS Expert" }'
```

- `null`인 필드는 기존 값 유지 (부분 업데이트)
- `isDefault: true`로 설정하면 기존 default가 자동으로 해제

### DELETE /api/personas/{id}

```bash
curl -X DELETE http://localhost:8080/api/personas/550e8400-...
# → 204 No Content (없는 ID도 204)
```

### 시스템 프롬프트 결정 우선순위

ChatController에서 실제 시스템 프롬프트가 결정되는 순서:

```
1. request.personaId가 있으면 → PersonaStore에서 조회 → 해당 프롬프트 사용
2. request.systemPrompt가 있으면 → 직접 사용 (프론트에서 직접 입력한 경우)
3. PersonaStore에서 default 페르소나 조회 → 있으면 사용
4. 하드코딩 폴백: "You are a helpful AI assistant..."
```

### PersonaStore 구현체

| | InMemoryPersonaStore | JdbcPersonaStore |
|---|---|---|
| **저장** | ConcurrentHashMap | PostgreSQL `personas` 테이블 |
| **초기 데이터** | 코드에서 "Default Assistant" 생성 | Flyway V2에서 seed |
| **서버 재시작** | 유실 (기본 페르소나만 재생성) | 유지 |
| **자동 전환** | 기본 | DataSource 빈 감지 시 자동 활성화 |

### isDefault 단일 보장

`isDefault = true`인 페르소나는 **항상 최대 1개**:
- `save(persona)` 시 `isDefault = true`이면 기존 default를 먼저 해제
- `update()` 시 `isDefault = true`이면 기존 default를 먼저 해제

---

## DB 스키마

### personas 테이블 (Flyway V2)

```sql
CREATE TABLE IF NOT EXISTS personas (
    id            VARCHAR(36)   PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    system_prompt TEXT          NOT NULL,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 기본 페르소나 시드
INSERT INTO personas (id, name, system_prompt, is_default)
VALUES ('default', 'Default Assistant',
        'You are a helpful AI assistant. ...', TRUE);
```

### conversation_messages 테이블 (Flyway V1 + V4)

```sql
-- V1: 기본 테이블
CREATE TABLE conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL,    -- user, assistant, system, tool
    content     TEXT         NOT NULL,
    timestamp   BIGINT       NOT NULL,    -- epoch millis
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- V4: 사용자별 격리 (인증 활성 시)
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';
```

---

## 프론트엔드 연동

### 세션 관리 흐름

```
[앱 시작]
  │
  ├─→ localStorage에서 세션 목록 로드
  ├─→ GET /api/sessions로 서버 측 세션 확인
  │
  ▼
[세션 선택]
  │
  ├─→ activeSessionId 설정
  ├─→ localStorage에서 메시지 로드 (즉시 표시)
  │
  ▼
[메시지 전송]
  │
  ├─→ POST /api/chat/stream { metadata: { sessionId } }
  ├─→ 응답 수신 후 localStorage + UI 업데이트
  │
  ▼
[세션 삭제]
  │
  ├─→ DELETE /api/sessions/{id} (서버 측 정리)
  └─→ localStorage에서도 제거
```

### 페르소나 선택 (프론트엔드)

`SettingsPanel`의 `PersonaSelector` 컴포넌트:

1. 드롭다운에서 페르소나 목록 표시 (`GET /api/personas`)
2. 페르소나 선택 → `personaId`를 ChatRequest에 포함하여 전송
3. "Custom" 모드 선택 → `systemPrompt`를 직접 입력하여 전송
4. 인라인 CRUD — 드롭다운 내에서 페르소나 추가/수정/삭제 가능

---

## 참고 코드

- [`controller/SessionController.kt`](../../src/main/kotlin/com/arc/reactor/controller/SessionController.kt) — 세션/모델 API
- [`controller/PersonaController.kt`](../../src/main/kotlin/com/arc/reactor/controller/PersonaController.kt) — 페르소나 CRUD API
- [`controller/ChatController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ChatController.kt) — 시스템 프롬프트 결정 로직
- [`memory/ConversationMemory.kt`](../../src/main/kotlin/com/arc/reactor/memory/ConversationMemory.kt) — MemoryStore 인터페이스
- [`memory/ConversationManager.kt`](../../src/main/kotlin/com/arc/reactor/memory/ConversationManager.kt) — 대화 히스토리 생명주기
- [`persona/PersonaStore.kt`](../../src/main/kotlin/com/arc/reactor/persona/PersonaStore.kt) — PersonaStore 인터페이스 + InMemory 구현
- [`persona/JdbcPersonaStore.kt`](../../src/main/kotlin/com/arc/reactor/persona/JdbcPersonaStore.kt) — PostgreSQL 구현
- [`V1__create_conversation_messages.sql`](../../src/main/resources/db/migration/V1__create_conversation_messages.sql) — 대화 테이블
- [`V2__create_personas.sql`](../../src/main/resources/db/migration/V2__create_personas.sql) — 페르소나 테이블
