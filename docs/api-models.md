# 데이터 모델 & API 레퍼런스

> **핵심 파일:** `AgentModels.kt`, `AgentErrorCode.kt`, `AgentMetrics.kt`, `ChatController.kt`
> 이 문서는 Arc Reactor의 핵심 데이터 모델, 에러 처리 체계, 메트릭 인터페이스, REST API를 설명합니다.

## 핵심 데이터 모델

### AgentCommand — 에이전트 실행 요청

에이전트에게 작업을 요청할 때 사용하는 입력 모델입니다.

```kotlin
data class AgentCommand(
    val systemPrompt: String,              // 시스템 프롬프트 (에이전트 역할 정의)
    val userPrompt: String,                // 사용자 프롬프트 (실제 질문/요청)
    val mode: AgentMode = AgentMode.REACT, // 실행 모드
    val conversationHistory: List<Message> = emptyList(),  // 초기 대화 히스토리
    val temperature: Double? = null,       // LLM 온도 (null이면 설정값 사용)
    val maxToolCalls: Int = 10,            // 이 요청의 최대 도구 호출 수
    val userId: String? = null,            // 사용자 ID (Guard, Memory에 사용)
    val metadata: Map<String, Any> = emptyMap(),  // 사용자 정의 메타데이터
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,  // 응답 형식
    val responseSchema: String? = null     // JSON 스키마 (JSON 모드 시)
)
```

**필드별 동작:**

| 필드 | 사용처 | 비고 |
|------|--------|------|
| `systemPrompt` | LLM 시스템 메시지 | RAG/JSON 지시문이 자동 추가됨 |
| `userPrompt` | LLM 사용자 메시지 + RAG 쿼리 | Guard 입력 검증 대상 |
| `mode` | 현재 `REACT`만 구현됨 | `STANDARD`, `STREAMING`은 예약됨 |
| `temperature` | `null`이면 `AgentProperties.llm.temperature` 사용 | 요청별 오버라이드 |
| `userId` | Guard(Rate Limit), Memory(세션), Hook(컨텍스트) | `null`이면 "anonymous" 폴백 |
| `metadata` | Hook 컨텍스트로 전달 | 감사 로그, 빌링 등에 활용 |
| `responseFormat` | `JSON`이면 시스템 프롬프트에 JSON 지시문 추가 | 스트리밍 모드에서 JSON 미지원 |

### AgentResult — 에이전트 실행 결과

```kotlin
data class AgentResult(
    val success: Boolean,                          // 성공 여부
    val content: String?,                          // LLM 응답 텍스트
    val errorCode: AgentErrorCode? = null,         // 에러 코드 (실패 시)
    val errorMessage: String? = null,              // 사용자 친화적 에러 메시지
    val toolsUsed: List<String> = emptyList(),     // 사용된 도구 이름 목록
    val tokenUsage: TokenUsage? = null,            // 토큰 사용량
    val durationMs: Long = 0,                      // 실행 시간 (ms)
    val metadata: Map<String, Any> = emptyMap()    // 추가 메타데이터
)
```

**팩토리 메서드:**

```kotlin
// 성공 결과
AgentResult.success(
    content = "응답 텍스트",
    toolsUsed = listOf("calculator", "datetime"),
    tokenUsage = TokenUsage(promptTokens = 500, completionTokens = 200),
    durationMs = 1500
)

// 실패 결과
AgentResult.failure(
    errorMessage = "요청 시간이 초과되었습니다.",
    errorCode = AgentErrorCode.TIMEOUT,
    durationMs = 30000
)
```

### TokenUsage — 토큰 사용량

```kotlin
data class TokenUsage(
    val promptTokens: Int,                         // 입력 토큰 수
    val completionTokens: Int,                     // 출력 토큰 수
    val totalTokens: Int = promptTokens + completionTokens  // 총 토큰 수
)
```

ReAct 루프 내 모든 LLM 호출의 토큰이 **누적**됩니다. 도구 호출 3회면 4번의 LLM 호출 토큰이 합산됩니다.

### ResponseFormat — 응답 형식

```kotlin
enum class ResponseFormat {
    TEXT,   // 일반 텍스트 (기본값)
    JSON    // JSON 응답 (시스템 프롬프트에 지시문 추가)
}
```

**JSON 모드 동작:**
- 시스템 프롬프트에 `"You MUST respond with valid JSON only."` 추가
- `responseSchema`가 있으면 `"Expected JSON schema: {스키마}"` 도 추가
- 스트리밍 모드에서는 사용 불가 (JSON 조각은 의미 없음)

### AgentMode — 실행 모드

```kotlin
enum class AgentMode {
    STANDARD,   // 단일 응답 (도구 호출 없음) — 미구현
    REACT,      // ReAct 루프 (기본값)
    STREAMING   // 스트리밍 — 예약됨
}
```

현재 `REACT`만 구현되어 있습니다. `executeStream()`은 `AgentMode`와 무관하게 스트리밍으로 동작합니다.

### Message — 대화 메시지

```kotlin
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now()
)

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}
```

`AgentCommand.conversationHistory`로 초기 대화 히스토리를 전달할 때 사용합니다. Memory에서 로드된 히스토리와 합쳐져 LLM에 전달됩니다.

---

## 에러 처리 체계

### AgentErrorCode — 에러 분류

```kotlin
enum class AgentErrorCode(val defaultMessage: String) {
    RATE_LIMITED("Rate limit exceeded. Please try again later."),
    TIMEOUT("Request timed out."),
    CONTEXT_TOO_LONG("Input is too long. Please reduce the content."),
    TOOL_ERROR("An error occurred during tool execution."),
    GUARD_REJECTED("Request rejected by guard."),
    HOOK_REJECTED("Request rejected by hook."),
    UNKNOWN("An unknown error occurred.")
}
```

**에러 코드별 발생 조건:**

| 코드 | 발생 조건 | 재시도 여부 |
|------|-----------|-------------|
| `RATE_LIMITED` | Guard Rate Limit 또는 LLM 429 응답 | LLM 429만 재시도 |
| `TIMEOUT` | `withTimeout()` 초과 또는 LLM 타임아웃 | LLM만 재시도 |
| `CONTEXT_TOO_LONG` | LLM 컨텍스트 초과 에러 | 재시도 안 함 |
| `TOOL_ERROR` | 도구 실행 중 예외 | 재시도 안 함 |
| `GUARD_REJECTED` | Guard 파이프라인 Reject | 재시도 안 함 |
| `HOOK_REJECTED` | BeforeAgentStart/BeforeToolCall Hook Reject | 재시도 안 함 |
| `UNKNOWN` | 분류 불가 예외 | 재시도 안 함 |

### classifyError — 에러 메시지 기반 분류

```kotlin
private fun classifyError(e: Exception): AgentErrorCode {
    val message = e.message?.lowercase() ?: ""
    return when {
        "rate limit" in message  → RATE_LIMITED
        "timeout" in message     → TIMEOUT
        "context length" in message → CONTEXT_TOO_LONG
        "tool" in message        → TOOL_ERROR
        else                     → UNKNOWN
    }
}
```

에러 메시지의 키워드로 분류합니다. LLM provider마다 에러 메시지 형식이 다를 수 있으므로, 정확하지 않을 수 있습니다.

### ErrorMessageResolver — 에러 메시지 로컬라이제이션

```kotlin
fun interface ErrorMessageResolver {
    fun resolve(code: AgentErrorCode, originalMessage: String?): String
}
```

**기본 구현 (영어):**

```kotlin
class DefaultErrorMessageResolver : ErrorMessageResolver {
    override fun resolve(code: AgentErrorCode, originalMessage: String?): String {
        return when (code) {
            AgentErrorCode.TOOL_ERROR ->
                if (originalMessage != null) "${code.defaultMessage}: $originalMessage"
                else code.defaultMessage
            else -> code.defaultMessage
        }
    }
}
```

**커스텀 예시 (한국어):**

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, originalMessage ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
        AgentErrorCode.CONTEXT_TOO_LONG -> "입력이 너무 깁니다. 내용을 줄여주세요."
        AgentErrorCode.TOOL_ERROR -> "도구 실행 중 오류: $originalMessage"
        AgentErrorCode.GUARD_REJECTED -> "요청이 거부되었습니다."
        AgentErrorCode.HOOK_REJECTED -> "요청이 거부되었습니다."
        AgentErrorCode.UNKNOWN -> "알 수 없는 오류가 발생했습니다."
    }
}
```

`@Bean`으로 등록하면 `@ConditionalOnMissingBean`에 의해 기본 구현이 대체됩니다.

### 에러 흐름 전체

```
예외 발생
    │
    ▼
classifyError(e) → AgentErrorCode
    │
    ▼
errorMessageResolver.resolve(code, e.message) → 사용자 메시지
    │
    ▼
AgentResult.failure(errorMessage, errorCode, durationMs)
    │
    ▼
ChatResponse(success=false, errorMessage=...)
```

---

## 관찰 가능성 (Observability)

### AgentMetrics 인터페이스

```kotlin
interface AgentMetrics {
    fun recordExecution(result: AgentResult)
    fun recordToolCall(toolName: String, durationMs: Long, success: Boolean)
    fun recordGuardRejection(stage: String, reason: String)
}
```

**기본 구현:** `NoOpAgentMetrics` (아무것도 하지 않음)

**메서드별 호출 시점:**

| 메서드 | 호출 시점 | 데이터 |
|--------|-----------|--------|
| `recordExecution` | `executeInternal()` 완료 후 | success, durationMs, toolsUsed, tokenUsage |
| `recordToolCall` | 개별 도구 실행 완료 후 | toolName, durationMs, success |
| `recordGuardRejection` | Guard Reject 시 | stageName, reason |

### Micrometer 구현 예시

```kotlin
class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {
    private val executionCounter = registry.counter("arc.agent.executions")
    private val executionTimer = registry.timer("arc.agent.execution.duration")
    private val errorCounter = registry.counter("arc.agent.errors")
    private val toolCounter = registry.counter("arc.agent.tool.calls")

    override fun recordExecution(result: AgentResult) {
        executionCounter.increment()
        executionTimer.record(result.durationMs, TimeUnit.MILLISECONDS)
        if (!result.success) errorCounter.increment()
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        toolCounter.increment()
        registry.timer("arc.agent.tool.duration", "tool", toolName)
            .record(durationMs, TimeUnit.MILLISECONDS)
        if (!success) {
            registry.counter("arc.agent.tool.errors", "tool", toolName).increment()
        }
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        registry.counter("arc.agent.guard.rejections", "stage", stage).increment()
    }
}
```

---

## REST API

### POST /api/chat — 일반 응답

전체 응답을 한 번에 반환합니다.

**요청:**

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "3 + 5는 얼마야?",
    "userId": "user-1"
  }'
```

**ChatRequest:**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `message` | String | **필수** | 사용자 메시지 (`@NotBlank`) |
| `systemPrompt` | String? | 선택 | 시스템 프롬프트 (없으면 기본값 사용) |
| `userId` | String? | 선택 | 사용자 ID |
| `metadata` | Map<String, Any>? | 선택 | 추가 메타데이터 |
| `responseFormat` | ResponseFormat? | 선택 | `TEXT` 또는 `JSON` |
| `responseSchema` | String? | 선택 | JSON 스키마 (JSON 모드 시) |

**ChatResponse:**

| 필드 | 타입 | 설명 |
|------|------|------|
| `content` | String? | AI 응답 텍스트 (실패 시 null) |
| `success` | Boolean | 성공 여부 |
| `toolsUsed` | List<String> | 사용된 도구 목록 |
| `errorMessage` | String? | 에러 메시지 (실패 시) |

**응답 예시 (성공):**

```json
{
  "content": "3 + 5 = 8입니다.",
  "success": true,
  "toolsUsed": ["calculator"],
  "errorMessage": null
}
```

**응답 예시 (실패):**

```json
{
  "content": null,
  "success": false,
  "toolsUsed": [],
  "errorMessage": "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
}
```

### POST /api/chat/stream — 스트리밍 응답

SSE (Server-Sent Events)로 실시간 토큰 단위 응답을 반환합니다.

**요청:**

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "안녕하세요", "userId": "user-1"}'
```

**응답:** `Content-Type: text/event-stream`

```
data:안녕
data:하세요
data:! 무엇을
data: 도와드릴까요
data:?
```

**에러 시:**

```
data:[error] 요청 시간이 초과되었습니다.
```

**내부 변환:** `Flow<String>` → `Flux<String>` (`flow.asFlux()`)

### 기본 시스템 프롬프트

`systemPrompt`를 지정하지 않으면 다음 기본값이 사용됩니다:

```
You are a helpful AI assistant. You can use tools when needed.
Answer in the same language as the user's message.
```

### ChatRequest → AgentCommand 변환

```kotlin
AgentCommand(
    systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
    userPrompt = request.message,
    userId = request.userId,
    metadata = request.metadata ?: emptyMap(),
    responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
    responseSchema = request.responseSchema
)
```

`ChatRequest`는 `AgentCommand`의 서브셋입니다. `mode`, `temperature`, `maxToolCalls`, `conversationHistory` 등은 REST API에서 설정 불가하며 기본값이 사용됩니다. 프로그래밍 방식으로 `AgentExecutor`를 직접 호출하면 모든 옵션을 사용할 수 있습니다.
