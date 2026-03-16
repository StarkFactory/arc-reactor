# 페르소나

> 이 문서는 Arc Reactor의 페르소나 시스템을 설명합니다 -- 이름이 있는 시스템 프롬프트 템플릿이 어떻게 관리, 결정, 에이전트 실행에 적용되는지를 다룹니다.

## 한 줄 요약

**이름이 지정된 시스템 프롬프트 템플릿(페르소나)을 관리하여, 사용자가 시스템 프롬프트를 직접 입력하는 대신 ID로 선택할 수 있도록 한다.**

---

## 왜 필요한가?

페르소나 없이는 모든 에이전트 요청에 전체 시스템 프롬프트를 제공해야 합니다:

```
POST /api/chat
{
  "systemPrompt": "당신은 고객 지원 에이전트입니다. 환불 정책을 엄격히 따르세요...",
  "userPrompt": "환불하고 싶어요"
}
```

문제점:
- **중복**: 동일한 시스템 프롬프트가 모든 클라이언트 연동에 복사됨
- **중앙 제어 불가**: 프롬프트 업데이트 시 모든 호출자에서 변경 필요
- **버전 관리 없음**: 연결된 프롬프트 템플릿이 업데이트되면 모든 호출자가 구버전 프롬프트 사용
- **메타데이터 없음**: 환영 메시지, 아이콘, 가이드라인을 프롬프트에 첨부할 방법 없음

페르소나 적용 후:

```
POST /api/chat
{
  "personaId": "support-agent",
  "userPrompt": "환불하고 싶어요"
}
```

시스템 프롬프트, 응답 가이드라인, 환영 메시지, 아이콘 모두 서버 측에서 결정됩니다.

---

## 아키텍처

```
클라이언트 요청 (personaId)
    │
    ▼
┌─ 시스템 프롬프트 결정 ──────────────────────────────────────────────┐
│                                                                     │
│  1. PersonaStore.get(personaId)                                     │
│     ├─ 발견? → Persona                                             │
│     └─ 미발견? → PersonaStore.getDefault()                         │
│                  ├─ 발견? → 기본 Persona                            │
│                  └─ 미발견? → 내장 폴백 프롬프트                      │
│                                                                     │
│  2. resolveEffectivePrompt(promptTemplateStore)                     │
│     ├─ promptTemplateId 설정됨?                                     │
│     │   ├─ YES → promptTemplateStore.getActiveVersion()             │
│     │   │        ├─ 발견? → 템플릿 내용을 기본으로 사용               │
│     │   │        └─ 미발견? → systemPrompt로 폴백                   │
│     │   └─ NO  → systemPrompt를 기본으로 사용                       │
│     │                                                               │
│     └─ responseGuideline 설정됨?                                    │
│         ├─ YES → base + "\n\n" + responseGuideline                 │
│         └─ NO  → base                                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
  Agent Executor (결정된 시스템 프롬프트 사용)
```

**핵심 설계 원칙**:
- 최대 하나의 페르소나만 **기본** 페르소나(`isDefault = true`)일 수 있습니다. 새 기본값을 설정하면 이전 기본값이 자동으로 해제됩니다.
- 프롬프트 결정은 **안전 우선**: 연결된 프롬프트 템플릿 조회가 실패하면 페르소나 자체의 `systemPrompt`가 폴백으로 사용됩니다.
- 페르소나는 **활성/비활성** 상태를 가집니다. `isActive` 플래그가 삭제 없이 가시성을 제어합니다.

---

## 데이터 모델

```kotlin
data class Persona(
    val id: String,              // UUID 또는 "default"
    val name: String,            // 표시 이름 (예: "고객 지원 에이전트")
    val systemPrompt: String,    // 실제 시스템 프롬프트 텍스트
    val isDefault: Boolean,      // 최대 하나만 기본값
    val description: String?,    // 관리자 UI용 간단 설명
    val responseGuideline: String?, // systemPrompt에 추가됨
    val welcomeMessage: String?, // 페르소나 선택 시 초기 인사 메시지
    val icon: String?,           // UI용 이모지 또는 짧은 아이콘 식별자
    val isActive: Boolean,       // 선택 가능 여부
    val promptTemplateId: String?, // 선택적 연결 버전 관리 프롬프트 템플릿
    val createdAt: Instant,
    val updatedAt: Instant
)
```

### 시스템 프롬프트 조합

유효 시스템 프롬프트는 `resolveEffectivePrompt()`로 구성됩니다:

1. **기본 프롬프트**: `promptTemplateId`가 설정되어 있고 `PromptTemplateStore`가 사용 가능하면, 연결된 템플릿의 활성 버전이 사용됩니다. 그렇지 않으면 `systemPrompt`가 기본입니다.
2. **응답 가이드라인**: `responseGuideline`이 비어있지 않으면, 이중 줄바꿈 후에 추가됩니다.

```
[템플릿 또는 systemPrompt의 기본 프롬프트]

[responseGuideline]
```

---

## 저장소 구현

### InMemoryPersonaStore (기본)

- `ConcurrentHashMap` + `synchronized` 블록으로 스레드 안전성 보장
- 생성 시 "Default Assistant" 페르소나 사전 로드
- 비영속 -- 서버 재시작 시 데이터 소실

### JdbcPersonaStore (PostgreSQL)

- 서버 재시작 간 영속성 보장
- 데이터베이스 트랜잭션을 통한 단일 기본 페르소나 강제
- 부분 유니크 인덱스로 데이터베이스 수준에서 최대 하나의 기본값 보장
- `TransactionTemplate`을 사용한 원자적 기본값 전환 연산

---

## 기본 페르소나

최대 하나의 페르소나만 `isDefault = true`일 수 있습니다. 이 불변식은 두 수준에서 강제됩니다:

1. **애플리케이션 수준**: `InMemoryPersonaStore`와 `JdbcPersonaStore` 모두 새 기본값을 설정하기 전에 기존 기본값을 해제합니다.
2. **데이터베이스 수준**: `is_default WHERE is_default = TRUE` 부분 유니크 인덱스가 동시 위반을 방지합니다.

요청에 `personaId`가 지정되지 않으면, 스케줄러와 에이전트 실행기는 기본 페르소나의 결정된 프롬프트로 폴백합니다.

---

## 에이전트 실행과의 통합

페르소나는 두 가지 컨텍스트에서 사용됩니다:

### 1. 직접 채팅 (ChatController)

호출자가 `personaId`를 제공합니다. 컨트롤러가 유효 시스템 프롬프트를 결정하고 `AgentCommand.systemPrompt`에 전달합니다.

### 2. 스케줄러 (DynamicSchedulerService)

AGENT 모드 스케줄 작업에서 시스템 프롬프트 결정 우선순위:

1. `agentSystemPrompt` (작업에 명시적 오버라이드)
2. 작업의 `personaId` --> `PersonaStore.get(personaId).resolveEffectivePrompt()`
3. 기본 페르소나 --> `PersonaStore.getDefault().resolveEffectivePrompt()`
4. 내장 폴백 프롬프트

---

## REST API

모든 쓰기 엔드포인트는 ADMIN 역할이 필요합니다. 기본 경로: `/api/personas`

### 페르소나 목록 조회
```
GET /api/personas?activeOnly=false
```

### 페르소나 조회
```
GET /api/personas/{personaId}
```

### 페르소나 생성
```
POST /api/personas
Content-Type: application/json

{
  "name": "고객 지원 에이전트",
  "systemPrompt": "당신은 고객 지원 에이전트입니다. 환불 정책을 엄격히 따르세요...",
  "isDefault": false,
  "description": "고객 문의 및 환불 요청 처리",
  "responseGuideline": "항상 친절한 톤으로 응답하세요. 가능하면 주문 번호를 포함하세요.",
  "welcomeMessage": "안녕하세요! 어떤 도움이 필요하신가요?",
  "icon": "headset",
  "promptTemplateId": "support-prompt-v2",
  "isActive": true
}
```

응답: `201 Created`

### 페르소나 수정 (부분)
```
PUT /api/personas/{personaId}
Content-Type: application/json

{
  "responseGuideline": "업데이트: 공식적이고 간결하게.",
  "isDefault": true
}
```

제공된 필드만 변경됩니다. `isDefault: true` 설정 시 이전 기본값이 해제됩니다.

nullable 필드를 비우려면 빈 문자열을 전송합니다: `"description": ""`.

### 페르소나 삭제
```
DELETE /api/personas/{personaId}
```

응답: `204 No Content` (멱등)

---

## 설정

페르소나에는 전용 설정 블록이 없습니다. `PersonaStore` 빈이 등록되면 항상 사용 가능합니다.

`InMemoryPersonaStore`가 기본으로 자동 구성됩니다. PostgreSQL과 `JdbcTemplate`이 사용 가능하면 `JdbcPersonaStore`가 `@Primary`로 등록됩니다.

---

## 데이터베이스 스키마

### `personas` (V2 + V8 + V29 + V33)

```sql
CREATE TABLE personas (
    id                 VARCHAR(36)   PRIMARY KEY,
    name               VARCHAR(200)  NOT NULL,
    system_prompt      TEXT          NOT NULL,
    is_default         BOOLEAN       NOT NULL DEFAULT FALSE,
    description        TEXT,
    response_guideline TEXT,
    welcome_message    TEXT,
    icon               VARCHAR(20),
    is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
    prompt_template_id VARCHAR(36),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 최대 하나의 기본 페르소나
CREATE UNIQUE INDEX idx_personas_single_default
    ON personas (is_default)
    WHERE is_default = TRUE;
```

시드 데이터:
```sql
INSERT INTO personas (id, name, system_prompt, is_default)
VALUES ('default', 'Default Assistant', '...', TRUE);
```

---

## 주의사항

1. **기본 페르소나 전환은 트랜잭션**: 페르소나 A에 `isDefault: true`를 설정하면 이전 기본값의 `isDefault`가 해제됩니다. 트랜잭션이 중간에 실패하면 이전 기본값이 유지됩니다.
2. **프롬프트 템플릿 폴백**: `promptTemplateId`가 삭제되거나 비활성화된 템플릿을 가리키면, 페르소나 자체의 `systemPrompt`로 조용히 폴백됩니다 (경고 로그 출력).
3. **nullable 필드 비우기**: `description`, `responseGuideline`, `welcomeMessage`, `icon`, `promptTemplateId`를 비우려면 빈 문자열 `""`을 전송하세요. `null`(또는 필드 생략)은 "변경 없음"을 의미합니다.
4. **활성 vs 삭제**: 데이터를 유지하면서 선택 목록에서 숨기려면 `isActive = false`를 사용하세요. 영구 제거에만 DELETE를 사용하세요.

---

## 주요 파일

| 파일 | 역할 |
|------|------|
| `persona/PersonaStore.kt` | Persona 데이터 클래스, PersonaStore 인터페이스, InMemoryPersonaStore, resolveEffectivePrompt() |
| `persona/JdbcPersonaStore.kt` | 트랜잭션 기본값 강제를 포함한 JDBC 구현 |
| `controller/PersonaController.kt` | 페르소나 CRUD REST API |
| `scheduler/DynamicSchedulerService.kt` | AGENT 모드 시스템 프롬프트 결정에 PersonaStore 사용 |
