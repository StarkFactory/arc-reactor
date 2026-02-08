# Arc Reactor 기능 인벤토리 & 데이터 아키텍처

> 마지막 업데이트: 2026-02-08

---

## 1. 전체 기능 매트릭스

### 1.1 백엔드 (arc-reactor)

| 기능 | API 엔드포인트 | 상태 | 설명 |
|------|---------------|------|------|
| **일반 채팅** | `POST /api/chat` | 활성 | 전체 응답 한 번에 반환 |
| **스트리밍 채팅** | `POST /api/chat/stream` | 활성 | SSE 실시간 토큰 스트리밍 |
| **모델 선택** | `ChatRequest.model` | 활성 | gemini/openai/anthropic/vertex 런타임 전환 |
| **시스템 프롬프트** | `ChatRequest.systemPrompt` | 활성 | 요청별 커스텀 시스템 프롬프트 |
| **응답 포맷** | `ChatRequest.responseFormat` | 활성 | TEXT/JSON 모드 |
| **대화 메모리** | `metadata.sessionId` → MemoryStore | 활성 | 세션별 대화 이력 자동 저장/로드 |
| **Guard 파이프라인** | 내부 (API 없음) | 활성 | Rate Limit → Input Validation → Injection Detection → Classification → Permission |
| **Hook 시스템** | 내부 (API 없음) | 활성 | BeforeAgentStart → BeforeToolCall → AfterToolCall → AfterAgentComplete |
| **도구 실행** | 내부 (API 없음) | 활성 | 로컬 도구 + MCP 도구 자동 발견 |
| **RAG 파이프라인** | 내부 (API 없음) | 비활성 (설정 가능) | VectorStore 연결 시 자동 활성화 |
| **멀티에이전트** | `POST /api/multi/*` | **비활성** | @RestController 주석 처리됨 |
| **세션 목록 조회** | 없음 | **미구현** | 세션 목록을 가져오는 API 없음 |
| **대화 이력 조회** | 없음 | **미구현** | 특정 세션의 이력을 가져오는 API 없음 |
| **세션 삭제** | 없음 | **미구현** | 서버에서 세션을 삭제하는 API 없음 |
| **사용 가능 모델 조회** | 없음 | **미구현** | 동적 모델 목록 API 없음 |

### 1.2 프론트엔드 (arc-reactor-web)

| 기능 | 상태 | 데이터 저장 | 서버 연동 |
|------|------|------------|----------|
| **멀티세션 관리** | 구현 완료 | localStorage | sessionId를 metadata로 전송 |
| **대화 이력** | 구현 완료 | localStorage | 서버에서 로드하지 않음 |
| **모델 선택** | 구현 완료 | localStorage | `model` 필드로 전송 |
| **시스템 프롬프트** | 구현 완료 | localStorage | `systemPrompt` 필드로 전송 |
| **응답 포맷** | 구현 완료 | localStorage | `responseFormat` 필드로 전송 |
| **다크/라이트 모드** | 구현 완료 | localStorage | 클라이언트 전용 |
| **마크다운 렌더링** | 구현 완료 | — | — |
| **코드 구문 강조** | 구현 완료 | — | — |
| **메시지 복사** | 구현 완료 | — | — |
| **재시도** | 구현 완료 | — | — |
| **도구 사용 표시** | 구현 완료 | — | SSE `tool_start`/`tool_end` 이벤트 |
| **응답 시간 표시** | 구현 완료 | — | 프론트에서 측정 |
| **사용자 인증** | **미구현** | — | userId `'web-user'` 하드코딩 |

---

## 2. 데이터 아키텍처

### 2.1 데이터 흐름도

```
┌─────────────────────────────────────────────────────────────────┐
│                          브라우저                                 │
│                                                                  │
│  localStorage                                                    │
│  ├── arc-reactor-sessions    (Session[] — 최대 50개)             │
│  │   ├── session.id          (UUID)                              │
│  │   ├── session.title       (첫 메시지 30자)                    │
│  │   ├── session.messages[]  (전체 대화 이력)                    │
│  │   └── session.updatedAt   (타임스탬프)                        │
│  └── arc-reactor-settings    (ChatSettings)                      │
│      ├── model, systemPrompt, responseFormat                     │
│      ├── darkMode, showMetadata                                  │
│      └── sidebarOpen                                             │
│                                                                  │
│  POST /api/chat/stream ──────────────────────┐                   │
│  { message, userId, model, systemPrompt,     │                   │
│    responseFormat, metadata: { sessionId } }  │                   │
└───────────────────────────────────────────────┼──────────────────┘
                                                │
                                                ▼
┌───────────────────────────────────────────────────────────────────┐
│                      백엔드 (Spring Boot)                         │
│                                                                   │
│  Guard 파이프라인 ─→ Hook ─→ ConversationManager ─→ ReAct Loop    │
│                              │                                    │
│                              ▼                                    │
│                    ┌─────────────────────┐                        │
│                    │    MemoryStore      │                        │
│                    │  (세션별 대화 저장)  │                        │
│                    └────────┬────────────┘                        │
│                             │                                     │
│              ┌──────────────┼──────────────┐                      │
│              ▼                              ▼                     │
│   InMemoryMemoryStore              JdbcMemoryStore                │
│   (Caffeine 캐시)                  (PostgreSQL)                   │
│   - 최대 1000 세션                 - conversation_messages 테이블  │
│   - 세션당 50 메시지               - 세션당 100 메시지             │
│   - 서버 재시작 시 유실            - 영구 저장                     │
│   - LRU 자동 삭제                  - TTL 기반 정리                 │
└───────────────────────────────────────────────────────────────────┘
```

### 2.2 데이터 이중 저장 구조

현재 대화 데이터는 **두 곳에 독립적으로** 저장된다:

| 저장소 | 위치 | 내용 | 영속성 | 용도 |
|--------|------|------|--------|------|
| **localStorage** | 브라우저 | 세션 목록 + 전체 메시지 | 브라우저 데이터 삭제 시 유실 | UI 표시, 세션 전환 |
| **MemoryStore** | 서버 메모리 또는 DB | 세션별 메시지 (role + content) | 설정에 따라 다름 | LLM 대화 컨텍스트 |

**핵심 차이점:**
- localStorage의 메시지: `id`, `role`, `content`, `toolsUsed`, `error`, `timestamp`, `durationMs` (풍부한 메타데이터)
- MemoryStore의 메시지: `role`, `content`, `timestamp` (LLM 컨텍스트에 필요한 최소 정보만)

두 저장소는 **동기화되지 않는다.** 각각 독립적으로 저장하고 읽는다.

---

## 3. 세션 아키텍처

### 3.1 세션 생명주기

```
[사용자가 "새 대화" 클릭]
    │
    ▼
브라우저: Session { id: UUID, title: "새 대화", messages: [] }
    → localStorage에 저장
    │
    ▼
[사용자가 메시지 입력]
    │
    ├─→ 브라우저: message를 session.messages[]에 추가 → localStorage 저장
    │
    └─→ 서버: POST /api/chat/stream { metadata: { sessionId: UUID } }
             │
             ├─→ ConversationManager.loadHistory(sessionId)
             │     → MemoryStore에서 이전 대화 로드 (있으면)
             │     → LLM에 컨텍스트로 전달
             │
             ├─→ LLM 실행 (이전 대화 컨텍스트 + 현재 메시지)
             │
             └─→ ConversationManager.saveStreamingHistory(sessionId, content)
                   → MemoryStore에 user + assistant 메시지 저장
```

### 3.2 세션 ID 전달 경로

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

### 3.3 세션 영속성 비교

| 시나리오 | localStorage (브라우저) | InMemoryMemoryStore (서버) | JdbcMemoryStore (DB) |
|----------|----------------------|--------------------------|---------------------|
| 페이지 새로고침 | **유지** | **유지** | **유지** |
| 브라우저 탭 닫기 | **유지** | **유지** | **유지** |
| 브라우저 데이터 삭제 | **유실** | **유지** | **유지** |
| 서버 재시작 | **유지** | **유실** | **유지** |
| Docker 재배포 | **유지** | **유실** | Volume이면 **유지** |
| 다른 브라우저/기기 | **유실** | **유지** (sessionId 알면) | **유지** (sessionId 알면) |

**현재 배포 상태 (arc-reactor-web docker-compose):**
- PostgreSQL **미연결** → `InMemoryMemoryStore` 사용
- 서버 재시작 시 서버 측 대화 컨텍스트 **유실**
- 브라우저 localStorage의 UI 이력은 유지되지만, 서버는 이전 대화를 모름

---

## 4. DB 스키마 (PostgreSQL 연결 시)

### 4.1 conversation_messages 테이블

```sql
-- Flyway V1__create_conversation_messages.sql
CREATE TABLE conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(255)  NOT NULL,    -- 세션 UUID
    role        VARCHAR(20)   NOT NULL,    -- user, assistant, system, tool
    content     TEXT          NOT NULL,    -- 메시지 본문
    timestamp   BIGINT        NOT NULL,    -- epoch millis
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스
CREATE INDEX idx_conversation_messages_session_id
    ON conversation_messages (session_id);

CREATE INDEX idx_conversation_messages_session_timestamp
    ON conversation_messages (session_id, timestamp);
```

### 4.2 데이터 예시

PostgreSQL 연결 시 저장되는 데이터:

| id | session_id | role | content | timestamp | created_at |
|----|-----------|------|---------|-----------|------------|
| 1 | a1b2c3d4-... | user | 안녕하세요 | 1707350400000 | 2026-02-08 12:00:00 |
| 2 | a1b2c3d4-... | assistant | 안녕하세요! 무엇을 도와드릴까요? | 1707350402300 | 2026-02-08 12:00:02 |
| 3 | a1b2c3d4-... | user | Python이란 뭐야? | 1707350410000 | 2026-02-08 12:00:10 |
| 4 | a1b2c3d4-... | assistant | Python은 프로그래밍 언어로... | 1707350413500 | 2026-02-08 12:00:13 |

### 4.3 MemoryStore 제한/정리 정책

| 설정 | InMemory (기본) | JDBC (PostgreSQL) |
|------|----------------|-------------------|
| 최대 세션 수 | 1,000 (Caffeine LRU) | 무제한 (디스크) |
| 세션당 최대 메시지 | 50 | 100 |
| 초과 시 동작 | FIFO (가장 오래된 메시지 삭제) | FIFO (DELETE + LIMIT) |
| 세션 만료 | LRU 자동 삭제 | `cleanupExpiredSessions(ttlMs)` 수동 호출 |
| 토큰 제한 로드 | `getHistoryWithinTokenLimit(maxTokens)` | 동일 |

---

## 5. 배포 구성별 차이

### 5.1 arc-reactor-web/docker-compose.yml (현재 사용 중)

```yaml
services:
  backend:    # arc-reactor (GEMINI_API_KEY만 설정)
  web:        # nginx + React 빌드
# PostgreSQL 없음 → InMemoryMemoryStore
```

- **장점:** 간단, 빠른 시작
- **단점:** 서버 재시작 시 대화 컨텍스트 유실

### 5.2 arc-reactor/docker-compose.yml (PostgreSQL 포함)

```yaml
services:
  app:        # arc-reactor (DB 연결 설정 포함)
  db:         # PostgreSQL 16 Alpine
# JdbcMemoryStore 자동 활성화 → 영구 저장
```

- **장점:** 대화 이력 영구 보존, 서버 재시작 후에도 컨텍스트 유지
- **단점:** DB 관리 필요

### 5.3 PostgreSQL 활성화 방법

arc-reactor-web의 docker-compose에 DB 서비스를 추가하거나, 환경 변수 설정:

```bash
# 방법 1: 외부 PostgreSQL에 연결
SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/arcreactor \
SPRING_DATASOURCE_USERNAME=arc \
SPRING_DATASOURCE_PASSWORD=arc \
docker compose up -d

# 방법 2: arc-reactor/docker-compose.yml 사용 (DB 포함)
cd arc-reactor && docker compose up -d
```

`JdbcMemoryStore`는 `DataSource` 빈이 존재하면 **자동 활성화**된다 (`@ConditionalOnClass` + `@ConditionalOnBean`).

---

## 6. ChatRequest 필드 전체 명세

프론트엔드가 백엔드에 보내는 요청:

```json
{
  "message": "사용자 입력 텍스트 (필수, NotBlank)",
  "model": "gemini | openai | anthropic | vertex | null (서버 기본값)",
  "systemPrompt": "커스텀 시스템 프롬프트 | null (기본 프롬프트)",
  "userId": "web-user (현재 하드코딩)",
  "metadata": {
    "sessionId": "UUID (세션 식별자)"
  },
  "responseFormat": "TEXT | JSON | null (기본: TEXT)",
  "responseSchema": "JSON 스키마 문자열 | null"
}
```

### 각 필드의 역할

| 필드 | 프론트 전송 여부 | 백엔드 사용처 |
|------|----------------|-------------|
| `message` | **항상** | LLM에 userPrompt로 전달 |
| `model` | 설정 시 | `ChatModelProvider`에서 해당 provider의 ChatModel 선택 |
| `systemPrompt` | 설정 시 | LLM 시스템 프롬프트 (기본: "You are a helpful AI assistant...") |
| `userId` | **항상** (`'web-user'`) | Guard rate limit 키, 로그 식별자 |
| `metadata.sessionId` | **항상** | `ConversationManager`에서 대화 이력 로드/저장 |
| `responseFormat` | TEXT 아닐 때 | 응답 형식 (JSON 모드 시 시스템 프롬프트에 JSON 지시 추가) |
| `responseSchema` | 미사용 | JSON 모드 시 스키마 강제 |

---

## 7. Guard 파이프라인 상세

요청이 LLM에 도달하기 전에 5단계 검증을 거친다:

```
요청 → [1. Rate Limit] → [2. Input Validation] → [3. Injection Detection]
                       → [4. Classification] → [5. Permission] → 허용/거부
```

| 단계 | 기본 구현 | 기본 설정 | 커스텀 가능 |
|------|---------|---------|-----------|
| Rate Limit | `DefaultRateLimitStage` | 분당 20회, 시간당 200회 | application.yml |
| Input Validation | `DefaultInputValidationStage` | 최대 10,000자 | application.yml |
| Injection Detection | `DefaultInjectionDetectionStage` | 활성 (regex 기반) | application.yml |
| Classification | 미구현 (인터페이스만) | — | `@Component`로 추가 |
| Permission | 미구현 (인터페이스만) | — | `@Component`로 추가 |

---

## 8. Hook 시스템 상세

에이전트 실행 생명주기의 4개 확장 포인트:

```
[BeforeAgentStart] → [에이전트 루프] → [BeforeToolCall → AfterToolCall]* → [AfterAgentComplete]
```

| Hook | 시점 | 용도 예시 | 실패 정책 |
|------|------|---------|----------|
| `BeforeAgentStartHook` | 실행 시작 전 | 인증, 예산 확인, 메타데이터 보강 | Fail-open (기본) |
| `BeforeToolCallHook` | 각 도구 호출 전 | 파라미터 검증, 위험 작업 승인 | Fail-open (기본) |
| `AfterToolCallHook` | 각 도구 호출 후 | 로깅, 메트릭, 알림 | Fail-open (기본) |
| `AfterAgentCompleteHook` | 실행 완료 후 | 감사 로그, 과금, 정리 | Fail-open (기본) |

`failOnError: true` 설정 시 해당 Hook 에러가 실행을 중단시킨다.

---

## 9. 설정 가능한 전체 속성 (`arc.reactor.*`)

```yaml
arc:
  reactor:
    max-tool-calls: 10              # ReAct 루프 최대 도구 호출 횟수
    max-tools-per-request: 20       # 요청당 최대 도구 수

    llm:
      default-provider: gemini      # 기본 LLM 프로바이더
      temperature: 0.7              # 창의성 (0.0~1.0)
      max-output-tokens: 4096       # 최대 응답 길이
      max-conversation-turns: 10    # 대화 이력 최대 턴 수
      max-context-window-tokens: 128000  # 컨텍스트 윈도우 토큰 한도

    retry:
      max-attempts: 3               # LLM 재시도 횟수
      initial-delay-ms: 1000        # 초기 지연 (ms)
      multiplier: 2.0               # 지수 백오프 배수
      max-delay-ms: 10000           # 최대 지연 (ms)

    concurrency:
      max-concurrent-requests: 20   # 최대 동시 요청
      request-timeout-ms: 30000     # 요청 타임아웃 (30초)

    guard:
      enabled: true                 # Guard 활성화
      rate-limit-per-minute: 20     # 분당 요청 한도
      rate-limit-per-hour: 200      # 시간당 요청 한도
      max-input-length: 10000       # 최대 입력 길이 (자)
      injection-detection-enabled: true  # 프롬프트 인젝션 탐지

    rag:
      enabled: false                # RAG 파이프라인 활성화
      similarity-threshold: 0.7     # 유사도 임계값
      top-k: 10                     # 검색 문서 수
      rerank-enabled: true          # Re-ranking 활성화
      max-context-tokens: 4000      # RAG 컨텍스트 최대 토큰
```

---

## 10. 현재 한계점 & 알려진 제약

### 10.1 세션 관리

| 문제 | 영향 | 심각도 |
|------|------|--------|
| 세션 목록/이력 조회 API 없음 | 다른 기기에서 대화 이어가기 불가 | 중간 |
| 세션 삭제 API 없음 | 서버 측 대화 데이터 정리 불가 | 낮음 |
| userId 하드코딩 (`web-user`) | 모든 사용자가 동일한 rate limit 공유 | 중간 |
| localStorage ↔ MemoryStore 미동기화 | 브라우저 데이터 삭제 시 UI 이력 유실 (서버에는 있을 수 있음) | 낮음 |

### 10.2 배포

| 문제 | 영향 | 심각도 |
|------|------|--------|
| arc-reactor-web docker-compose에 DB 없음 | 서버 재시작 시 대화 컨텍스트 유실 | 높음 |
| 모델 목록 하드코딩 | 새 모델 추가 시 프론트엔드 재배포 필요 | 낮음 |

### 10.3 프레임워크

| 제약 | 이유 | 대안 |
|------|------|------|
| MDC + 코루틴 = 로그 상관관계 불안정 | ThreadLocal 기반 MDC, 코루틴은 스레드 전환 | kotlinx-coroutines-slf4j 추가 |
| `ArcToolCallbackAdapter`의 `runBlocking` | Spring AI 인터페이스가 동기식 | Spring AI 인터페이스 제약 |
| 도구 성공 판단이 `startsWith("Error:")` | 프레임워크 수준의 한계 | 커스텀 ToolResultParser 구현 |

---

## 11. 자동 구성 빈 목록

모든 빈은 `@ConditionalOnMissingBean`으로 등록되어 사용자가 동일 타입 빈을 등록하면 오버라이드된다.

| 빈 | 기본 구현 | 조건 | 오버라이드 방법 |
|----|---------|------|--------------|
| `ToolSelector` | `AllToolSelector` | 항상 | `@Bean` 등록 |
| `MemoryStore` | `InMemoryMemoryStore` | DataSource 없을 때 | DataSource 추가 → 자동 전환 |
| `MemoryStore` | `JdbcMemoryStore` | DataSource + JdbcTemplate 있을 때 | `@Bean` 등록 |
| `ConversationManager` | `DefaultConversationManager` | 항상 | `@Bean` 등록 |
| `TokenEstimator` | `DefaultTokenEstimator` | 항상 | `@Bean` 등록 |
| `RequestGuard` | Guard 파이프라인 | `guard.enabled: true` | `@Bean` 등록 |
| `HookExecutor` | `DefaultHookExecutor` | 항상 | `@Component` Hook 추가 |
| `McpManager` | `DefaultMcpManager` | 항상 | `@Bean` 등록 |
| `ChatModelProvider` | 자동 발견 | ChatModel 빈 있을 때 | `@Bean` 등록 |
| `AgentExecutor` | `SpringAiAgentExecutor` | 항상 | `@Bean` 등록 |
| `AgentMetrics` | `NoOpAgentMetrics` | 항상 | `@Bean` 등록 |
| `ErrorMessageResolver` | `DefaultErrorMessageResolver` | 항상 | `@Bean` 등록 |

---

## 12. Fork 후 커스터마이즈 가이드

arc-reactor를 fork하여 사용할 때 수정이 필요한 부분:

### 12.1 필수 설정

```bash
# .env 파일
GEMINI_API_KEY=your-key          # 또는 다른 프로바이더 키
```

### 12.2 도구 추가

```kotlin
@Component  // 주석 해제하여 활성화
class MyCustomTool : LocalTool {
    @Tool(description = "도구 설명")
    suspend fun execute(param: String): String {
        return "결과"
    }
}
```

### 12.3 PostgreSQL 연결

```yaml
# application.yml 또는 환경 변수
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: arc
  flyway:
    enabled: true  # 자동 마이그레이션
```

### 12.4 Guard 커스터마이즈

```kotlin
@Component
class MyPermissionStage : GuardStage {
    override val order = 500
    override suspend fun evaluate(context: GuardContext): GuardResult {
        // 커스텀 권한 검사
        return GuardResult.Allowed
    }
}
```

### 12.5 Hook 추가

```kotlin
@Component
class MyAuditHook : AfterAgentCompleteHook {
    override val order = 200
    override suspend fun execute(context: HookContext, result: AgentResult) {
        // 감사 로그, 과금 등
    }
}
```
