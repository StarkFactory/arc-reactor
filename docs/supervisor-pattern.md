# Supervisor 패턴 Deep Dive

## 한 줄 요약

**매니저 에이전트가 상황을 판단해서, 전문 워커 에이전트에게 작업을 위임하는 구조.**

---

## 왜 필요한가?

싱글 에이전트에게 모든 도구를 주면 문제가 생긴다:

```
싱글 에이전트의 도구 목록 (20개+):
  checkOrder, cancelOrder, modifyOrder,
  processRefund, refundStatus, refundPolicy,
  trackShipping, changeAddress, deliverySchedule,
  getBalance, chargeCard, issueCredit,
  ...
```

- LLM이 도구를 잘못 선택할 확률이 높아진다
- 시스템 프롬프트가 비대해진다 ("주문은 이렇게, 환불은 이렇게, 배송은...")
- 한 영역의 변경이 다른 영역에 영향을 줄 수 있다

Supervisor 패턴은 이걸 해결한다:

```
[Supervisor] 도구 3개만 봄
  - delegate_to_order   → 주문 에이전트 (도구 3개)
  - delegate_to_refund  → 환불 에이전트 (도구 3개)
  - delegate_to_shipping → 배송 에이전트 (도구 3개)
```

각 에이전트가 **자기 전문 분야의 도구만** 갖고, **자기 역할에 집중하는 프롬프트**를 받는다.

---

## 핵심 트릭: WorkerAgentTool

Supervisor 패턴의 전부는 이 한 가지 아이디어다:

> **에이전트를 도구로 감싼다.**

### 일반 도구 vs WorkerAgentTool

```
[일반 도구 - CalculatorTool]
  call({expression: "3+5"})
  → 함수 실행
  → "8" 리턴

[WorkerAgentTool - delegate_to_refund]
  call({instruction: "주문 환불 처리해줘"})
  → 에이전트가 통째로 실행 (자체 LLM + 자체 도구 + 자체 ReAct 루프)
  → "환불 완료" 리턴
```

Supervisor 입장에서는 **둘 다 그냥 도구**다. 이름 보고, 설명 읽고, `call()` 호출하면 문자열이 돌아온다. 안에서 뭐가 돌아가는지 모른다.

### 왜 이게 좋은가?

**기존 코드를 한 줄도 수정하지 않는다.**

`SpringAiAgentExecutor`는 이미 ReAct 루프를 갖고 있다:
```
입력 → LLM → 도구 호출 → LLM → 도구 호출 → ... → 최종 응답
```

WorkerAgentTool은 이 "도구 호출" 자리에 에이전트를 끼워넣는 것이다.
executor 입장에서는 평소처럼 도구를 호출할 뿐이고, 그 도구가 내부에서 에이전트를 돌리는 건 executor가 알 바 아니다.

---

## 전체 동작 흐름

```
사용자: "주문 #1234 환불해주세요"
│
▼
[Supervisor 에이전트] ─── SpringAiAgentExecutor (기존 코드 그대로)
│  시스템 프롬프트: "적절한 워커에게 위임하라"
│  도구 목록:
│    - delegate_to_order     (WorkerAgentTool)
│    - delegate_to_refund    (WorkerAgentTool)
│    - delegate_to_shipping  (WorkerAgentTool)
│
│  [LLM 호출 #1] "환불 요청이니 refund에 위임하자"
│  → delegate_to_refund({instruction: "주문 #1234 환불 처리"})
│     │
│     ▼
│     [Refund 워커 에이전트] ─── SpringAiAgentExecutor (별도 인스턴스)
│     │  시스템 프롬프트: "환불 정책에 따라 처리하라"
│     │  도구: checkOrder, processRefund
│     │
│     │  [LLM 호출 #2] "먼저 주문을 확인하자"
│     │  → checkOrder({orderId: "1234"}) → "주문 존재, 결제 50,000원"
│     │
│     │  [LLM 호출 #3] "환불 처리하자"
│     │  → processRefund({orderId: "1234"}) → "환불 완료"
│     │
│     │  [LLM 호출 #4] 결과 정리
│     │  → "주문 #1234, 50,000원 환불 처리 완료"
│     │
│     ▼
│  (이 문자열이 도구 결과로 돌아옴)
│
│  [LLM 호출 #5] 최종 답변 생성
│  → "고객님, 주문 #1234의 50,000원 환불이 완료되었습니다."
│
▼
사용자에게 응답
```

총 LLM 5회 호출: Supervisor 2회 + Refund Worker 3회.

---

## 실제 코드: 어떻게 쓰는가?

### node 정의 + 실행

```kotlin
class CustomerService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {
    // 실제 도구들 (개발자가 구현)
    private val checkOrderTool = CheckOrderTool(orderRepository)
    private val processRefundTool = ProcessRefundTool(paymentService)
    private val trackShippingTool = TrackShippingTool(shippingApi)

    suspend fun handle(message: String): MultiAgentResult {
        return MultiAgent.supervisor()
            .node("order") {
                systemPrompt = "주문 조회, 변경, 취소를 처리하라"
                description = "주문 관련 업무"
                tools = listOf(checkOrderTool)
            }
            .node("refund") {
                systemPrompt = "환불 정책에 따라 환불을 처리하라"
                description = "환불 신청, 상태 확인"
                tools = listOf(checkOrderTool, processRefundTool)
            }
            .node("shipping") {
                systemPrompt = "배송 추적, 주소 변경을 처리하라"
                description = "배송 관련 업무"
                tools = listOf(trackShippingTool)
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "고객 요청을 분석하고 적절한 팀에 위임하라",
                    userPrompt = message
                ),
                agentFactory = { node ->
                    SpringAiAgentExecutor(
                        chatClient = chatClient,
                        properties = properties,
                        toolCallbacks = node.tools
                    )
                }
            )
    }
}
```

### Controller 연결

```kotlin
@RestController
class SupportController(private val customerService: CustomerService) {

    @PostMapping("/api/support")
    suspend fun support(@RequestBody request: ChatRequest): ChatResponse {
        val result = customerService.handle(request.message)
        return ChatResponse(
            content = result.finalResult.content,
            success = result.success
        )
    }
}
```

사용자는 `POST /api/support`만 호출한다. 내부에서 멀티에이전트가 동작하는 건 모른다.

---

## 내부에서 일어나는 일 (개발자가 안 만지는 부분)

개발자가 `MultiAgent.supervisor().node(...).execute(...)` 를 호출하면:

```
1. SupervisorOrchestrator.execute() 실행

2. 각 node를 WorkerAgentTool로 자동 변환
   node("order")    → WorkerAgentTool(name="delegate_to_order")
   node("refund")   → WorkerAgentTool(name="delegate_to_refund")
   node("shipping") → WorkerAgentTool(name="delegate_to_shipping")

3. Supervisor 에이전트 생성
   도구 목록 = [위에서 만든 WorkerAgentTool 3개]
   시스템 프롬프트 = 자동 생성 (또는 커스텀)

4. Supervisor의 ReAct 루프 시작
   LLM이 description을 보고 적절한 delegate_to_* 도구를 호출
   → WorkerAgentTool.call() 안에서 워커 에이전트가 실행됨
   → 결과가 Supervisor에게 돌아옴
   → Supervisor가 최종 답변 생성
```

`WorkerAgentTool` 클래스는 1개다. 인스턴스만 node 수만큼 만들어진다.
개발자가 직접 `WorkerAgentTool`을 만질 일은 없다.

---

## MCP 도구와의 관계

에이전트가 사용하는 도구에는 3종류가 있다:

| | 로컬 도구 | MCP 도구 | WorkerAgentTool |
|---|---|---|---|
| **안에서 하는 일** | 함수 1개 실행 | 외부 서버에 요청 | 에이전트 통째로 실행 |
| **LLM 호출** | 없음 | 없음 | 있음 (자체 ReAct 루프) |
| **예시** | 계산기 → `"8"` | MCP 서버에 파일 읽기 → 내용 | 환불 에이전트 → `"환불 완료"` |

**MCP는 "도구의 출처"** (로컬 vs 외부), **WorkerAgentTool은 "도구의 복잡도"** (함수 vs 에이전트).

이 셋을 섞을 수 있다:
```
Supervisor 도구: [delegate_to_refund(WorkerAgentTool), search(MCP도구)]
Refund Worker 도구: [processRefund(로컬도구), paymentApi(MCP도구)]
```

---

## 사람이 정하는 것 vs LLM이 정하는 것

```
사람(개발자)이 정하는 것:
  ✅ 어떤 워커가 존재하는지 (node 정의)
  ✅ 각 워커의 역할과 도구 (systemPrompt, tools)
  ✅ 패턴 선택 (supervisor, sequential, parallel)

LLM이 알아서 하는 것:
  ✅ 이 요청을 어떤 워커에게 보낼지 (라우팅)
  ✅ 워커 안에서 도구를 어떤 순서로 쓸지
  ✅ 최종 답변 생성
```

**node 정의는 사람이 해야 한다.** 이건 어떤 AI Agent 프레임워크든 동일하다.

이유:
- **도구는 코드다** — `processRefund()` 같은 함수는 누군가 구현해야 한다
- **권한 경계** — 어떤 에이전트가 어떤 도구에 접근 가능한지는 사람이 통제해야 한다
- **비용 통제** — 에이전트가 에이전트를 무한히 만들면 LLM 호출 비용이 폭발한다

사람이 **"이 시스템에 어떤 능력이 있는지"**를 정의하고,
LLM이 **"그 능력을 언제 어떻게 쓸지"**를 판단하는 구조다.

---

## 참고 코드

- [`WorkerAgentTool.kt`](../src/main/kotlin/com/arc/reactor/agent/multi/WorkerAgentTool.kt) — 에이전트를 도구로 감싸는 어댑터
- [`SupervisorOrchestrator.kt`](../src/main/kotlin/com/arc/reactor/agent/multi/SupervisorOrchestrator.kt) — Supervisor 오케스트레이터
- [`MultiAgentBuilder.kt`](../src/main/kotlin/com/arc/reactor/agent/multi/MultiAgentBuilder.kt) — DSL 빌더
- [`CustomerServiceExample.kt`](../src/main/kotlin/com/arc/reactor/agent/multi/example/CustomerServiceExample.kt) — 실제 사용 예시
