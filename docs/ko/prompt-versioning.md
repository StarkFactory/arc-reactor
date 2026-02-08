# 프롬프트 버저닝

## 한 줄 요약

**AI Agent 개발자가 시스템 프롬프트를 버전 관리하고, 안전하게 배포·롤백하는 기능.**

---

## 왜 필요한가?

AI Agent를 운영하면 시스템 프롬프트를 계속 수정하게 된다:

```
v1: "친절하게 응대하라. 환불은 7일 이내."
  → 2주 운영 → 고객 피드백: "너무 딱딱해요"

v2: "공감하며 응대하라. 환불은 7일 이내. 이모지 사용."
  → 1주 운영 → 만족도 상승

v3: "공감하며 응대하라. 환불 14일로 변경."
  → 배포 → 환불 비용 폭증 → v2로 롤백하고 싶다
```

**기존 Persona만으로는:**
- 수정하면 이전 프롬프트가 사라진다
- 어떤 프롬프트가 언제 쓰였는지 알 수 없다
- 롤백하려면 이전 내용을 기억하거나 별도로 기록해야 한다
- "v2가 v1보다 나은가?" 비교할 근거가 없다

**프롬프트 버저닝이 해결하는 것:**
- 모든 버전 이력 보존 (수정 전 내용 절대 사라지지 않음)
- DRAFT → ACTIVE → ARCHIVED 상태 관리로 안전한 배포
- 어떤 버전이 어떤 응답을 만들었는지 metadata로 추적
- 한 번의 API 호출로 이전 버전 롤백

---

## 누구를 위한 기능인가?

| 역할 | 사용 여부 |
|------|----------|
| **AI Agent 개발자/운영자** | 이 기능의 주 사용자. 프롬프트를 만들고, 배포하고, 개선한다 |
| **End-user (채팅 사용자)** | 이 기능의 존재를 모른다. 그냥 채팅할 뿐이다 |

---

## Persona와 뭐가 다른가?

| | Persona | Prompt Versioning |
|---|---|---|
| **목적** | 역할 전환 ("상담원" ↔ "코드리뷰어") | 같은 역할의 프롬프트 개선·관리 |
| **사용자** | End-user가 역할을 선택 | 개발자가 프롬프트를 관리 |
| **버전** | 없음 (수정 = 덮어쓰기) | 전체 이력 보존 (v1, v2, v3...) |
| **상태** | 없음 | DRAFT → ACTIVE → ARCHIVED |
| **추적** | 없음 | 어떤 버전으로 응답했는지 기록 |
| **비유** | 폴더 (분류) | Git (변경 이력) |

둘은 독립적이다. 함께 쓸 수도 있고, 하나만 쓸 수도 있다.

---

## 핵심 개념

### PromptTemplate

프롬프트의 "이름표". 여러 버전을 담는 컨테이너.

```
PromptTemplate:
  id: "abc-123"
  name: "customer-support"      ← 고유 이름
  description: "고객 상담 봇"
```

### PromptVersion

실제 프롬프트 텍스트. 템플릿 안에 여러 개가 있다.

```
PromptVersion v1: "친절하게 응대하라."        → ARCHIVED
PromptVersion v2: "공감하며 응대하라."        → ACTIVE  ← 현재 사용 중
PromptVersion v3: "간결하게 응대하라."        → DRAFT   ← 아직 테스트 중
```

### VersionStatus (상태 흐름)

```
DRAFT  ──activate──→  ACTIVE  ──(새 버전 activate)──→  ARCHIVED
  │                                                        ↑
  └─────────────archive────────────────────────────────────┘
```

- **DRAFT**: 작성 중. 아직 프로덕션에서 안 쓰임
- **ACTIVE**: 현재 프로덕션에서 사용 중 (템플릿당 1개만)
- **ARCHIVED**: 더 이상 안 쓰지만, 이력으로 보존

---

## 사용 방법

### 1. 템플릿 생성

```bash
curl -X POST http://localhost:8080/api/prompt-templates \
  -H "Content-Type: application/json" \
  -d '{"name": "customer-support", "description": "고객 상담 봇"}'
```

### 2. 버전 작성 (DRAFT)

```bash
curl -X POST http://localhost:8080/api/prompt-templates/{id}/versions \
  -H "Content-Type: application/json" \
  -d '{
    "content": "당신은 친절한 고객 상담 에이전트입니다. 환불은 7일 이내만 가능합니다.",
    "changeLog": "최초 버전"
  }'
```

### 3. 테스트 후 활성화

```bash
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{vid}/activate
```

### 4. 채팅에서 사용

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "환불하고 싶어요", "promptTemplateId": "{id}"}'
```

ACTIVE 버전의 프롬프트가 자동으로 시스템 프롬프트로 사용된다.

### 5. 프롬프트 개선 → 새 버전

```bash
# v2 작성
curl -X POST http://localhost:8080/api/prompt-templates/{id}/versions \
  -H "Content-Type: application/json" \
  -d '{
    "content": "당신은 공감 능력이 뛰어난 고객 상담 에이전트입니다. 고객의 감정을 먼저 인정하고 답변하세요.",
    "changeLog": "공감 지시 추가"
  }'

# v2 활성화 (v1은 자동으로 ARCHIVED)
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{v2id}/activate
```

### 6. 문제 발생 시 롤백

```bash
# v1 다시 활성화 (v2는 자동으로 ARCHIVED)
curl -X PUT http://localhost:8080/api/prompt-templates/{id}/versions/{v1id}/activate
```

---

## 버전 추적

`promptTemplateId`로 채팅하면, 응답의 metadata에 자동으로 버전 정보가 기록된다:

```json
{
  "promptTemplateId": "abc-123",
  "promptVersionId": "v2-uuid",
  "promptVersion": 2
}
```

이걸로 나중에:
- "v1 시절 대화 만족도 vs v2 시절 만족도" 비교
- "이 이상한 답변은 어떤 프롬프트 버전으로 생성됐는가?" 추적
- 프롬프트 변경이 실제 결과에 미친 영향 분석

---

## 시스템 프롬프트 우선순위

ChatController에서 시스템 프롬프트는 이 순서로 결정된다:

```
1. personaId          → Persona 조회 (사용자가 역할 선택)
2. promptTemplateId   → ACTIVE 버전 조회 (개발자가 배포한 프롬프트)
3. systemPrompt       → 직접 지정 (request body에 프롬프트 텍스트 전달)
4. Default Persona    → PersonaStore의 기본 페르소나
5. Hardcoded fallback → "You are a helpful AI assistant."
```

---

## REST API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/prompt-templates` | 템플릿 목록 |
| POST | `/api/prompt-templates` | 템플릿 생성 |
| GET | `/api/prompt-templates/{id}` | 템플릿 상세 (버전 목록 포함) |
| PUT | `/api/prompt-templates/{id}` | 템플릿 수정 (이름, 설명) |
| DELETE | `/api/prompt-templates/{id}` | 템플릿 삭제 (모든 버전 함께 삭제) |
| POST | `/api/prompt-templates/{id}/versions` | 새 버전 생성 (DRAFT) |
| PUT | `/api/prompt-templates/{id}/versions/{vid}/activate` | 버전 활성화 |
| PUT | `/api/prompt-templates/{id}/versions/{vid}/archive` | 버전 아카이브 |

---

## 데이터 모델

### DB 스키마

```sql
-- 템플릿 (이름표)
prompt_templates (
    id          VARCHAR(36) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
)

-- 버전 (실제 프롬프트 내용)
prompt_versions (
    id          VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) FK → prompt_templates(id) ON DELETE CASCADE,
    version     INT,              -- 1, 2, 3... (자동 증가)
    content     TEXT,             -- 시스템 프롬프트 텍스트
    status      VARCHAR(20),      -- DRAFT / ACTIVE / ARCHIVED
    change_log  TEXT,
    created_at  TIMESTAMP,
    UNIQUE(template_id, version)
)
```

### Kotlin 클래스

- `PromptTemplate` — 템플릿 엔티티 (`com.arc.reactor.prompt`)
- `PromptVersion` — 버전 엔티티
- `VersionStatus` — enum (DRAFT, ACTIVE, ARCHIVED)
- `PromptTemplateStore` — 인터페이스 (InMemory / JDBC 구현)

---

## 참고 코드

- [`PromptModels.kt`](../../src/main/kotlin/com/arc/reactor/prompt/PromptModels.kt) — 데이터 클래스
- [`PromptTemplateStore.kt`](../../src/main/kotlin/com/arc/reactor/prompt/PromptTemplateStore.kt) — 인터페이스 + InMemory 구현
- [`JdbcPromptTemplateStore.kt`](../../src/main/kotlin/com/arc/reactor/prompt/JdbcPromptTemplateStore.kt) — PostgreSQL 구현
- [`PromptTemplateController.kt`](../../src/main/kotlin/com/arc/reactor/controller/PromptTemplateController.kt) — REST API
- [`V5__create_prompt_templates.sql`](../../src/main/resources/db/migration/V5__create_prompt_templates.sql) — DB 마이그레이션
