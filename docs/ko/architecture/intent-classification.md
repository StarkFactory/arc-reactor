# Intent 분류 시스템

> 이 문서는 Arc Reactor의 Intent 분류 시스템을 설명합니다 — 사용자 입력을 분석하고, 등록된 인텐트에 매칭하고, 에이전트 파이프라인을 동적으로 설정하는 방법입니다.

## 한 줄 요약

**사용자 입력을 분류하고, 인텐트별 파이프라인 오버라이드(모델, 도구, 프롬프트)를 에이전트 실행 전에 적용한다.**

---

## 왜 필요한가?

인텐트 분류 없이는 모든 요청이 동일한 에이전트 설정을 사용합니다:

```
사용자: "안녕하세요"           → model=openai, maxToolCalls=10, 전체 시스템프롬프트
사용자: "주문 환불해주세요"     → model=openai, maxToolCalls=10, 전체 시스템프롬프트
사용자: "Q4 매출 분석해줘"     → model=openai, maxToolCalls=10, 전체 시스템프롬프트
```

문제점:
- **토큰 낭비**: 단순 인사에 비싼 모델이나 도구가 필요 없음
- **전문성 부재**: 환불 요청에는 환불 전용 프롬프트가 필요, 범용 프롬프트가 아니라
- **도구 범위 미지정**: 모든 요청이 전체 도구를 봐서 LLM 혼란 증가

인텐트 분류 적용 후:

```
사용자: "안녕하세요"           → model=gemini, maxToolCalls=0   (저렴, 도구 없음)
사용자: "주문 환불해주세요"     → model=openai, maxToolCalls=5,  systemPrompt="환불 전문가..."
사용자: "Q4 매출 분석해줘"     → model=openai, maxToolCalls=10, tools=[analyzeData, generateReport]
```

---

## 아키텍처

```
사용자 입력
    │
    ▼
┌─ CompositeIntentClassifier ─────────────────────────────────────┐
│                                                                  │
│  1. RuleBasedIntentClassifier (토큰 0)                          │
│     └─ 키워드 매치? 신뢰도 >= 0.8?                               │
│         ├─ YES → 결과 반환 (LLM 생략)                           │
│         └─ NO  → LLM으로 폴백                                   │
│                                                                  │
│  2. LlmIntentClassifier (~200-500 토큰)                         │
│     └─ LLM에 간결한 프롬프트 전송                                 │
│         ├─ 성공 → 분류된 인텐트 반환                              │
│         └─ 실패 → 규칙 결과로 폴백                                │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ IntentResolver ────────────────────────────────────────────────┐
│  1. 신뢰도 >= 임계값 (기본 0.6) 확인                              │
│  2. 레지스트리에서 IntentDefinition 조회                          │
│  3. 프로필 병합 (멀티 인텐트 도구 병합)                            │
│  4. 프로필이 포함된 ResolvedIntent 반환                           │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ ChatController ────────────────────────────────────────────────┐
│  AgentCommand에 프로필 오버라이드 적용:                            │
│    command.copy(model=..., systemPrompt=..., maxToolCalls=...)  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
  Agent Executor (변경 없음)
```

**핵심 설계 원칙**: 인텐트 시스템은 **절대 요청을 차단하지 않습니다**. 분류 실패나 낮은 신뢰도 = 기본 파이프라인이 평소대로 실행됩니다.
- 인텐트 해석은 **안전 우선**: 예외 시 원본 command로 폴백합니다 (`BlockedIntentException` 제외).
- 차단된 인텐트는 `GUARD_REJECTED` 에러 코드를 반환합니다.

---

## 고급 규칙 기반 분류

규칙 기반 분류기는 단순 키워드 매칭 외에 세 가지 고급 기능을 지원합니다:

### 동의어 (Synonyms)

각 키워드를 대체 형태로 매핑합니다. 동의어 매칭은 원본 키워드의 매칭으로 처리됩니다 (이중 카운팅 없음).

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund", "cancel"),
    synonyms = mapOf(
        "refund" to listOf("리펀드", "돌려줘"),
        "cancel" to listOf("캔슬", "취소")
    )
)
```

입력 `"리펀드 해주세요"`는 동의어를 통해 `"refund"` 키워드에 매칭됩니다.

### 키워드 가중치 (Keyword Weights)

더 구별력 있는 키워드에 높은 가중치를 부여합니다. 기본 가중치는 `1.0`입니다.

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund", "order"),
    keywordWeights = mapOf("refund" to 3.0)
    // "refund" weight=3.0, "order" weight=1.0 (기본값)
)
```

`"refund"`만 매칭될 경우: confidence = 3.0 / 4.0 = **0.75** (동일 가중치면 0.5).

### 부정 키워드 (Negative Keywords)

매칭 시 해당 인텐트를 즉시 제외하는 구문입니다. 유사한 인텐트를 구별하는 데 유용합니다.

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund"),
    negativeKeywords = listOf("refund policy")
)
```

입력 `"Tell me about refund policy"`는 refund 인텐트에서 제외되어 FAQ 인텐트가 대신 매칭됩니다.

### 스코어링 알고리즘

```
scoreIntent(intent, text):
  1. negativeKeyword가 텍스트에 매칭? → 제외 (null 반환)
  2. 각 키워드에 대해:
     - variants = [keyword] + synonyms[keyword]
     - weight = keywordWeights[keyword] ?: 1.0
     - variant 중 하나라도 매칭? → matchedWeight += weight
     - totalWeight += weight
  3. confidence = matchedWeight / totalWeight (최대 1.0)
```

---

## 차단된 인텐트 (Blocked Intents)

특정 인텐트를 실행기 수준에서 차단할 수 있습니다. 차단된 인텐트는 `GUARD_REJECTED` 에러 코드를 반환합니다.

```yaml
arc:
  reactor:
    intent:
      blocked-intents: refund, data_analysis
```

정의를 제거하지 않고 특정 인텐트 경로를 일시적으로 비활성화하는 데 유용합니다.

---

## 설정

```yaml
arc:
  reactor:
    intent:
      enabled: true                    # 기본: false (opt-in)
      confidence-threshold: 0.6        # 프로필 적용 최소 신뢰도
      llm-model: gemini                # 분류용 LLM (null = 기본 제공자)
      rule-confidence-threshold: 0.8   # LLM 폴백 없이 규칙 사용 최소 신뢰도
      max-examples-per-intent: 3       # LLM 프롬프트 내 Few-shot 예시 수
      max-conversation-turns: 2        # 대화 컨텍스트 (2턴 = 4개 메시지)
```

---

## REST API

### 인텐트 목록 조회
```
GET /api/intents
```

### 인텐트 조회
```
GET /api/intents/{name}
```

### 인텐트 생성
```
POST /api/intents
Content-Type: application/json

{
  "name": "refund",
  "description": "환불 요청, 반품 처리",
  "examples": ["환불 신청하고 싶어요", "주문 취소하고 환불해주세요"],
  "keywords": ["환불", "반품"],
  "profile": {
    "systemPrompt": "환불 전문가로서 환불 정책을 엄격히 따릅니다.",
    "maxToolCalls": 5,
    "allowedTools": ["checkOrder", "processRefund"]
  }
}
```

### 인텐트 수정
```
PUT /api/intents/{name}
```

### 인텐트 삭제
```
DELETE /api/intents/{name}
```

---

## 도구 허용 목록 (`allowedTools`)

`profile.allowedTools`가 설정되어 있으면, 목록에 없는 tool call은 실행 단계에서 차단되고 LLM에는 에러 tool response로 반환됩니다.

---

## 주요 파일

| 파일 | 역할 |
|------|------|
| `intent/model/IntentModels.kt` | 데이터 클래스: IntentDefinition, IntentProfile, IntentResult |
| `intent/IntentClassifier.kt` | 분류기 인터페이스 |
| `intent/IntentRegistry.kt` | 레지스트리 인터페이스 + InMemoryIntentRegistry |
| `intent/IntentResolver.kt` | 분류 + 프로필 적용 오케스트레이션 |
| `intent/impl/RuleBasedIntentClassifier.kt` | 키워드 매칭 (토큰 0) |
| `intent/impl/LlmIntentClassifier.kt` | LLM 기반 분류 |
| `intent/impl/CompositeIntentClassifier.kt` | 규칙 → LLM 캐스케이딩 |
| `intent/impl/JdbcIntentRegistry.kt` | 영속 레지스트리 (JDBC) |
| `controller/IntentController.kt` | 인텐트 CRUD REST API |
