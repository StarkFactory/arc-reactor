# 휴먼인더루프 (HITL)

## 한 줄 요약

**위험한 도구 호출을 실행 전에 일시 정지하고, 사람의 승인을 받은 후 실행합니다.**

---

## 왜 휴먼인더루프인가?

AI 에이전트는 강력하지만, 일부 작업은 완전 자동화하면 안 됩니다:

```
사용자: "지난달 주문 전부 취소해줘"

[HITL 없는 에이전트]
  LLM: "각 주문에 대해 cancelOrder를 호출해야겠다"
  → cancelOrder({orderId: "1001"}) → 취소됨
  → cancelOrder({orderId: "1002"}) → 취소됨
  → cancelOrder({orderId: "1003"}) → 취소됨
  ... (전부 취소됨, 되돌릴 수 없음)
```

HITL이 있으면 에이전트가 위험한 작업 전에 멈춥니다:

```
사용자: "지난달 주문 전부 취소해줘"

[HITL 있는 에이전트]
  LLM: "주문 #1001에 대해 cancelOrder를 호출해야겠다"
  → cancelOrder는 승인 필요
  → 에이전트 일시 정지, 사람 대기
  → 관리자 확인: "주문 #1001 취소? [승인] [거부]"
  → 관리자 승인
  → cancelOrder({orderId: "1001"}) → 취소됨
  → 다음 주문...
```

되돌릴 수 없거나 높은 가치의 작업에 대해 사람이 통제권을 유지합니다.

---

## 핵심 개념

### ToolApprovalPolicy

어떤 도구 호출에 사람의 승인이 필요한지 결정하는 인터페이스:

```kotlin
interface ToolApprovalPolicy {
    fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean
}
```

`true`를 반환하면 도구 호출이 승인 대기 상태가 되고, `false`를 반환하면 즉시 실행됩니다.

### PendingApprovalStore

Kotlin `CompletableDeferred`를 사용해 대기 중인 승인을 관리합니다. 도구 호출에 승인이 필요하면:

1. 고유 ID를 가진 `PendingApproval` 항목이 생성됨
2. 에이전트의 코루틴이 `CompletableDeferred`에서 일시 정지
3. 사람이 REST API로 승인/거부하면 `CompletableDeferred`가 완료됨
4. 에이전트 재개: 도구 실행(승인) 또는 건너뜀(거부)

### ApprovalController

사람이 대기 중인 승인을 조회하고 처리하는 REST API입니다.

---

## 설정

### HITL 활성화

```yaml
arc:
  reactor:
    approval:
      enabled: true               # HITL 활성화 (기본값: false)
      timeout-ms: 300000          # 승인 타임아웃 ms (기본값: 5분)
      tool-names:                 # 승인이 필요한 도구 (ToolNameApprovalPolicy용)
        - delete_order
        - process_refund
        - send_payment
```

### 속성

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | `false` | HITL 마스터 스위치 |
| `timeout-ms` | `300000` | 승인 대기 타임아웃 (ms) |
| `tool-names` | `[]` | 승인이 필요한 도구 이름 목록 (기본 정책용) |

---

## 실행 흐름

```
사용자 프롬프트: "주문 #1234 환불해줘"
|
v
[에이전트 ReAct 루프]
  LLM 결정: process_refund({orderId: "1234", amount: 50000}) 호출
     |
     v
  ToolApprovalPolicy.requiresApproval("process_refund", {orderId: "1234", ...})
     → true (process_refund가 승인 목록에 있음)
     |
     v
  PendingApprovalStore.requestApproval()
     → PendingApproval(id="abc-123", toolName="process_refund", ...) 생성
     → 에이전트 코루틴이 CompletableDeferred에서 일시 정지
     |
     v
  [관리자가 REST API로 확인]
     GET /api/approvals → 대기 중인 승인 "abc-123" 확인
     |
     v
  [관리자 결정]
     POST /api/approvals/abc-123/approve
     또는
     POST /api/approvals/abc-123/reject?reason=중복 환불
     |
     v
  CompletableDeferred 완료
     → 에이전트 코루틴 재개
     |
     v
  [승인됨] process_refund 실행 → "50,000원 환불 완료"
  [거부됨] 도구 건너뜀 → LLM에 "운영자가 도구 호출을 거부했습니다: 중복 환불" 전달
     |
     v
  LLM이 최종 응답 생성
```

---

## REST API

### 대기 중인 승인 목록 조회

```bash
GET /api/approvals
```

응답:
```json
[
  {
    "id": "abc-123",
    "toolName": "process_refund",
    "arguments": {
      "orderId": "1234",
      "amount": 50000
    },
    "requestedAt": "2026-02-10T14:30:00Z",
    "sessionId": "session-456",
    "userPrompt": "주문 #1234 환불해줘"
  }
]
```

### 도구 호출 승인

```bash
POST /api/approvals/abc-123/approve
Content-Type: application/json

{
  "modifiedArguments": {
    "orderId": "1234",
    "amount": 25000
  }
}
```

선택적 `modifiedArguments` 필드로 실행 전 도구 인자를 수정할 수 있습니다. 예를 들어, 환불을 승인하되 금액을 줄이는 것이 가능합니다.

생략하면 원래 인자가 사용됩니다.

### 도구 호출 거부

```bash
POST /api/approvals/abc-123/reject
Content-Type: application/json

{
  "reason": "이 주문은 이미 환불되었습니다"
}
```

선택적 `reason`은 LLM에 전달되어 사용자에게 안내할 수 있습니다.

---

## 기본 제공 정책

### ToolNameApprovalPolicy (기본)

`tool-names` 설정 목록에 있는 도구에 대해 승인을 요구합니다:

```yaml
arc:
  reactor:
    approval:
      enabled: true
      tool-names:
        - delete_order
        - process_refund
        - send_payment
```

목록에 없는 도구는 승인 없이 즉시 실행됩니다.

### AlwaysApprovePolicy

승인을 요구하지 않습니다. HITL 인프라를 유지하면서 실질적으로 비활성화합니다. 테스트에 유용합니다:

```kotlin
@Bean
fun approvalPolicy(): ToolApprovalPolicy = AlwaysApprovePolicy()
```

---

## 커스텀 정책

`ToolApprovalPolicy`를 구현해서 자체 승인 로직을 정의할 수 있습니다:

### 금액 기반 정책

```kotlin
@Component
class AmountPolicy : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        val amount = (arguments["amount"] as? Number)?.toDouble() ?: return false
        return amount > 10_000  // 10,000 초과 금액은 승인 필요
    }
}
```

### 역할 기반 정책

```kotlin
@Component
class RoleBasedPolicy(
    private val userStore: UserStore
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        // 사용자 역할, 도구 이름, 인자 값 등을 확인 가능
        val dangerousTools = setOf("delete_order", "process_refund", "modify_account")
        return toolName in dangerousTools
    }
}
```

### 복합 정책

```kotlin
@Component
class CompositePolicy(
    private val policies: List<ToolApprovalPolicy>
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        // 어느 정책이라도 승인을 요구하면 승인 필요
        return policies.any { it.requiresApproval(toolName, arguments) }
    }
}
```

`@ConditionalOnMissingBean` 덕분에 자체 `ToolApprovalPolicy` 빈을 제공하면 기본 `ToolNameApprovalPolicy`를 자동으로 대체합니다.

---

## 타임아웃 동작

`timeout-ms` 내에 사람이 응답하지 않으면:

```
에이전트 일시 정지 → 타임아웃 도달 → TimeoutException
  → 도구 호출 건너뜀
  → LLM 수신: "승인 대기 중 도구 호출이 타임아웃되었습니다"
  → LLM이 사용자에게 안내
```

타임아웃은 에이전트가 무한정 대기하는 것을 방지합니다. 운영 워크플로우에 맞게 `timeout-ms`를 조정하세요 (예: 비동기 워크플로우는 길게, 실시간 채팅은 짧게).

---

## 페일오픈 설계

HITL은 페일오픈으로 설계되었습니다. 승인 시스템 자체에 오류가 발생하면, 에이전트를 차단하지 않고 도구를 정상 실행합니다.

| 상황 | 동작 |
|------|------|
| `approval.enabled = false` | 모든 도구가 승인 없이 실행 |
| `PendingApprovalStore` 예외 발생 | 도구 정상 실행 (경고 로그) |
| 승인 타임아웃 | 도구 호출 건너뜀, LLM에 알림 |
| 관리자 승인 | 원래 또는 수정된 인자로 도구 실행 |
| 관리자 거부 | 도구 건너뜀, 거부 사유를 LLM에 전달 |

원칙: **승인 시스템이 고장 나도 에이전트는 고장 나면 안 된다.**

---

## 멀티에이전트 통합

Supervisor 패턴에서 HITL은 도구 실행 레벨에서 적용됩니다. Supervisor와 Worker 에이전트 모두 승인 정책을 따릅니다:

```
[Supervisor] → delegate_to_refund (WorkerAgentTool, 승인 불필요)
  [Refund Worker] → process_refund (승인 필요)
    → 관리자 승인
    → 환불 실행
  [Refund Worker] → send_notification (승인 불필요)
    → 즉시 실행
```

승인 확인은 `SpringAiAgentExecutor.checkToolApproval()`에서 이루어지며, 계층 구조에서 에이전트의 위치에 관계없이 모든 도구 호출에 대해 실행됩니다.

---

## 참고 코드

| 파일 | 설명 |
|------|------|
| [`ToolApprovalPolicy.kt`](../../src/main/kotlin/com/arc/reactor/approval/ToolApprovalPolicy.kt) | 정책 인터페이스 + 기본 제공 구현체 |
| [`ApprovalModels.kt`](../../src/main/kotlin/com/arc/reactor/approval/ApprovalModels.kt) | PendingApproval, ApprovalResult 데이터 클래스 |
| [`PendingApprovalStore.kt`](../../src/main/kotlin/com/arc/reactor/approval/PendingApprovalStore.kt) | CompletableDeferred 기반 승인 저장소 |
| [`ApprovalController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ApprovalController.kt) | 승인 관리 REST API |
| [`SpringAiAgentExecutor.kt`](../../src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt) | checkToolApproval() 통합 지점 |
| [`AgentProperties.kt`](../../src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt) | ApprovalProperties 설정 |
