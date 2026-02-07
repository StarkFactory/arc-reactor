# 멀티에이전트 가이드

## 멀티에이전트란?

하나의 AI 에이전트가 모든 일을 처리하는 대신, **여러 전문 에이전트가 협력**하는 구조입니다.

사람으로 비유하면:
- **싱글 에이전트** = 혼자서 상담도 하고, 주문도 처리하고, 환불도 하는 직원
- **멀티에이전트** = 상담 팀장이 요청을 받아서 주문팀/환불팀/배송팀에 전달하는 구조

## 왜 필요한가?

| 상황 | 싱글 에이전트 | 멀티에이전트 |
|------|-------------|------------|
| 도구가 20개 넘어감 | 혼란, 잘못된 도구 선택 | 에이전트별 3~5개씩 분리 |
| 서로 다른 전문성 필요 | 시스템 프롬프트가 비대해짐 | 에이전트별 전문 프롬프트 |
| 독립적 작업을 동시에 | 순차 실행, 느림 | 병렬 실행으로 빠름 |
| 작업 결과를 다음에 활용 | 프롬프트 엔지니어링 필요 | 자동 파이프라이닝 |

---

## 3가지 패턴

Arc Reactor는 세 가지 멀티에이전트 패턴을 지원합니다.

### 1. Sequential (순차 파이프라인)

```
[에이전트A] → 결과 → [에이전트B] → 결과 → [에이전트C] → 최종 결과
```

**A의 출력이 B의 입력이 되는** 체인 구조입니다.

**실제 예시 — 블로그 글 작성:**
1. 리서치 에이전트: "AI 트렌드 2026" 자료 조사 → 조사 결과 출력
2. 작성 에이전트: 조사 결과를 받아서 → 블로그 글 작성
3. 교정 에이전트: 블로그 글을 받아서 → 맞춤법/문체 교정

```kotlin
val result = MultiAgent.sequential()
    .node("researcher") {
        systemPrompt = "주어진 주제에 대해 핵심 트렌드를 조사하라"
    }
    .node("writer") {
        systemPrompt = "조사 결과를 바탕으로 블로그 글을 작성하라"
    }
    .node("editor") {
        systemPrompt = "글의 맞춤법과 문체를 교정하라"
    }
    .execute(command, agentFactory)
```

**동작 원리:**
- 첫 번째 노드는 사용자의 원래 입력(`userPrompt`)을 받음
- 두 번째 노드부터는 **이전 노드의 출력**이 `userPrompt`로 전달됨
- 중간에 실패하면 즉시 중단 (C는 실행되지 않음)

---

### 2. Parallel (병렬 실행)

```
         ┌→ [에이전트A] → 결과A ─┐
사용자 → ├→ [에이전트B] → 결과B ─┼→ 결과 병합 → 최종 결과
         └→ [에이전트C] → 결과C ─┘
```

**같은 입력을 여러 에이전트가 동시에** 처리하고, 결과를 합칩니다.

**실제 예시 — 코드 리뷰:**
- 보안 에이전트: SQL 인젝션, XSS 등 보안 취약점 분석
- 스타일 에이전트: 코딩 컨벤션, 네이밍 검사
- 로직 에이전트: 비즈니스 로직 정확성 검증

세 분석이 독립적이므로 **동시에 실행**하면 3배 빨라집니다.

```kotlin
val result = MultiAgent.parallel()
    .node("security") {
        systemPrompt = "코드의 보안 취약점을 분석하라"
    }
    .node("style") {
        systemPrompt = "코딩 컨벤션을 검사하라"
    }
    .node("logic") {
        systemPrompt = "비즈니스 로직을 검증하라"
    }
    .execute(command, agentFactory)

// result.finalResult.content → 세 결과가 합쳐진 문자열
```

**옵션:**
- `failFast = true`: 하나라도 실패하면 전체 실패
- `failFast = false` (기본): 성공한 결과만 모아서 반환
- `merger`: 결과를 합치는 방식 커스텀 가능

```kotlin
// 커스텀 결과 병합
val result = MultiAgent.parallel(
    merger = ResultMerger { results ->
        results.joinToString("\n---\n") { "${it.nodeName}: ${it.result.content}" }
    }
)
```

---

### 3. Supervisor (매니저-워커)

```
사용자: "주문 환불해주세요"
         ↓
[Supervisor 에이전트]          ← 매니저 역할
  "환불팀에 전달해야겠다"
         ↓ delegate_to_refund 도구 호출
[Refund 에이전트]              ← 워커 역할
  환불 정책 확인, 처리
         ↓ 결과 반환
[Supervisor 에이전트]
  "환불이 완료되었습니다" → 사용자에게 최종 응답
```

**매니저가 상황을 판단해서 적절한 워커에게 위임**하는 구조입니다.

**실제 예시 — 고객 상담 센터:**
- Supervisor: 고객 요청을 분석하고 적절한 팀에 전달
- 주문 워커: 주문 조회, 변경, 취소
- 환불 워커: 환불 신청, 상태 확인
- 배송 워커: 배송 추적, 주소 변경

```kotlin
val result = MultiAgent.supervisor()
    .node("order") {
        systemPrompt = "주문 관련 업무를 처리하라"
        description = "주문 조회, 변경, 취소"  // Supervisor가 이 설명을 보고 판단
    }
    .node("refund") {
        systemPrompt = "환불 업무를 처리하라"
        description = "환불 신청, 상태 확인"
    }
    .node("shipping") {
        systemPrompt = "배송 업무를 처리하라"
        description = "배송 추적, 주소 변경"
    }
    .execute(command, agentFactory)
```

---

## Supervisor 패턴 핵심 설계

이 부분이 Arc Reactor 멀티에이전트의 가장 중요한 설계입니다.

### 핵심 원칙: 기존 코드를 전혀 수정하지 않는다

`SpringAiAgentExecutor`는 이미 **ReAct 루프**를 가지고 있습니다:

```
사용자 입력 → LLM 호출 → 도구 호출 → LLM 호출 → 도구 호출 → ... → 최종 응답
```

Supervisor 패턴의 핵심 아이디어는:

> **워커 에이전트를 "도구"로 감싸면, 기존 ReAct 루프가 자연스럽게 워커를 호출한다.**

### WorkerAgentTool — 에이전트를 도구로 변환

```kotlin
class WorkerAgentTool(node, agentExecutor) : ToolCallback {
    name = "delegate_to_${node.name}"     // 예: "delegate_to_refund"
    description = "환불 업무를 처리하라"    // node.description

    fun call(arguments) {
        val instruction = arguments["instruction"]  // Supervisor가 전달한 지시
        val result = agentExecutor.execute(          // 워커 에이전트 실행
            systemPrompt = node.systemPrompt,
            userPrompt = instruction
        )
        return result.content  // 워커의 응답을 Supervisor에게 반환
    }
}
```

### 동작 흐름 (상세)

```
1. SupervisorOrchestrator.execute() 호출

2. 각 워커 노드를 WorkerAgentTool로 변환
   - AgentNode("order", ...) → WorkerAgentTool(name="delegate_to_order")
   - AgentNode("refund", ...) → WorkerAgentTool(name="delegate_to_refund")

3. Supervisor 에이전트 생성
   - 시스템 프롬프트: "적절한 워커에게 위임하라"
   - 도구 목록: [delegate_to_order, delegate_to_refund]  ← WorkerAgentTool들

4. Supervisor의 ReAct 루프 시작 (기존 SpringAiAgentExecutor 그대로!)
   → LLM: "환불 요청이니 refund에 위임해야겠다"
   → 도구 호출: delegate_to_refund(instruction="ORD-123 환불 처리")
     → WorkerAgentTool.call() 실행
       → refund 에이전트의 execute() 실행 (이것도 기존 executor 그대로!)
         → refund 에이전트가 자기 도구(checkOrder, processRefund)로 환불 처리
       → "환불 완료" 반환
   → LLM: "고객님, 환불이 완료되었습니다"
   → 최종 응답
```

### 왜 이 설계가 좋은가?

1. **기존 코드 수정 없음**: `SpringAiAgentExecutor`를 전혀 건드리지 않음
2. **자연스러운 통합**: ReAct 루프의 "도구 호출" 메커니즘을 그대로 활용
3. **재귀적 확장**: 워커 에이전트도 자기만의 도구를 가질 수 있음
4. **Supervisor의 판단**: LLM이 상황에 맞는 워커를 선택 (하드코딩 아님)

---

## 도구의 3가지 종류

에이전트가 사용하는 도구는 3가지가 있습니다. **에이전트 입장에서는 셋 다 똑같은 도구**입니다.
이름을 보고, 설명을 읽고, `call()`을 호출하면 결과가 돌아옵니다.

차이는 **안에서 뭐가 돌아가는지**입니다:

```
에이전트의 도구 목록:
  - calculator            ← 로컬 도구 (함수 1개 실행)
  - file_read             ← MCP 도구 (외부 서버에 요청)
  - delegate_to_refund    ← WorkerAgentTool (에이전트가 통째로 실행)
```

| | 로컬 도구 | MCP 도구 | WorkerAgentTool |
|---|---|---|---|
| **내부 동작** | 함수 1개 실행 | 외부 서버에 네트워크 요청 | 에이전트 통째로 실행 (자체 LLM + 도구) |
| **LLM 호출** | 없음 | 없음 | **있음** (자체 ReAct 루프) |
| **실행 위치** | 같은 프로세스 | 외부 MCP 서버 | 같은 프로세스 |
| **예시** | `3+5` 계산 → `"8"` | MCP서버에 파일 읽기 요청 → 파일 내용 | 환불 에이전트가 알아서 처리 → `"환불 완료"` |

**MCP는 "도구를 어디서 가져오느냐"** (로컬 vs 외부 서버)이고,
**WorkerAgentTool은 "도구 안에서 뭐가 돌아가느냐"** (단순 로직 vs LLM 에이전트)입니다.
서로 다른 축의 개념이며, 함께 조합할 수도 있습니다:

```
Supervisor 에이전트:
  도구: [MCP에서 가져온 도구들, WorkerAgentTool들]

Refund Worker 에이전트:
  도구: [MCP에서 가져온 결제 API 도구, 로컬 DB 조회 도구]
```

### LLM 호출 횟수 예시

Supervisor 패턴에서는 **LLM이 여러 번 호출**됩니다:

```
사용자: "주문 환불해주세요"

[Supervisor LLM 호출 #1]
  "delegate_to_refund를 호출하자"
      ↓
  [Refund Worker LLM 호출 #1] ← 별도 LLM 호출!
    → checkOrder 도구 사용
  [Refund Worker LLM 호출 #2] ← 별도 LLM 호출!
    → processRefund 도구 사용
  [Refund Worker LLM 호출 #3]
    → "주문 #1234 환불 완료"
      ↓ (이 문자열이 Supervisor에게 도구 결과로 돌아감)

[Supervisor LLM 호출 #2]
  → "고객님의 주문 #1234 환불이 완료되었습니다"
```

총 LLM 5번 호출. Supervisor 2번 + Worker 3번.
Supervisor는 Worker 내부에서 LLM이 몇 번 호출되는지 모릅니다.

---

## 자주 묻는 질문

### Q: WorkerAgentTool을 워커마다 새로 만들어야 하나?

**아닙니다.** `WorkerAgentTool`은 범용 래퍼 클래스 1개입니다. 인스턴스만 여러 개 만듭니다:

```kotlin
// 클래스는 1개, 인스턴스가 3개
WorkerAgentTool(refundNode, refundAgent)    // name = "delegate_to_refund"
WorkerAgentTool(orderNode, orderAgent)      // name = "delegate_to_order"
WorkerAgentTool(shippingNode, shippingAgent) // name = "delegate_to_shipping"
```

그리고 DSL 빌더를 쓰면 이것마저도 자동입니다:

```kotlin
// 개발자가 작성하는 코드 — 이게 전부
MultiAgent.supervisor()
    .node("refund") { systemPrompt = "환불 전문 에이전트" }
    .node("shipping") { systemPrompt = "배송 전문 에이전트" }
    .execute(command, agentFactory)

// 내부에서 자동으로 WorkerAgentTool 인스턴스를 생성하고 Supervisor에 등록합니다.
```

### Q: 사용자 API가 바뀌나?

**바뀌지 않습니다.** 사용자는 동일한 엔드포인트(`POST /api/chat`)에 요청하고, 단일 응답을 받습니다.
싱글 에이전트 → 멀티에이전트 전환은 순수하게 서버 내부 구현 변경입니다.

### Q: 어떤 패턴을 써야 하나?

- 작업 A의 결과가 B에 필요하면 → **Sequential**
- 독립적 작업을 빠르게 하고 싶으면 → **Parallel**
- 요청 종류에 따라 전문가가 다르면 → **Supervisor**

---

## AgentNode 설정

각 노드(에이전트)에 설정할 수 있는 항목:

```kotlin
AgentNode(
    name = "refund",                    // 필수: 에이전트 이름
    systemPrompt = "환불 처리하라",      // 필수: 에이전트의 역할/지시
    description = "환불 신청 담당",      // Supervisor가 워커를 선택할 때 참고
    tools = listOf(myTool),             // 이 에이전트가 사용할 도구들
    localTools = listOf(myLocalTool),   // @Tool 어노테이션 기반 도구
    maxToolCalls = 10                   // 도구 호출 최대 횟수
)
```

## agentFactory란?

`agentFactory`는 `AgentNode`를 받아서 `AgentExecutor`를 만드는 함수입니다.

```kotlin
// 기본 패턴: SpringAiAgentExecutor를 직접 생성
val result = MultiAgent.sequential()
    .node("A") { systemPrompt = "..." }
    .execute(command) { node ->
        SpringAiAgentExecutor(
            chatModel = chatModel,
            tools = node.tools,
            // ... 기타 설정
        )
    }

// Spring DI 패턴: 빈으로 등록된 빌더 활용
val result = MultiAgent.supervisor()
    .node("order") { systemPrompt = "주문 처리" }
    .node("refund") { systemPrompt = "환불 처리" }
    .execute(command) { node ->
        agentExecutorBuilder.build(node)  // 팩토리 메서드
    }
```

## MultiAgentResult

실행 결과에서 확인할 수 있는 정보:

```kotlin
val result: MultiAgentResult = orchestrator.execute(...)

result.success              // 전체 성공 여부
result.finalResult          // AgentResult (최종 응답)
result.finalResult.content  // 최종 응답 텍스트
result.nodeResults          // 각 노드별 결과 리스트
result.totalDurationMs      // 전체 실행 시간 (ms)

// 노드별 결과 확인
result.nodeResults.forEach { nodeResult ->
    nodeResult.nodeName     // 노드 이름
    nodeResult.result       // AgentResult
    nodeResult.durationMs   // 이 노드의 실행 시간
}
```

## 패턴 선택 가이드

| 상황 | 추천 패턴 |
|------|----------|
| 작업 A의 결과가 작업 B에 필요 | Sequential |
| 여러 분석을 동시에 수행 | Parallel |
| 사용자 요청에 따라 다른 전문가 필요 | Supervisor |
| 조사 → 작성 → 교정 파이프라인 | Sequential |
| 보안 + 스타일 + 로직 코드 리뷰 | Parallel |
| 고객 상담 센터 (주문/환불/배송) | Supervisor |
