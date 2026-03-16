# 피드백과 평가

> 이 문서는 Arc Reactor의 피드백 시스템을 설명합니다 -- 사용자 피드백이 어떻게 수집되고, 실행 메타데이터로 자동 보강되며, 오프라인 평가용으로 내보내지는지를 다룹니다.

## 한 줄 요약

**에이전트 응답에 대한 좋아요/싫어요 피드백을 수집하고, 실행 메타데이터로 자동 보강하며, eval-testing 스키마 형식으로 내보낸다.**

---

## 왜 필요한가?

피드백 시스템 없이는 에이전트 품질을 측정할 구조화된 방법이 없습니다:

```
사용자: "이 답변 틀렸어요"     →  대화 이력 속에 묻힘
사용자: "좋은 답변이에요!"     →  어디에도 기록 없음
```

문제점:
- **품질 신호 없음**: 어떤 응답이 좋고 나쁜지 알 수 없음
- **평가 데이터 없음**: 오프라인 평가를 위한 구조화된 데이터셋 부재
- **메타데이터 상관관계 없음**: 피드백을 특정 모델, 프롬프트, 사용된 도구와 연결 불가
- **수동 데이터 수집**: 평가 데이터셋 구축에 수작업 필요

피드백 시스템 적용 후:

```
POST /api/feedback  →  실행 메타데이터와 함께 저장  →  GET /api/feedback/export  →  평가 파이프라인
```

---

## 아키텍처

```
에이전트 실행
    │
    ▼
┌─ FeedbackMetadataCaptureHook (order=250) ──────────────────────────┐
│  AfterAgentComplete:                                                │
│    실행 메타데이터를 메모리에 캐시:                                    │
│    - runId, userId, userPrompt, agentResponse                      │
│    - toolsUsed, durationMs, sessionId, templateId                  │
│    TTL: 1시간, 최대: 10,000개 항목                                   │
└─────────────────────────────────────────────────────────────────────┘
    │
    │  (사용자가 1시간 이내에 피드백 제출)
    │
    ▼
┌─ FeedbackController ───────────────────────────────────────────────┐
│  POST /api/feedback                                                 │
│    1. rating 파싱 (THUMBS_UP / THUMBS_DOWN)                        │
│    2. runId 제공 시 → metadataCaptureHook.get(runId)               │
│    3. 자동 보강: 명시적 값 > 캐시 메타데이터 > 빈값                    │
│    4. feedbackStore.save(feedback)                                  │
│    5. 201 Created 반환                                              │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ FeedbackStore ────────────────────────────────────────────────────┐
│  InMemoryFeedbackStore (기본)                                       │
│    └─ ConcurrentHashMap, timestamp 내림차순 정렬                    │
│  JdbcFeedbackStore (PostgreSQL)                                     │
│    └─ feedback 테이블, 필터용 동적 WHERE 절                          │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
  GET /api/feedback/export  →  Eval-testing 스키마 JSON
```

---

## 데이터 모델

```kotlin
data class Feedback(
    val feedbackId: String,      // UUID
    val query: String,           // 사용자의 원본 프롬프트
    val response: String,        // 에이전트 응답
    val rating: FeedbackRating,  // THUMBS_UP 또는 THUMBS_DOWN
    val timestamp: Instant,      // 제출 시각
    val comment: String?,        // 자유 텍스트 코멘트
    val sessionId: String?,      // 대화 세션 ID
    val runId: String?,          // 에이전트 실행 run ID
    val userId: String?,         // 제출한 사용자
    val intent: String?,         // 분류된 인텐트
    val domain: String?,         // 비즈니스 도메인 (예: "order", "refund")
    val model: String?,          // 사용된 LLM 모델
    val promptVersion: Int?,     // 프롬프트 템플릿 버전 번호
    val toolsUsed: List<String>?, // 실행 중 호출된 도구 목록
    val durationMs: Long?,       // 총 실행 시간 (밀리초)
    val tags: List<String>?,     // 필터링용 임의 태그
    val templateId: String?      // 프롬프트 템플릿 ID
)

enum class FeedbackRating {
    THUMBS_UP, THUMBS_DOWN
}
```

---

## 자동 보강

사용자가 `runId`와 함께 피드백을 제출하면, 컨트롤러가 `FeedbackMetadataCaptureHook`에서 캐시된 실행 메타데이터로 피드백을 자동 보강합니다.

### 보강 우선순위

각 필드에 다음 우선순위가 적용됩니다:

1. **명시적 요청 값** (비어있지 않음) -- 항상 우선
2. **캐시된 메타데이터** (훅에서) -- 요청 값이 비어있거나 null일 때 사용
3. **빈 기본값** -- 둘 다 사용 불가할 때 사용

캐시에서 보강되는 필드:
- `query` (`userPrompt`에서)
- `response` (`agentResponse`에서)
- `toolsUsed`
- `durationMs`
- `sessionId`
- `templateId`

### FeedbackMetadataCaptureHook

이 `AfterAgentCompleteHook`은 order 250(웹훅 이후)에서 실행되며 실행 메타데이터를 메모리에 캐시합니다:

- **TTL**: 1시간 (이보다 오래된 항목은 삭제됨)
- **최대 항목**: 10,000개 (초과 시 가장 오래된 것부터 삭제)
- **삭제 조절**: 최대 30초에 한 번만 수행
- **Fail-open**: 에이전트 응답 전달을 절대 차단하지 않음

---

## 저장소 구현

### InMemoryFeedbackStore (기본)

- `ConcurrentHashMap`으로 스레드 안전성 보장
- 모든 필터 조합 지원 (rating, 시간 범위, intent, sessionId, templateId)
- 비영속 -- 서버 재시작 시 데이터 소실

### JdbcFeedbackStore (PostgreSQL)

- `feedback` 테이블에 영속 저장
- 결합 필터를 위한 동적 WHERE 절 구성
- 리스트 필드(`toolsUsed`, `tags`)는 JSON TEXT 컬럼으로 저장
- Flyway 마이그레이션: V17 (기본 테이블) + V24 (templateId 컬럼)

---

## REST API

### 피드백 제출 (모든 사용자)
```
POST /api/feedback
Content-Type: application/json

{
  "rating": "thumbs_up",
  "query": "비밀번호를 어떻게 재설정하나요?",
  "response": "설정 > 보안 > 비밀번호 재설정으로 이동하세요...",
  "comment": "매우 도움이 됐어요!",
  "runId": "run-abc-123",
  "sessionId": "session-xyz",
  "intent": "account_help",
  "domain": "account",
  "tags": ["helpful", "accurate"]
}
```

응답: `201 Created`

최소 제출 (runId를 통한 자동 보강):
```json
{
  "rating": "thumbs_down",
  "runId": "run-abc-123",
  "comment": "답변이 잘못됐어요"
}
```

### 피드백 목록 조회 (Admin)
```
GET /api/feedback?rating=thumbs_down&from=2024-01-01T00:00:00Z&to=2024-12-31T23:59:59Z&intent=refund&offset=0&limit=50
```

모든 필터 파라미터는 선택 사항이며 AND 조합됩니다:
- `rating`: `thumbs_up` 또는 `thumbs_down`
- `from` / `to`: ISO 8601 타임스탬프
- `intent`: 정확 매칭
- `sessionId`: 정확 매칭
- `templateId`: 정확 매칭

### 평가용 내보내기 (Admin)
```
GET /api/feedback/export
```

응답:
```json
{
  "version": 1,
  "exportedAt": "2024-12-25T09:00:00Z",
  "source": "arc-reactor",
  "items": [
    {
      "feedbackId": "...",
      "query": "...",
      "response": "...",
      "rating": "thumbs_up",
      "timestamp": "...",
      "comment": "...",
      "toolsUsed": ["searchDocs", "summarize"],
      "durationMs": 3500,
      "templateId": "support-v2"
    }
  ]
}
```

이 형식은 eval-testing `schemas/feedback.schema.json` 데이터 계약을 준수합니다.

### 피드백 조회 (모든 사용자)
```
GET /api/feedback/{feedbackId}
```

### 피드백 삭제 (Admin)
```
DELETE /api/feedback/{feedbackId}
```

응답: `204 No Content`

---

## PromptLab 평가와의 통합

피드백 내보내기 엔드포인트는 PromptLab 평가 파이프라인에서 직접 소비할 수 있는 데이터를 생성합니다:

```
피드백 내보내기  →  eval-testing 스키마  →  PromptLab 평가 실행기
                                              │
                                              ├─ 모델 A vs B 비교
                                              ├─ 프롬프트 v1 vs v2 비교
                                              └─ 시간 경과에 따른 품질 추적
```

평가를 위한 핵심 필드:
- `templateId` + `promptVersion`: 어떤 프롬프트가 응답을 생성했는지
- `model`: 어떤 LLM이 사용되었는지
- `rating`: 실제 품질 신호
- `toolsUsed`: 어떤 도구가 호출되었는지
- `durationMs`: 성능 지표

---

## 설정

피드백 시스템에는 전용 설정 블록이 없습니다. `FeedbackStore` 빈이 등록되면 활성화됩니다.

`InMemoryFeedbackStore`가 기본으로 자동 구성됩니다. PostgreSQL과 `JdbcTemplate`이 사용 가능하면 `JdbcFeedbackStore`가 등록됩니다.

`FeedbackController`는 `@ConditionalOnBean(FeedbackStore::class)`로 조건부 활성화됩니다.

---

## 데이터베이스 스키마

### `feedback` (V17 + V24)

```sql
CREATE TABLE feedback (
    feedback_id    VARCHAR(36)   PRIMARY KEY,
    query          TEXT          NOT NULL,
    response       TEXT          NOT NULL,
    rating         VARCHAR(20)   NOT NULL,
    timestamp      TIMESTAMP     NOT NULL,
    comment        TEXT,
    session_id     VARCHAR(255),
    run_id         VARCHAR(36),
    user_id        VARCHAR(255),
    intent         VARCHAR(50),
    domain         VARCHAR(50),
    model          VARCHAR(100),
    prompt_version INTEGER,
    tools_used     TEXT,          -- JSON 배열
    duration_ms    BIGINT,
    tags           TEXT,          -- JSON 배열
    template_id    VARCHAR(255)
);

CREATE INDEX idx_feedback_rating      ON feedback (rating);
CREATE INDEX idx_feedback_timestamp   ON feedback (timestamp);
CREATE INDEX idx_feedback_session_id  ON feedback (session_id);
CREATE INDEX idx_feedback_run_id      ON feedback (run_id);
CREATE INDEX idx_feedback_template_id ON feedback (template_id);
```

---

## 주의사항

1. **자동 보강 TTL**: 메타데이터는 1시간 동안 캐시됩니다. 이 기간 이후 제출된 피드백은 자동 보강되지 않으므로 -- 클라이언트가 `query`와 `response`를 명시적으로 제공해야 합니다.
2. **Rating 형식**: API는 대소문자 구분 없는 문자열을 허용합니다 (`thumbs_up`, `THUMBS_UP`, `Thumbs_Up`). 유효하지 않은 값은 `400 Bad Request`를 반환합니다.
3. **JSON 리스트 컬럼**: `tools_used`와 `tags`는 PostgreSQL에서 JSON TEXT로 저장됩니다. 읽기 시 역직렬화되며 -- 잘못된 JSON은 조용히 `null`을 반환합니다.
4. **내보내기 크기**: 내보내기 엔드포인트는 모든 피드백 항목을 반환합니다. 대규모 데이터셋의 경우 페이지네이션이 있는 필터 목록 엔드포인트 사용을 고려하세요.
5. **ConditionalOnBean**: `FeedbackController`는 `FeedbackStore` 빈이 존재할 때만 등록됩니다. `/api/feedback`에서 404가 발생하면 저장소 빈 구성을 확인하세요.

---

## 주요 파일

| 파일 | 역할 |
|------|------|
| `feedback/FeedbackModels.kt` | Feedback 데이터 클래스, FeedbackRating enum |
| `feedback/FeedbackStore.kt` | FeedbackStore 인터페이스, InMemoryFeedbackStore |
| `feedback/JdbcFeedbackStore.kt` | 동적 필터링을 포함한 JDBC 구현 |
| `hook/impl/FeedbackMetadataCaptureHook.kt` | 자동 보강 캐시용 AfterAgentCompleteHook |
| `controller/FeedbackController.kt` | 피드백 제출, 목록, 내보내기, 삭제 REST API |
