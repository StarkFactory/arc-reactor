# ReAct 루프 내부 구현

> **핵심 파일:** `SpringAiAgentExecutor.kt` (~555줄, 추출된 헬퍼 클래스에 위임하는 오케스트레이터)
> 이 문서는 Arc Reactor의 핵심 실행 엔진인 ReAct 루프의 내부 동작을 설명합니다.

## 전체 실행 흐름

```
사용자 요청 (AgentCommand)
    │
    ▼
┌─────────────────────────────┐
│  execute() / executeStream()│  진입점 (2가지)
│  ├─ 실행 컨텍스트 설정        │
│  ├─ 동시성 세마포어 확보      │
│  └─ withTimeout 적용        │
└──────────┬──────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  AgentExecutionCoordinator       │  다단계 파이프라인
│  1. Guard 검사                   │  (PreExecutionResolver)
│  2. BeforeAgentStart Hook        │  (PreExecutionResolver)
│  3. Intent resolution            │  (PreExecutionResolver)
│  4. Response cache 조회          │
│  5. 대화 히스토리 로드            │
│  6. RAG 컨텍스트 검색            │  (RagContextRetriever)
│  7. 도구 선택 및 준비            │  (ToolPreparationPlanner)
│  8. executeWithTools()           │  ← ReAct 루프 본체 (ManualReActLoopExecutor)
│  9. Output guard + 경계 검사     │  (ExecutionResultFinalizer)
│  10. 대화 히스토리 저장           │  (ExecutionResultFinalizer)
│  11. AfterAgentComplete Hook     │  (ExecutionResultFinalizer)
└──────────────────────────────────┘
```

## 진입점: execute() vs executeStream()

### execute() — 비스트리밍

```kotlin
override suspend fun execute(command: AgentCommand): AgentResult
```

1. 실행 컨텍스트(`AgentRunContextManager`)를 열어 구조적 로깅 수행
2. `concurrencySemaphore.withPermit { }` — 동시 실행 수 제한
3. `withTimeout(requestTimeoutMs)` — 요청 전체 타임아웃
4. `AgentExecutionCoordinator.execute()`에 위임
5. 실패 시 `AgentErrorPolicy.classify(e)` → `AgentResult.failure()` 반환
6. `CancellationException`은 반드시 rethrow (`throwIfCancellation()`을 통해)

### executeStream() — 스트리밍

```kotlin
override fun executeStream(command: AgentCommand): Flow<String>
```

비스트리밍과 동일한 단계를 따르되, 다음이 다릅니다:

| 차이점 | execute() | executeStream() |
|--------|-----------|-----------------|
| 반환 타입 | `AgentResult` | `Flow<String>` |
| LLM 호출 | `requestSpec.call()` | `requestSpec.stream().chatResponse()` |
| 콘텐츠 수집 | 최종 응답에서 한 번에 | 청크 단위로 `emit()` |
| 메모리 저장 | `saveHistory()` | `saveStreamingHistory()` (finally 블록) |
| 에러 전달 | AgentResult.failure() | `emit("[error] message")` |
| Coordinator | `AgentExecutionCoordinator` | `StreamingExecutionCoordinator` |
| Finalizer | `ExecutionResultFinalizer` | `StreamingCompletionFinalizer` |

**스트리밍 메모리 저장의 핵심:**

```kotlin
// StreamingCompletionFinalizer.finalize()가 finally 블록에서 실행
if (streamSuccess) {
    conversationManager.saveStreamingHistory(command, lastIterationContent)
}
```

`lastIterationContent`만 저장합니다. 전체 누적 콘텐츠(`collectedContent`)가 아닌, 마지막 ReAct 반복의 콘텐츠만 대화 기록에 남깁니다.

## ReAct 루프 본체: ManualReActLoopExecutor

ReAct 루프는 `ManualReActLoopExecutor.execute()`에 구현되어 있습니다. 진입점인 `SpringAiAgentExecutor.executeWithTools()`가 시스템 프롬프트와 chat client를 확인한 후 여기로 위임합니다:

```kotlin
// ManualReActLoopExecutor.execute()
suspend fun execute(
    command: AgentCommand,
    activeChatClient: ChatClient,
    systemPrompt: String,
    initialTools: List<Any>,
    conversationHistory: List<Message>,
    hookContext: HookContext,
    toolsUsed: MutableList<String>,
    allowedTools: Set<String>?,
    maxToolCalls: Int
): AgentResult
```

### 루프 구조

```
while (true) {
    1. messageTrimmer.trim()          — 토큰 예산 내로 메시지 정리
    2. buildRequestSpec()             — system + messages + options + tools
    3. callWithRetry { call() }       — LLM 호출 (재시도 + 서킷 브레이커 포함)
    4. 토큰 사용량 누적
    5. 도구 호출 감지
       ├─ 없음 → validateAndRepairResponse() (루프 종료)
       └─ 있음 → 계속
    6. AssistantMessage 추가           — 도구 호출 정보 포함
    7. toolCallOrchestrator
         .executeInParallel()         — 병렬 도구 실행
    8. ToolResponseMessage 추가        — 도구 결과
    9. maxToolCalls 체크
       ├─ 미도달 → 루프 계속
       └─ 도달 → activeTools = emptyList() + SystemMessage 넛지 추가
}
```

### 도구 호출 감지

```kotlin
val assistantOutput = chatResponse?.results?.firstOrNull()?.output
val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
    return validateAndRepairResponse(assistantOutput?.text.orEmpty(), ...)
}
```

LLM 응답의 `output.toolCalls`가 비어 있으면 최종 텍스트 답변으로 판단합니다. 응답은 `StructuredResponseRepairer`를 통해 검증 (및 구조화된 출력 형식의 경우 선택적으로 복구)됩니다.

### maxToolCalls 강제 종료

```kotlin
if (totalToolCalls >= maxToolCalls) {
    activeTools = emptyList()           // 도구 제거
    chatOptions = buildChatOptions(command, false)  // 도구 없는 옵션
    messages.add(SystemMessage(
        "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
            "Summarize the results you have so far and provide your best answer. " +
            "Do not request additional tool calls."
    ))
}
```

도구를 빈 리스트로 교체하면, 다음 LLM 호출에서 도구 호출 없이 최종 텍스트 답변을 생성합니다. 단순히 로그만 찍으면 LLM이 계속 도구를 호출하려 하므로, **도구 자체를 제거**하는 것이 핵심입니다. `SystemMessage`도 주입하여 LLM이 추가 도구 요청 대신 결과를 요약하도록 유도합니다.

## 병렬 도구 실행

도구 실행은 메인 executor에서 추출된 전용 클래스인 `ToolCallOrchestrator`가 처리합니다.

### 실행 패턴

```kotlin
// ToolCallOrchestrator.executeInParallel()
suspend fun executeInParallel(
    toolCalls: List<AssistantMessage.ToolCall>,
    ...
): List<ToolResponseMessage.ToolResponse> = coroutineScope {
    toolCalls.map { toolCall ->
        async {
            executeSingleToolCall(toolCall, ...)
        }
    }.awaitAll()  // 순서 보장 (map 인덱스 순)
}
```

- `coroutineScope` — 구조적 동시성 (하나가 실패하면 전체 취소)
- `map { async { } }` — 모든 도구 호출을 동시에 시작
- `awaitAll()` — 원래 순서대로 결과 반환

### 단일 도구 실행 흐름

```kotlin
private suspend fun executeSingleToolCall(...): ParallelToolExecution {
    // 1. 인텐트 기반 도구 허용 목록 확인
    if (allowedTools != null && toolName !in allowedTools) return 에러 응답

    // 2. BeforeToolCall Hook
    hookExecutor?.executeBeforeToolCall(toolCallContext)
    // → Reject 시 해당 도구만 스킵

    // 3. Human-in-the-Loop 승인 확인 (ToolApprovalPolicy)
    checkToolApproval(toolName, toolCallContext, hookContext)

    // 4. 슬롯 예약 전 도구 존재 여부 확인
    val toolExists = findToolAdapter(toolName, tools) != null
        || springCallbacksByName.containsKey(toolName)

    // 5. CAS로 maxToolCalls 슬롯 예약 (동시성 안전)
    reserveToolExecutionSlot(totalToolCallsCounter, maxToolCalls)
    // → getAndIncrement 대신 compareAndSet 루프 사용

    // 6. 도구 실행 (도구별 타임아웃 적용)
    val invocation = invokeToolAdapter(toolName, toolInput, tools, ...)
    // → adapter 존재 확인 후에만 toolsUsed.add(toolName)

    // 7. 도구 출력 정제 (ToolOutputSanitizer, 간접 인젝션 방어)

    // 8. AfterToolCall Hook
    hookExecutor?.executeAfterToolCall(context, result)

    // 9. 메트릭 기록
    agentMetrics.recordToolCall(toolName, durationMs, success)
}
```

**주의:** `toolsUsed.add(toolName)`은 adapter 존재를 확인한 후에만 호출됩니다. LLM이 존재하지 않는 도구 이름을 생성(hallucination)할 수 있기 때문입니다.

## 컨텍스트 윈도우 관리

컨텍스트 트리밍은 `ConversationMessageTrimmer`가 처리합니다.

### 토큰 예산 계산

```
budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens - toolTokenReserve
```

`toolTokenReserve`는 활성 도구 정의를 설명하는 데 필요한 추정 토큰 수를 고려합니다 (도구당 ~200 토큰).

예: `128000 - 2000 - 4096 - 2000 = 119904 토큰`

### 3단계 트리밍

```kotlin
// ConversationMessageTrimmer.trim()
fun trim(messages: MutableList<Message>, systemPrompt: String, toolTokenReserve: Int = 0)
```

**Phase 1: 오래된 히스토리 제거 (앞에서부터)**

```
[SystemMessages...] [히스토리 메시지들...] [현재 UserMessage] [도구 상호작용들...]
                     ↑ 여기서부터 제거
```

가장 오래된 비SystemMessage부터 순서대로 제거합니다. **마지막 UserMessage(현재 프롬프트)**에 도달하면 멈춥니다. 선행 `SystemMessage` 항목(예: 계층적 메모리 팩트)은 이 단계에서 보존됩니다.

**Phase 1.5: 최신 도구 컨텍스트를 보존하기 위해 선행 SystemMessage 삭제**

Phase 1이 불충분하고 UserMessage 뒤에 도구 상호작용이 있는 경우, 가장 최근의 도구 호출/응답 컨텍스트를 LLM에 보이도록 유지하기 위해 선행 메모리 `SystemMessage` 항목을 삭제합니다.

**Phase 2: 도구 상호작용 제거 (UserMessage 뒤에서)**

여전히 예산 초과인 경우, 현재 UserMessage 뒤의 도구 호출/응답 쌍을 제거합니다.

### 메시지 쌍 무결성

```kotlin
private fun calculateRemoveGroupSize(messages: List<Message>): Int {
    val first = messages[0]
    // AssistantMessage(toolCalls) → ToolResponseMessage 쌍으로 제거
    if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
        return if (messages.size > 1 && messages[1] is ToolResponseMessage) 2 else 1
    }
    return 1
}
```

`AssistantMessage(toolCalls)`와 `ToolResponseMessage`는 반드시 쌍으로 제거됩니다. 하나만 남으면 LLM API 에러가 발생합니다.

### 토큰 추정

```kotlin
private fun estimateMessageTokens(message: Message): Int {
    val contentTokens = when (message) {
        is UserMessage -> tokenEstimator.estimate(message.text)
        is AssistantMessage -> {
            val textTokens = tokenEstimator.estimate(message.text ?: "")
            val toolCallTokens = message.toolCalls.sumOf {
                tokenEstimator.estimate(it.name() + it.arguments())
            }
            textTokens + toolCallTokens
        }
        is SystemMessage -> tokenEstimator.estimate(message.text)
        is ToolResponseMessage -> message.responses.sumOf {
            tokenEstimator.estimate(it.responseData())
        }
        // ...
    }
    return contentTokens + MESSAGE_STRUCTURE_OVERHEAD  // 메시지당 +20 토큰
}
```

각 메시지 추정에는 역할 태그, 구분자, 메타데이터를 위한 고정 `MESSAGE_STRUCTURE_OVERHEAD` (20 토큰)가 포함됩니다.

`TokenEstimator`는 CJK 문자를 인식하며 Caffeine 캐시(10,000 항목, 5분 TTL)를 사용합니다. 2,000자를 초과하는 문자열은 큰 힙 객체를 유지하지 않기 위해 캐시를 우회합니다:
- Latin 문자: ~4자/토큰
- 한글/CJK: ~1.5자/토큰
- 이모지: ~1자/토큰

## LLM 재시도 (RetryExecutor)

재시도 로직은 `RetryExecutor`로 추출되어 있으며, 선택적으로 `CircuitBreaker`를 래핑합니다.

### 지수 백오프 + 지터

```kotlin
// RetryExecutor.execute()
suspend fun <T> execute(block: suspend () -> T): T {
    val retryBlock: suspend () -> T = {
        repeat(maxAttempts) { attempt ->
            try {
                return@repeat block()
            } catch (e: Exception) {
                e.throwIfCancellation()  // 취소는 절대 재시도 안 함
                if (!isTransientError(e) || attempt == maxAttempts - 1) throw e

                // ±25% 지터를 포함한 지수 백오프
                val baseDelay = min(initialDelay * multiplier^attempt, maxDelay)
                val jitter = baseDelay * 0.25 * random(-1, 1)
                delay(baseDelay + jitter)
            }
        }
    }
    // 선택적으로 CircuitBreaker로 래핑
    return circuitBreaker?.execute(retryBlock) ?: retryBlock()
}
```

### 일시적 에러 판별

재시도 대상:
- Rate limit (429)
- Timeout
- 5xx 서버 에러
- Connection 에러

즉시 실패:
- 인증 에러
- Context too long
- Invalid request
- `CancellationException` (코루틴 취소 — 절대 재시도 불가)
- `CircuitBreakerOpenException` (회로 오픈 — 즉시 실패)

## 동시성 세마포어

```kotlin
concurrencySemaphore.withPermit {
    executeWithRequestTimeout(properties.concurrency.requestTimeoutMs) {
        agentExecutionCoordinator.execute(command, hookContext, toolsUsed, startTime)
    }
}
```

`Semaphore(permits = maxConcurrentRequests)`로 동시 에이전트 실행 수를 제한합니다. 허용량 초과 시 코루틴이 대기합니다.

## 에러 분류

```kotlin
// AgentErrorPolicy.classify()
fun classify(e: Exception): AgentErrorCode {
    return when {
        e is CircuitBreakerOpenException → CIRCUIT_BREAKER_OPEN
        "rate limit" in message          → RATE_LIMITED
        "timeout" in message             → TIMEOUT
        "context length" in msg          → CONTEXT_TOO_LONG
        "tool" in message                → TOOL_ERROR
        else                             → UNKNOWN
    }
}
```

에러 메시지 기반 분류로, `ErrorMessageResolver`를 통해 사용자 친화적 메시지로 변환됩니다.

## 시스템 프롬프트 구성

시스템 프롬프트 조립은 `SystemPromptBuilder`가 처리합니다:

```kotlin
// SystemPromptBuilder.build()
fun build(
    basePrompt: String,
    ragContext: String?,
    responseFormat: ResponseFormat = ResponseFormat.TEXT,
    responseSchema: String? = null,
    userPrompt: String? = null,
    workspaceToolAlreadyCalled: Boolean = false
): String {
    val parts = mutableListOf(basePrompt)
    parts.add(buildGroundingInstruction(...))  // 그라운딩 규칙

    if (ragContext != null) {
        parts.add(buildRagInstruction(ragContext))
    }

    when (responseFormat) {
        ResponseFormat.JSON -> parts.add(buildJsonInstruction(responseSchema))
        ResponseFormat.YAML -> parts.add(buildYamlInstruction(responseSchema))
        ResponseFormat.TEXT -> {}
    }

    val result = parts.joinToString("\n\n")
    return postProcessor?.process(result) ?: result  // 카나리 토큰 주입
}
```

최종 시스템 프롬프트 구조:

```
{사용자 정의 시스템 프롬프트}

[Grounding Rules]
{팩트 그라운딩 및 도구 호출 지시문}

[Retrieved Context]
{RAG에서 검색한 문서들}

[Response Format]
You MUST respond with valid JSON only.
Expected JSON schema: {...}
```

빌더는 YAML 응답 형식과 카나리 토큰 주입을 위한 선택적 `SystemPromptPostProcessor`도 지원합니다.

## 도구 선택 및 준비

도구 선택은 `ToolPreparationPlanner`가 처리합니다:

```kotlin
// ToolPreparationPlanner.prepareForPrompt()
fun prepareForPrompt(userPrompt: String): List<Any> {
    // 1. LocalTool (@Tool 어노테이션 기반), LocalToolFilters를 통해 필터링
    val localToolInstances = localToolFilters.fold(localTools.toList()) { acc, filter ->
        filter.filter(acc)
    }

    // 2. ToolCallback + MCP 도구 수집 후 도구 이름 기준 중복 제거
    val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks())

    // 3. ToolSelector로 필터링 (사용자 프롬프트 기반)
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks)
        ?: allCallbacks

    // 4. ArcToolCallbackAdapter로 래핑 (WeakHashMap 캐시)
    val wrappedCallbacks = selectedCallbacks.map(::resolveAdapter)

    // 5. maxToolsPerRequest 적용
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

콜백 이름이 중복되면 선택/래핑 전에 먼저 정리됩니다.
병합된 콜백 목록에서 먼저 등장한 콜백을 유지하고, 이후 중복 항목은 warning 로그를 남기고 제거합니다. 어댑터는 요청마다 새 래퍼를 생성하지 않도록 `WeakHashMap`에 캐시됩니다.

## ArcToolCallbackAdapter

Spring AI의 `ToolCallback`과 Arc Reactor의 `ToolCallback`을 연결하는 어댑터:

```kotlin
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback,
    fallbackToolTimeoutMs: Long = 15_000
) : org.springframework.ai.tool.ToolCallback {

    private val blockingInvoker = BlockingToolCallbackInvoker(fallbackToolTimeoutMs)

    override fun call(toolInput: String): String {
        val parsedArguments = parseToolArguments(toolInput)
        // suspend fun → blocking 변환 (Spring AI 인터페이스 제약)
        return blockingInvoker.invokeWithTimeout(arcCallback, parsedArguments)
    }
}
```

`BlockingToolCallbackInvoker`는 Spring AI 인터페이스가 동기 `call()`만 지원하기 때문에 `runBlocking(Dispatchers.IO)`와 `withTimeout`을 사용합니다. 도구별 타임아웃은 `toolCallback.timeoutMs`를 따르며, 설정이 없으면 `toolCallTimeoutMs`로 폴백합니다.

## CancellationException 규칙

모든 `suspend fun`에서 generic `Exception` catch 전에 `CancellationException`을 catch하고 rethrow해야 합니다. 코드베이스에서는 `throwIfCancellation()` 확장 함수로 이를 강제합니다:

```kotlin
try {
    // 비즈니스 로직
} catch (e: Exception) {
    e.throwIfCancellation()  // CancellationException이면 rethrow
    // 에러 처리
}
```

이 패턴은 `execute()`, `executeWithTools()`, `RetryExecutor.execute()` 등 **모든** suspend 함수에 적용됩니다. 누락 시 `withTimeout`이 정상 동작하지 않습니다.

> `java.util.concurrent.CancellationException`은 `kotlin.coroutines.cancellation.CancellationException`의 typealias입니다.
