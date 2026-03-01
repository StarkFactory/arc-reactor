# ReAct 루프 내부 구현

> **핵심 파일:** `SpringAiAgentExecutor.kt` (~1,730줄)
> 이 문서는 Arc Reactor의 핵심 실행 엔진인 ReAct 루프의 내부 동작을 설명합니다.

## 전체 실행 흐름

```
사용자 요청 (AgentCommand)
    │
    ▼
┌─────────────────────────────┐
│  execute() / executeStream()│  진입점 (2가지)
│  ├─ MDC 설정                │
│  ├─ 동시성 세마포어 확보      │
│  └─ withTimeout 적용        │
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│  executeInternal()          │  8단계 파이프라인
│  1. Guard 검사              │
│  2. BeforeAgentStart Hook   │
│  3. 대화 히스토리 로드        │
│  4. RAG 컨텍스트 검색        │
│  5. 도구 선택 및 준비        │
│  6. executeWithTools()      │  ← ReAct 루프 본체
│  7. 대화 히스토리 저장        │
│  8. AfterAgentComplete Hook │
└─────────────────────────────┘
```

## 진입점: execute() vs executeStream()

### execute() — 비스트리밍

```kotlin
override suspend fun execute(command: AgentCommand): AgentResult
```

1. MDC에 `runId`, `userId`, `sessionId` 설정 (구조적 로깅)
2. `concurrencySemaphore.withPermit { }` — 동시 실행 수 제한
3. `withTimeout(requestTimeoutMs)` — 요청 전체 타임아웃
4. `executeInternal()` 호출
5. 실패 시 `classifyError(e)` → `AgentResult.failure()` 반환
6. `CancellationException`은 반드시 rethrow (구조적 동시성 보장)

### executeStream() — 스트리밍

```kotlin
override fun executeStream(command: AgentCommand): Flow<String>
```

비스트리밍과 동일한 8단계를 따르되, 다음이 다릅니다:

| 차이점 | execute() | executeStream() |
|--------|-----------|-----------------|
| 반환 타입 | `AgentResult` | `Flow<String>` |
| LLM 호출 | `requestSpec.call()` | `requestSpec.stream().chatResponse()` |
| 콘텐츠 수집 | 최종 응답에서 한 번에 | 청크 단위로 `emit()` |
| 메모리 저장 | `saveHistory()` | `saveStreamingHistory()` (finally 블록) |
| 에러 전달 | AgentResult.failure() | `emit("[error] message")` |

**스트리밍 메모리 저장의 핵심:**

```kotlin
// withTimeout 밖의 finally 블록에서 저장
finally {
    if (streamSuccess) {
        conversationManager.saveStreamingHistory(command, lastIterationContent.toString())
    }
}
```

`lastIterationContent`만 저장합니다. 전체 누적 콘텐츠(`collectedContent`)가 아닌, 마지막 ReAct 반복의 콘텐츠만 대화 기록에 남깁니다.

## ReAct 루프 본체: executeWithTools()

```kotlin
private suspend fun executeWithTools(
    command: AgentCommand,
    tools: List<Any>,
    conversationHistory: List<Message>,
    hookContext: HookContext,
    toolsUsed: MutableList<String>,
    ragContext: String? = null
): AgentResult
```

### 루프 구조

```
while (true) {
    1. trimMessagesToFitContext()     — 토큰 예산 내로 메시지 정리
    2. chatClient.prompt() 구성       — system + messages + options + tools
    3. callWithRetry { call() }      — LLM 호출 (재시도 포함)
    4. 토큰 사용량 누적
    5. 도구 호출 감지
       ├─ 없음 → AgentResult.success() 반환 (루프 종료)
       └─ 있음 → 계속
    6. AssistantMessage 추가          — 도구 호출 정보 포함
    7. executeToolCallsInParallel()  — 병렬 도구 실행
    8. ToolResponseMessage 추가       — 도구 결과
    9. maxToolCalls 체크
       ├─ 미도달 → 루프 계속
       └─ 도달 → activeTools = emptyList() (도구 제거)
}
```

### 도구 호출 감지

```kotlin
val assistantOutput = chatResponse?.results?.firstOrNull()?.output
val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()

if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
    // 최종 응답 반환
    return AgentResult.success(content = response.content() ?: "")
}
```

LLM 응답의 `output.toolCalls`가 비어 있으면 최종 텍스트 답변으로 판단합니다.

### maxToolCalls 강제 종료

```kotlin
if (totalToolCalls >= maxToolCalls) {
    activeTools = emptyList()           // 도구 제거
    chatOptions = buildChatOptions(command, false)  // 도구 없는 옵션
}
```

도구를 빈 리스트로 교체하면, 다음 LLM 호출에서 도구 호출 없이 최종 텍스트 답변을 생성합니다. 단순히 로그만 찍으면 LLM이 계속 도구를 호출하려 하므로, **도구 자체를 제거**하는 것이 핵심입니다.

## 병렬 도구 실행

### 실행 패턴

```kotlin
private suspend fun executeToolCallsInParallel(
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
private suspend fun executeSingleToolCall(...): ToolResponseMessage.ToolResponse {
    // 1. AtomicInteger로 maxToolCalls 체크 (동시성 안전)
    val currentCount = totalToolCallsCounter.getAndIncrement()
    if (currentCount >= maxToolCalls) return 에러 응답

    // 2. BeforeToolCall Hook
    hookExecutor?.executeBeforeToolCall(toolCallContext)
    // → Reject 시 해당 도구만 스킵

    // 3. 도구 실행
    val adapter = findToolAdapter(toolName, tools)
    if (adapter != null) {
        toolsUsed.add(toolName)        // adapter 존재 확인 후에만 추가
        adapter.call(toolCall.arguments())
    } else {
        "Error: Tool '$toolName' not found"  // LLM hallucination 방지
    }

    // 4. AfterToolCall Hook
    hookExecutor?.executeAfterToolCall(context, result)

    // 5. 메트릭 기록
    agentMetrics.recordToolCall(toolName, durationMs, success)
}
```

**주의:** `toolsUsed.add(toolName)`은 adapter 존재를 확인한 후에만 호출됩니다. LLM이 존재하지 않는 도구 이름을 생성(hallucination)할 수 있기 때문입니다.

## 컨텍스트 윈도우 관리

### 토큰 예산 계산

```
budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens
```

예: `128000 - 2000 - 4096 = 121904 토큰`

### 2단계 트리밍

```kotlin
private fun trimMessagesToFitContext(messages: MutableList<Message>, systemPrompt: String)
```

**Phase 1: 오래된 히스토리 제거 (앞에서부터)**

```
[히스토리 메시지들...] [현재 UserMessage] [도구 상호작용들...]
 ↑ 여기서부터 제거
```

가장 오래된 메시지부터 순서대로 제거합니다. 단, **마지막 UserMessage(현재 프롬프트)**에 도달하면 멈춥니다.

**Phase 2: 도구 상호작용 제거 (UserMessage 뒤에서)**

Phase 1로 부족하면, 현재 UserMessage 뒤의 도구 호출/응답 쌍을 제거합니다.

### 메시지 쌍 무결성

```kotlin
private fun calculateRemoveGroupSize(messages: List<Message>): Int {
    val first = messages[0]
    // AssistantMessage(toolCalls) → ToolResponseMessage 쌍으로 제거
    if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
        return if (messages[1] is ToolResponseMessage) 2 else 1
    }
    return 1
}
```

`AssistantMessage(toolCalls)`와 `ToolResponseMessage`는 반드시 쌍으로 제거됩니다. 하나만 남으면 LLM API 에러가 발생합니다.

### 토큰 추정

```kotlin
private fun estimateMessageTokens(message: Message): Int {
    return when (message) {
        is UserMessage -> tokenEstimator.estimate(message.text)
        is AssistantMessage -> {
            val textTokens = tokenEstimator.estimate(message.text ?: "")
            val toolCallTokens = message.toolCalls.sumOf {
                tokenEstimator.estimate(it.name() + it.arguments())
            }
            textTokens + toolCallTokens
        }
        is ToolResponseMessage -> message.responses.sumOf {
            tokenEstimator.estimate(it.responseData())
        }
        // ...
    }
}
```

`TokenEstimator`는 CJK 문자를 인식합니다:
- Latin 문자: ~4자/토큰
- 한글/CJK: ~1.5자/토큰
- 이모지: ~1자/토큰

## LLM 재시도 (callWithRetry)

### 지수 백오프 + 지터

```kotlin
private suspend fun <T> callWithRetry(block: suspend () -> T): T {
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e  // 절대 재시도 안 함
        } catch (e: Exception) {
            if (!isTransientError(e) || 마지막 시도) throw e

            // 지수 백오프: 1s → 2s → 4s (최대 10s)
            val baseDelay = min(initialDelay * 2^attempt, maxDelay)
            // ±25% 지터: 동시 재시도 분산
            val jitter = baseDelay * 0.25 * random(-1, 1)
            delay(baseDelay + jitter)
        }
    }
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

## 동시성 세마포어

```kotlin
concurrencySemaphore.withPermit {
    withTimeout(properties.concurrency.requestTimeoutMs) {
        executeInternal(command, hookContext, toolsUsed, startTime)
    }
}
```

`Semaphore(permits = maxConcurrentRequests)`로 동시 에이전트 실행 수를 제한합니다. 허용량 초과 시 코루틴이 대기합니다.

## 에러 분류

```kotlin
private fun classifyError(e: Exception): AgentErrorCode {
    return when {
        "rate limit" in message  → RATE_LIMITED
        "timeout" in message     → TIMEOUT
        "context length" in msg  → CONTEXT_TOO_LONG
        "tool" in message        → TOOL_ERROR
        else                     → UNKNOWN
    }
}
```

에러 메시지 기반 분류로, `ErrorMessageResolver`를 통해 사용자 친화적 메시지로 변환됩니다.

## 시스템 프롬프트 구성

```kotlin
private fun buildSystemPrompt(
    basePrompt: String,
    ragContext: String?,
    responseFormat: ResponseFormat,
    responseSchema: String?
): String {
    val parts = mutableListOf(basePrompt)

    // RAG 컨텍스트 추가
    if (ragContext != null) {
        parts.add("[Retrieved Context]\n$ragContext")
    }

    // JSON 모드 지시문 추가
    if (responseFormat == ResponseFormat.JSON) {
        parts.add("[Response Format]\nYou MUST respond with valid JSON only.")
        if (responseSchema != null) {
            parts.add("Expected JSON schema:\n$responseSchema")
        }
    }

    return parts.joinToString("\n\n")
}
```

최종 시스템 프롬프트 구조:

```
{사용자 정의 시스템 프롬프트}

[Retrieved Context]
{RAG에서 검색한 문서들}

[Response Format]
You MUST respond with valid JSON only.
Expected JSON schema: {...}
```

## 도구 선택 및 준비

```kotlin
private fun selectAndPrepareTools(userPrompt: String): List<Any> {
    // 1. LocalTool (@Tool 어노테이션 기반)
    val localToolInstances = localTools.toList()

    // 2. ToolCallback + MCP 도구 수집 후 도구 이름 기준 중복 제거
    val allCallbacks = deduplicateCallbacks(toolCallbacks + mcpToolCallbacks())

    // 3. ToolSelector로 필터링 (사용자 프롬프트 기반)
    val selectedCallbacks = toolSelector?.select(userPrompt, allCallbacks)
        ?: allCallbacks

    // 4. ArcToolCallbackAdapter로 래핑
    val wrappedCallbacks = selectedCallbacks.map { ArcToolCallbackAdapter(it) }

    // 5. maxToolsPerRequest 적용
    return (localToolInstances + wrappedCallbacks).take(maxToolsPerRequest)
}
```

콜백 이름이 중복되면 선택/래핑 전에 먼저 정리됩니다.
병합된 콜백 목록에서 먼저 등장한 콜백을 유지하고, 이후 중복 항목은 warning 로그를 남기고 제거합니다.

## ArcToolCallbackAdapter

Spring AI의 `ToolCallback`과 Arc Reactor의 `ToolCallback`을 연결하는 어댑터:

```kotlin
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback
) : org.springframework.ai.tool.ToolCallback {

    override fun call(toolInput: String): String {
        val args = parseJsonToMap(toolInput)
        // suspend fun → blocking 변환 (Spring AI 인터페이스 제약)
        return runBlocking(Dispatchers.IO) {
            arcCallback.call(args)?.toString() ?: ""
        }
    }
}
```

`runBlocking(Dispatchers.IO)` 사용은 Spring AI 인터페이스가 동기 `call()`만 지원하기 때문입니다. 이는 알려진 제약사항입니다.

## CancellationException 규칙

모든 `suspend fun`에서 generic `Exception` catch 전에 `CancellationException`을 catch하고 rethrow해야 합니다:

```kotlin
try {
    // 비즈니스 로직
} catch (e: CancellationException) {
    throw e  // 구조적 동시성 보장
} catch (e: Exception) {
    // 에러 처리
}
```

이 패턴은 `execute()`, `executeWithTools()`, `callWithRetry()` 등 **모든** suspend 함수에 적용됩니다. 누락 시 `withTimeout`이 정상 동작하지 않습니다.

> `java.util.concurrent.CancellationException`은 `kotlin.coroutines.cancellation.CancellationException`의 typealias입니다.
