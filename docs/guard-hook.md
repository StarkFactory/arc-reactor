# Guard 파이프라인 & Hook 시스템

> 이 문서는 Arc Reactor의 보안 계층(Guard)과 생명주기 확장점(Hook)의 내부 동작을 설명합니다.

## Guard 시스템

### 개요

Guard는 AI 에이전트로 들어오는 모든 요청을 **실행 전에** 검증하는 5단계 보안 파이프라인입니다.

```
요청 → [RateLimit] → [InputValidation] → [InjectionDetection] → [Classification] → [Permission] → 통과
         order=1        order=2              order=3                order=4            order=5
```

하나라도 `Rejected`를 반환하면 즉시 파이프라인이 중단되고, 에이전트 실행 없이 실패를 반환합니다.

### 핵심 인터페이스

**RequestGuard** (`guard/Guard.kt`):

```kotlin
interface RequestGuard {
    suspend fun guard(command: GuardCommand): GuardResult
}
```

**GuardStage** — 개별 검사 단계:

```kotlin
interface GuardStage {
    val stageName: String    // 단계 이름 (로깅/에러 메시지용)
    val order: Int           // 실행 순서 (낮을수록 먼저)
    val enabled: Boolean     // 비활성화 가능

    suspend fun check(command: GuardCommand): GuardResult
}
```

### GuardCommand와 GuardResult

**입력:**

```kotlin
data class GuardCommand(
    val userId: String,                       // 사용자 ID
    val text: String,                         // 검증할 텍스트
    val channel: String? = null,              // 채널 정보 (Slack, Web 등)
    val metadata: Map<String, Any> = emptyMap()
)
```

**결과 (sealed class):**

```kotlin
sealed class GuardResult {
    data class Allowed(val hints: List<String> = emptyList())
    data class Rejected(
        val reason: String,                   // 거부 사유
        val category: RejectionCategory,      // 거부 카테고리
        val stage: String? = null             // 어떤 단계에서 거부했는지
    )
}
```

**거부 카테고리:**

| 카테고리 | 설명 |
|---------|------|
| `RATE_LIMITED` | 속도 제한 초과 |
| `INVALID_INPUT` | 잘못된 입력 (빈 문자열, 길이 초과) |
| `PROMPT_INJECTION` | 프롬프트 인젝션 공격 탐지 |
| `OFF_TOPIC` | 관련 없는 요청 |
| `UNAUTHORIZED` | 권한 없음 |
| `SYSTEM_ERROR` | Guard 단계 자체 오류 |

### 5단계 파이프라인

#### Stage 1: RateLimit (order=1)

```kotlin
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100
) : RateLimitStage
```

- Caffeine 캐시로 사용자별 요청 수 추적
- 분당/시간당 두 가지 윈도우 적용
- 캐시 TTL이 자동으로 카운터를 리셋

#### Stage 2: InputValidation (order=2)

```kotlin
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1
) : InputValidationStage
```

- 빈 문자열 거부
- 최대 길이 초과 거부 (기본 10,000자)

#### Stage 3: InjectionDetection (order=3)

```kotlin
class DefaultInjectionDetectionStage : InjectionDetectionStage
```

정규식 패턴으로 프롬프트 인젝션 공격을 탐지합니다:

- **역할 변경 시도:** `"ignore previous"`, `"you are now"`, `"act as"`
- **시스템 프롬프트 추출:** `"show me your prompt"`, `"repeat your instructions"`
- **출력 조작:** `"output the following"`, `"print exactly"`
- **인코딩 우회:** `"base64"`, `"rot13"`, `"hex encode"`
- **구분자 인젝션:** `###`, `---`, `===`, `<<<>>>`

#### Stage 4: Classification (order=4)

```kotlin
class DefaultClassificationStage : ClassificationStage
```

기본 구현은 패스스루(모든 요청 허용)입니다. 프로덕션에서는 LLM 기반 또는 규칙 기반 콘텐츠 분류를 구현합니다.

#### Stage 5: Permission (order=5)

```kotlin
class DefaultPermissionStage : PermissionStage
```

기본 구현은 패스스루(모든 사용자 허용)입니다. 프로덕션에서는 RBAC 또는 사용자 정의 권한 시스템을 통합합니다.

### GuardPipeline 실행 흐름

```kotlin
class GuardPipeline(stages: List<GuardStage>) : RequestGuard {

    // 활성화된 단계만 order 순으로 정렬
    private val sortedStages = stages.filter { it.enabled }.sortedBy { it.order }

    override suspend fun guard(command: GuardCommand): GuardResult {
        if (sortedStages.isEmpty()) return GuardResult.Allowed.DEFAULT

        for (stage in sortedStages) {
            try {
                when (val result = stage.check(command)) {
                    is Allowed  -> continue        // 다음 단계
                    is Rejected -> return result    // 즉시 중단
                }
            } catch (e: Exception) {
                // Fail-Close: 에러 발생 시 거부
                return GuardResult.Rejected(
                    reason = "Security check failed",
                    category = RejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }
        }

        return GuardResult.Allowed.DEFAULT  // 모든 단계 통과
    }
}
```

### Fail-Close 정책

Guard는 **항상 Fail-Close**입니다:

- Guard 단계에서 예외가 발생하면 → **요청 거부**
- 이는 보안이 절대 우회되지 않도록 보장합니다
- Hook과의 핵심 차이점입니다 (Hook은 기본 Fail-Open)

### Executor 통합

```kotlin
// SpringAiAgentExecutor.kt
private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
    if (guard == null) return null
    val userId = command.userId ?: "anonymous"  // null userId 방어
    val result = guard.guard(GuardCommand(userId = userId, text = command.userPrompt))
    return result as? GuardResult.Rejected
}
```

`userId`가 null이면 `"anonymous"`를 사용합니다. Guard를 건너뛰지 않습니다 (보안 취약점 방지).

### Guard 커스터마이징

`@Component`로 빈을 등록하면 자동으로 파이프라인에 추가됩니다:

```kotlin
@Component
class MyCustomGuard : PermissionStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // 관리자만 허용
        if (command.userId in adminList) return GuardResult.Allowed()
        return GuardResult.Rejected("관리자만 사용 가능", UNAUTHORIZED)
    }
}
```

---

## Hook 시스템

### 개요

Hook은 에이전트 실행의 **4가지 주요 시점**에서 확장 포인트를 제공합니다.

```
BeforeAgentStart ──→ [에이전트 ReAct 루프] ──→ AfterAgentComplete
                           │
                    BeforeToolCall ──→ [도구 실행] ──→ AfterToolCall
                    (도구마다 반복)
```

### 4가지 Hook 타입

| Hook | 시점 | 반환값 | 용도 |
|------|------|--------|------|
| `BeforeAgentStart` | 에이전트 시작 전 | HookResult | 인증, 예산 확인, 거부 가능 |
| `BeforeToolCall` | 도구 호출 전 | HookResult | 도구별 권한, 파라미터 검증 |
| `AfterToolCall` | 도구 호출 후 | void | 결과 로깅, 메트릭 |
| `AfterAgentComplete` | 에이전트 완료 후 | void | 빌링, 통계, 알림 |

### AgentHook 베이스 인터페이스

```kotlin
interface AgentHook {
    val order: Int get() = 0          // 실행 순서 (낮을수록 먼저)
    val enabled: Boolean get() = true  // 활성화 여부
    val failOnError: Boolean get() = false  // 실패 시 동작
}
```

**order 권장 범위:**

| 범위 | 용도 | 예시 |
|------|------|------|
| 1-99 | 중요한 Hook | 인증, 보안 |
| 100-199 | 표준 Hook | 로깅, 감사 |
| 200+ | 후기 Hook | 정리, 알림 |

### HookResult

```kotlin
sealed class HookResult {
    data object Continue : HookResult()            // 계속 진행
    data class Reject(val reason: String)           // 실행 거부
    data class Modify(val modifiedParams: Map<...>) // 파라미터 수정 후 계속
    data class PendingApproval(                     // 비동기 승인 대기
        val approvalId: String,
        val message: String
    )
}
```

### Hook 컨텍스트

**HookContext** — 에이전트 수준:

```kotlin
data class HookContext(
    val runId: String,               // 실행 ID (UUID)
    val userId: String,              // 사용자 ID
    val userEmail: String? = null,
    val userPrompt: String,          // 사용자 프롬프트
    val channel: String? = null,
    val startedAt: Instant,          // 시작 시간
    val toolsUsed: MutableList<String>,          // CopyOnWriteArrayList
    val metadata: MutableMap<String, Any>        // ConcurrentHashMap
)
```

**ToolCallContext** — 도구 호출 수준:

```kotlin
data class ToolCallContext(
    val agentContext: HookContext,    // 상위 에이전트 컨텍스트
    val toolName: String,            // 도구 이름
    val toolParams: Map<String, Any?>,  // 도구 파라미터
    val callIndex: Int               // 호출 인덱스 (0부터)
) {
    // 민감한 파라미터 자동 마스킹
    fun maskedParams(): Map<String, Any?> {
        // password, token, secret, key, credential, apikey → "***"
    }
}
```

### HookExecutor 실행 흐름

```kotlin
class HookExecutor(
    beforeStartHooks: List<BeforeAgentStartHook>,
    beforeToolCallHooks: List<BeforeToolCallHook>,
    afterToolCallHooks: List<AfterToolCallHook>,
    afterCompleteHooks: List<AfterAgentCompleteHook>
) {
    // 각 타입별로 enabled 필터링 + order 정렬
    private val sortedBeforeStartHooks = beforeStartHooks
        .filter { it.enabled }
        .sortedBy { it.order }
    // ...
}
```

**Before 계열 Hook 실행:**

```kotlin
private suspend fun <T : AgentHook, C> executeHooks(
    hooks: List<T>,
    context: C,
    execute: suspend (T, C) -> HookResult
): HookResult {
    for (hook in hooks) {
        try {
            when (val result = execute(hook, context)) {
                is Continue       -> continue      // 다음 Hook
                is Reject         -> return result  // 즉시 거부
                is Modify         -> return result  // 수정 반환
                is PendingApproval -> return result // 승인 대기
            }
        } catch (e: Exception) {
            if (hook.failOnError) {
                return HookResult.Reject("Hook execution failed: ${e.message}")
            }
            // fail-open: 에러 무시하고 다음 Hook 계속
        }
    }
    return HookResult.Continue  // 모든 Hook 통과
}
```

**After 계열 Hook 실행:**

```kotlin
suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
    for (hook in sortedAfterToolCallHooks) {
        try {
            hook.afterToolCall(context, result)
        } catch (e: Exception) {
            logger.error(e) { "AfterToolCallHook failed" }
            if (hook.failOnError) throw e
        }
    }
}
```

After Hook은 결과값을 반환하지 않습니다. 관찰(observe)만 하고, 거부하거나 수정할 수 없습니다.

### Fail-Open vs Fail-Close

| | Guard | Hook (기본) | Hook (failOnError=true) |
|---|---|---|---|
| 에러 발생 시 | 요청 거부 | 에러 무시, 계속 진행 | 예외 전파, 실행 중단 |
| 정책 | Fail-Close (고정) | Fail-Open (기본) | Fail-Close (선택) |
| 근거 | 보안은 절대 우회 불가 | Hook 실패가 핵심 기능을 막으면 안 됨 | 중요한 Hook은 실패 시 중단 |

### Executor 통합

**BeforeAgentStart Hook:**

```kotlin
// executeInternal()에서 호출
checkBeforeHooks(hookContext)?.let { hookResult ->
    val message = when (hookResult) {
        is HookResult.Reject -> hookResult.reason
        is HookResult.PendingApproval -> "Pending approval: ${hookResult.message}"
        else -> "Blocked by hook"
    }
    return AgentResult.failure(
        errorMessage = message,
        errorCode = AgentErrorCode.HOOK_REJECTED
    )
}
```

**BeforeToolCall / AfterToolCall Hook:**

도구 병렬 실행 내부(`executeSingleToolCall()`)에서 호출됩니다. BeforeToolCall이 Reject를 반환하면 **해당 도구 호출만** 스킵되고, 다른 도구는 정상 실행됩니다.

**AfterAgentComplete Hook:**

```kotlin
// executeInternal()의 마지막 단계
try {
    hookExecutor?.executeAfterAgentComplete(context, response)
} catch (e: Exception) {
    logger.error(e) { "AfterAgentComplete hook failed" }
    // 에러만 로깅, 최종 결과에 영향 없음
}
```

AfterAgentComplete는 에이전트 성공/실패와 관계없이 항상 호출됩니다. 스트리밍 모드에서는 `finally` 블록에서 호출되어, 에러가 발생해도 반드시 실행됩니다.

### Hook 구현 예시

**감사 로그 Hook:**

```kotlin
@Component
class AuditLogHook : AfterAgentCompleteHook {
    override val order = 100  // 표준 Hook 범위

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info {
            "Agent completed: runId=${context.runId}, " +
            "userId=${context.userId}, " +
            "success=${response.success}, " +
            "tools=${response.toolsUsed}, " +
            "duration=${context.durationMs()}ms"
        }
    }
}
```

**비용 확인 Hook:**

```kotlin
@Component
class BudgetCheckHook(
    private val billingService: BillingService
) : BeforeAgentStartHook {
    override val order = 10  // 중요 Hook (인증 다음)

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val budget = billingService.getRemainingBudget(context.userId)
        if (budget <= 0) {
            return HookResult.Reject("예산이 소진되었습니다")
        }
        return HookResult.Continue
    }
}
```

---

## Guard vs Hook 비교

```
사용자 요청
    │
    ▼
┌─ Guard Pipeline ─────────────────────────┐
│  "이 요청을 실행해도 안전한가?"              │
│  - 보안 검증 (인젝션, 권한)                 │
│  - 입력 검증 (길이, 형식)                   │
│  - 속도 제한                               │
│  → Fail-Close: 에러 = 거부                 │
└──────────────────────────────────────────┘
    │ 통과
    ▼
┌─ Hook System ────────────────────────────┐
│  "이 실행에 추가로 무엇을 할 것인가?"        │
│  - 로깅, 감사                              │
│  - 빌링, 메트릭                            │
│  - 알림, 정리                              │
│  → Fail-Open: 에러 = 무시하고 계속          │
└──────────────────────────────────────────┘
```

| 관점 | Guard | Hook |
|------|-------|------|
| 질문 | "이 요청이 안전한가?" | "이 실행에 무엇을 더 할 것인가?" |
| 실행 시점 | 에이전트 시작 전 (1회) | 여러 시점 (4가지) |
| 에러 정책 | 항상 Fail-Close | 기본 Fail-Open, 선택 Fail-Close |
| 거부 범위 | 요청 전체 | 요청 전체 또는 특정 도구만 |
| 수정 능력 | 불가 | `HookResult.Modify`로 가능 |
| 단계 수 | 5단계 (고정 타입) | 4타입 (무제한 구현체) |
