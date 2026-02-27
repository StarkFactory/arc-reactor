# Guard 파이프라인 & Hook 시스템

> 이 문서는 Arc Reactor의 보안 계층(Guard)과 생명주기 확장점(Hook)의 내부 동작을 설명합니다.

## Guard 시스템

### 개요

Guard는 **입력**(실행 전)과 **출력**(실행 후)을 모두 커버하는 다계층 보안 시스템입니다. OWASP LLM Top 10 (2025)에 맞춰 5개 방어 레이어로 구성됩니다.

```
요청
  │
  ▼
[Input Guard Pipeline] ── 6개 정렬된 단계, fail-close
  │  L0: UnicodeNormalization → RateLimit → InputValidation → InjectionDetection
  │  L1: Classification → TopicDriftDetection (opt-in)
  │
  ▼
[에이전트 실행] ── ReAct 루프 + 도구 호출
  │  L3: ToolOutputSanitizer (opt-in, 도구 출력 래핑)
  │
  ▼
[Output Guard Pipeline] ── 4개 정렬된 단계, fail-close
  │  L2: SystemPromptLeakageGuard (opt-in)
  │      PiiMaskingGuard → DynamicRuleGuard → RegexPatternGuard
  │
  ▼
응답
```

### 핵심 인터페이스

**RequestGuard** (`guard/Guard.kt`):

```kotlin
interface RequestGuard {
    suspend fun guard(command: GuardCommand): GuardResult
}
```

**GuardStage** — 개별 입력 검사 단계:

```kotlin
interface GuardStage {
    val stageName: String    // 단계 이름 (로깅/에러 메시지용)
    val order: Int           // 실행 순서 (낮을수록 먼저)
    val enabled: Boolean     // 비활성화 가능

    suspend fun check(command: GuardCommand): GuardResult
}
```

**OutputGuardStage** — 개별 출력 검사 단계:

```kotlin
interface OutputGuardStage {
    val stageName: String
    val order: Int get() = 0
    val enabled: Boolean get() = true

    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult
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

**출력 결과 (sealed class):**

```kotlin
sealed class OutputGuardResult {
    data class Allowed(...)
    data class Modified(val content: String, val reason: String, val stage: String?)
    data class Rejected(val reason: String, val stage: String?, val category: OutputRejectionCategory?)
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

### 5계층 방어 아키텍처

#### Layer 0: 정적 고속 필터 (항상 ON, LLM 비용 0ms)

##### Stage 0: UnicodeNormalization (order=0)

```kotlin
class UnicodeNormalizationStage : GuardStage
```

호모글리프 및 비가시 문자 공격 방어:

- **NFKC 정규화**: 전각 라틴 → ASCII (예: `ｉｇｎｏｒｅ` → `ignore`)
- **Zero-width 문자 제거**: U+200B/C/D/E/F, U+FEFF, U+00AD, U+2060-2064, U+180E, Unicode Tag Block (U+E0000-E007F)
- **호모글리프 치환**: 키릴 문자 а→a, е→e, о→o, р→p, с→c 등 (15개 매핑)
- **거부**: Zero-width 문자 비율이 임계치(기본 10%) 초과 시
- 정규화된 텍스트를 `GuardResult.Allowed(hints = ["normalized:$text"])`로 반환 — 이후 단계는 정리된 텍스트를 검사

##### Stage 1: RateLimit (order=1)

```kotlin
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100,
    private val tenantRateLimits: Map<String, TenantRateLimit> = emptyMap()
) : RateLimitStage
```

- Caffeine 캐시로 사용자별 요청 수 추적
- 분당/시간당 두 가지 윈도우 적용
- **테넌트 인식**: 캐시 키가 `"$tenantId:$userId"`, `tenantRateLimits`로 테넌트별 오버라이드 지원
- 캐시 TTL이 자동으로 카운터를 리셋

##### Stage 2: InputValidation (order=2)

```kotlin
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1
) : InputValidationStage
```

- 빈 문자열 거부
- 최대 길이 초과 거부 (기본 10,000자)

##### Stage 3: InjectionDetection (order=3)

```kotlin
class DefaultInjectionDetectionStage : InjectionDetectionStage
```

28개 정규식 패턴으로 프롬프트 인젝션 공격을 탐지합니다:

- **역할 변경 시도**: `"ignore previous"`, `"you are now"`, `"act as"`
- **시스템 프롬프트 추출**: `"show me your prompt"`, `"repeat your instructions"`
- **출력 조작**: `"output the following"`, `"print exactly"`
- **인코딩 우회**: `"base64"`, `"rot13"`, `"hex encode"`
- **구분자 인젝션**: `###`, `---` (5개 이상), `===` (5개 이상), `<<<>>>`
- **ChatML / Llama 토큰**: `<|im_start|>`, `<|im_end|>`, `[INST]`, `<start_of_turn>`
- **권한 상승**: `"developer mode"`, `"system override"`
- **안전 필터 우회**: `"override safety filter"`, `"override content policy"`
- **Many-shot 탈옥**: 3개 이상 연속 `example N` 마커
- **유니코드 이스케이프**: 4개 이상 연속 `\uXXXX` 패턴

#### Layer 1: 분류 (opt-in)

##### Stage 4: Classification (order=4)

```kotlin
class CompositeClassificationStage(
    ruleBasedStage: RuleBasedClassificationStage,
    llmStage: LlmClassificationStage? = null  // LLM 분류 비활성화 시 null
) : ClassificationStage
```

2단계 분류:
- **규칙 기반** (항상): `blockedCategories` (예: malware, weapons, self_harm) 키워드 매칭
- **LLM 기반** (opt-in): `ChatClient`로 의미 분류. Fail-open — LLM 오류 시 `Allowed` 반환

활성화: `arc.reactor.guard.classification-enabled=true`, `arc.reactor.guard.classification-llm-enabled=true`

##### Stage 6: TopicDriftDetection (order=6)

```kotlin
class TopicDriftDetectionStage : GuardStage
```

**크레센도 공격** (다회차 점진적 탈옥) 방어:
- `GuardCommand.metadata`에서 `conversationHistory` 읽기
- 슬라이딩 윈도우(최근 5턴)로 민감도 상승 패턴 점수 산출
- 패턴 에스컬레이션: 가정 → what if → 연구 목적 → 단계별 → 우회/오버라이드
- `maxDriftScore` 임계치 설정 가능 (기본 0.7)

#### Layer 2: 시스템 프롬프트 보호 (opt-in)

카나리 토큰 주입으로 시스템 프롬프트 유출을 방지합니다:

```kotlin
class CanaryTokenProvider(seed: String)       // SHA-256 기반 결정적 CANARY-{8hex} 토큰 생성
class CanarySystemPromptPostProcessor         // 시스템 프롬프트에 카나리 절 추가
class SystemPromptLeakageOutputGuard          // 출력 가드 단계 (order=5)
```

- **CanaryTokenProvider**: SHA-256 시드에서 결정적 토큰 생성
- **CanarySystemPromptPostProcessor**: `"다음 토큰은 비밀이며..."` 절을 시스템 프롬프트에 추가
- **SystemPromptLeakageOutputGuard**: 출력에서 (1) 카나리 토큰 존재, (2) 유출 문구 (`"my system prompt is"`, `"I was instructed to"`) 탐지

활성화: `arc.reactor.guard.canary-token-enabled=true`

#### Layer 3: 도구 출력 정제 (opt-in)

도구 출력을 통한 **간접 프롬프트 인젝션** 방어:

```kotlin
class ToolOutputSanitizer {
    fun sanitize(toolName: String, output: String): SanitizedOutput
}
```

- 도구 출력을 데이터-명령어 분리 마커로 래핑
- 인젝션 패턴 제거 (역할 오버라이드, 시스템 구분자, 프롬프트 오버라이드, 데이터 유출 시도)
- 탐지된 패턴 → `[SANITIZED]`로 대체
- `ToolCallOrchestrator`에 선택적 파라미터로 통합

활성화: `arc.reactor.guard.tool-output-sanitization-enabled=true`

#### Layer 4: 감사 & 관측성 (arc-admin 활성화 시 항상 ON)

```kotlin
interface GuardAuditPublisher {
    fun publish(command: GuardCommand, stage: String, result: String,
                reason: String?, stageLatencyMs: Long, pipelineLatencyMs: Long)
}
```

- **GuardPipeline**: 단계별/파이프라인 전체 지연 시간 기록, `GuardAuditPublisher`로 발행
- **OutputGuardPipeline**: `onStageComplete` 콜백으로 단계별 액션을 `AgentMetrics`에 기록
- **MetricGuardAuditPublisher** (arc-admin): `MetricRingBuffer`에 `GuardEvent` 발행 (SHA-256 입력 해시, 원문 아님)
- **스트리밍 지원**: 출력 가드가 수집된 전체 콘텐츠에 대해 후처리 실행; 거부/수정 시 `StreamEventMarker.error()` 발출

### Output Guard 파이프라인

출력 가드는 에이전트 실행 **후** 응답을 검증합니다:

```kotlin
class OutputGuardPipeline(
    stages: List<OutputGuardStage>,
    private val onStageComplete: ((stage: String, action: String, reason: String) -> Unit)? = null
)
```

**기본 제공 출력 가드 단계:**

| 단계 | Order | 기본값 | 설명 |
|------|-------|--------|------|
| `SystemPromptLeakageOutputGuard` | 5 | opt-in | 카나리 토큰 + 유출 패턴 탐지 |
| `PiiMaskingOutputGuard` | 10 | opt-in | PII 마스킹 (전화번호, 이메일, 주민번호, 신용카드) |
| `DynamicRuleOutputGuard` | 15 | opt-in | 런타임 설정 가능 규칙 (REST API 관리) |
| `RegexPatternOutputGuard` | 20 | opt-in | 정적 정규식 패턴 필터링 |

**결과 유형:**
- `Allowed` — 응답 그대로 통과
- `Modified` — 응답 내용 변경 (예: PII 마스킹)
- `Rejected` — 응답 전체 차단

**스트리밍 모드**: 출력 가드가 수집된 전체 콘텐츠에 후처리 실행. 거부/수정 시 `StreamEventMarker.error()` 이벤트를 SSE 클라이언트로 전송.

### GuardPipeline 실행 흐름

```kotlin
class GuardPipeline(
    stages: List<GuardStage>,
    private val auditPublisher: GuardAuditPublisher? = null
) : RequestGuard {

    override suspend fun guard(command: GuardCommand): GuardResult {
        var currentCommand = command
        for (stage in sortedStages) {
            when (val result = stage.check(currentCommand)) {
                is Allowed -> {
                    // UnicodeNormalizationStage의 정규화된 텍스트 적용
                    val norm = result.hints.firstOrNull { it.startsWith("normalized:") }
                    if (norm != null) currentCommand = currentCommand.copy(
                        text = norm.removePrefix("normalized:")
                    )
                    continue
                }
                is Rejected -> return result  // 즉시 중단
            }
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

### Fail-Close 정책

Guard는 입력과 출력 모두 **항상 Fail-Close**입니다:

- Guard 단계에서 예외가 발생하면 → **요청 거부**
- 이는 보안이 절대 우회되지 않도록 보장합니다
- Hook과의 핵심 차이점입니다 (Hook은 기본 Fail-Open)

### OWASP LLM Top 10 커버리지

| OWASP | 위협 | 레이어 | 단계 | 기본값 |
|-------|------|--------|------|--------|
| LLM01 | 직접 프롬프트 인젝션 | L0 | UnicodeNormalization + InjectionDetection | ON |
| LLM01 | 간접 인젝션 (도구) | L3 | ToolOutputSanitizer | opt-in |
| LLM01 | 다회차 탈옥 | L1 | TopicDriftDetection | opt-in |
| LLM02 | 응답 내 PII | Output | PiiMaskingOutputGuard | opt-in |
| LLM07 | 시스템 프롬프트 유출 | L2 | CanaryToken + LeakageGuard | opt-in |
| LLM10 | 리소스 고갈 | L0 | 테넌트 인식 RateLimit | ON |

### 설정

```yaml
arc:
  reactor:
    guard:
      enabled: true                            # 마스터 토글
      rate-limit-per-minute: 10                # 사용자별 분당 제한
      rate-limit-per-hour: 100                 # 사용자별 시간당 제한
      injection-detection-enabled: true        # 정규식 기반 인젝션 탐지
      unicode-normalization-enabled: true      # NFKC + 호모글리프 + zero-width
      classification-enabled: false            # 규칙 기반 분류 (opt-in)
      classification-llm-enabled: false        # LLM 분류 (opt-in)
      canary-token-enabled: false              # 시스템 프롬프트 유출 보호 (opt-in)
      tool-output-sanitization-enabled: false   # 간접 인젝션 방어 (opt-in)
      audit-enabled: true                      # Guard 감사 추적
      tenant-rate-limits:                      # 테넌트별 오버라이드
        tenant-abc:
          per-minute: 50
          per-hour: 500
```

### Executor 통합

```kotlin
// SpringAiAgentExecutor.kt
private suspend fun checkGuard(command: AgentCommand): GuardResult.Rejected? {
    if (guard == null) return null
    val userId = command.userId ?: "anonymous"  // null userId 방어
    val result = guard.guard(GuardCommand(userId = userId, text = command.userPrompt,
        metadata = command.metadata))
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
