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
