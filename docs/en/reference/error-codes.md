# Error Code Reference

Arc Reactor uses a standardized error model for all agent execution failures. Every failed
response from `POST /api/chat` or the streaming endpoint carries a `success: false` flag,
an `errorMessage` string (human-readable, optionally localized), and — in the `AgentResult`
payload returned by the executor — an `errorCode` enum value.

This document covers every error code defined in `AgentErrorCode`, the HTTP status code
returned to clients, when each code is raised, how clients should respond, and an example
error response body.

---

## Error Response Shape

All chat failures use the `ChatResponse` DTO. HTTP status is always `200` for the chat
endpoints; the agent-level error is communicated in the response body, not the HTTP layer.

```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "<human-readable message>"
}
```

Infrastructure-level failures (validation, file-size limits, uncaught exceptions) are handled
by `GlobalExceptionHandler` and do use non-200 HTTP status codes. Those are described at the
end of this document.

---

## Agent Error Codes

### `RATE_LIMITED`

| Field | Value |
|---|---|
| Default message | `Rate limit exceeded. Please try again later.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The incoming request exceeds the per-minute or per-hour rate limit enforced by the Guard pipeline |

**Details.** The Guard applies two sliding-window counters per user ID: `rate-limit-per-minute`
and `rate-limit-per-hour`. When either counter is exceeded, the Guard rejects the request
fail-close and the executor returns `RATE_LIMITED`.

Default limits (configurable):
- 10 requests per minute (`arc.reactor.guard.rate-limit-per-minute`)
- 100 requests per hour (`arc.reactor.guard.rate-limit-per-hour`)

**Client action.** Back off and retry after the window resets. Use exponential back-off with
jitter. Do not retry immediately; a burst of retries will extend the window.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Rate limit exceeded. Please try again later."
}
```

---

### `TIMEOUT`

| Field | Value |
|---|---|
| Default message | `Request timed out.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The total wall-clock time for the request exceeds `concurrency.request-timeout-ms` (default 30 000 ms), or an individual tool call exceeds `concurrency.tool-call-timeout-ms` (default 15 000 ms) |

**Details.** The executor wraps each request in a `withTimeout` coroutine block. When the
deadline is reached, `kotlinx.coroutines.TimeoutCancellationException` is thrown and mapped
to `TIMEOUT`. Additionally, provider-side timeout messages (e.g. `"timed out"`) in exception
messages are classified as `TIMEOUT` by `AgentErrorPolicy`.

**Client action.** Retry with the same request. Timeouts are classified as transient errors;
the executor's built-in retry logic (up to `retry.max-attempts`, default 3) will attempt
retries automatically before surfacing the error to the client. If timeouts recur consistently,
reduce message complexity or increase timeout budgets.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Request timed out."
}
```

---

### `CONTEXT_TOO_LONG`

| Field | Value |
|---|---|
| Default message | `Input is too long. Please reduce the content.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The LLM provider returns a context-length error (e.g. `"context length exceeded"` in the exception message) |

**Details.** Arc Reactor performs token-based context trimming before each LLM call using
`maxContextWindowTokens` (default 128 000 tokens). The trimmer protects the most-recent user
message and trims older conversation history. If the trimmer cannot reduce the prompt
sufficiently (e.g. a single user message is itself too long), the provider call fails and
`AgentErrorPolicy` classifies the exception as `CONTEXT_TOO_LONG`.

**Client action.** Shorten the user message, reduce the system prompt, or clear conversation
history. Consider increasing `max-context-window-tokens` if the model supports a larger window.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Input is too long. Please reduce the content."
}
```

---

### `TOOL_ERROR`

| Field | Value |
|---|---|
| Default message | `An error occurred during tool execution.: <original message>` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | A `ToolCallback` throws or returns an error string, and the ReAct loop exhausts retries without recovery |

**Details.** ToolCallback implementations must return errors as strings (`"Error: ..."`)
rather than throwing exceptions. However, if an unhandled exception propagates from a tool and
its message contains `"tool"`, `AgentErrorPolicy` classifies it as `TOOL_ERROR`. The
`DefaultErrorMessageResolver` appends the original message to the default prefix.

**Client action.** This error usually indicates a third-party tool or MCP server failure.
Inspect the `errorMessage` field for the underlying cause. The error is classified as transient
if the tool exception message matches patterns like `"connection refused"`, `"timeout"`, or
`"service unavailable"`, triggering automatic retry.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": ["search_web"],
  "errorMessage": "An error occurred during tool execution.: connection refused to http://tool-server:8081"
}
```

---

### `GUARD_REJECTED`

| Field | Value |
|---|---|
| Default message | `Request rejected by guard.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | Any of the 5 Guard stages rejects the request (injection detection, rate limiting, input length, classification, canary token) |

**Details.** The Guard pipeline is fail-close: any stage that rejects a request immediately
stops processing and returns `GUARD_REJECTED`. The Guard operates before any LLM call or tool
execution. Common rejection scenarios:
- Unicode normalization detects a zero-width character ratio above `max-zero-width-ratio`
- Prompt injection pattern is matched
- Input length exceeds `boundaries.input-max-chars` (default 5 000 characters)
- Rule-based or LLM-based classification identifies a blocked category
- Canary token leak is detected in the input

**Client action.** The request itself was problematic. Do not retry without modifying the
input. Examine the `errorMessage` for hints; however, detailed rejection reasons are logged
server-side and not exposed to clients (by design, to avoid leaking Guard logic).

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": null,
  "toolsUsed": [],
  "errorMessage": "Request rejected by guard."
}
```

---

### `HOOK_REJECTED`

| Field | Value |
|---|---|
| Default message | `Request rejected by hook.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | A lifecycle Hook configured with `failOnError=true` throws an exception during the `BeforeStart` phase |

**Details.** Hooks are fail-open by default (log and continue). Setting `failOnError=true` on
a Hook makes it fail-close: an exception in that Hook will abort the request and return
`HOOK_REJECTED`. This code is also raised if the `BeforeStart` hook throws an exception that
the executor catches and maps through `PreExecutionResolver`.

**Client action.** This is a server-side configuration issue, not a client error. Contact the
operator. Retrying is unlikely to help unless the Hook failure is transient.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": null,
  "toolsUsed": [],
  "errorMessage": "Request rejected by hook."
}
```

---

### `INVALID_RESPONSE`

| Field | Value |
|---|---|
| Default message | `LLM returned an invalid structured response.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The LLM response does not conform to the requested `responseFormat` (JSON or YAML) after one repair attempt |

**Details.** When `responseFormat` is `JSON` or `YAML`, the response is validated by Jackson
or SnakeYAML after stripping markdown code fences. If validation fails, `StructuredResponseRepairer`
makes one additional LLM call asking the model to fix the output. If the repaired response
still fails validation, `INVALID_RESPONSE` is returned. Streaming mode always rejects non-TEXT
formats immediately without attempting repair.

**Client action.** Verify that the `responseSchema` is clear and that the model you are using
supports structured output reliably. Consider including explicit JSON/YAML formatting
instructions in the system prompt. Switching to `responseFormat: TEXT` and parsing the output
client-side is a fallback option.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "LLM returned an invalid structured response."
}
```

---

### `OUTPUT_GUARD_REJECTED`

| Field | Value |
|---|---|
| Default message | `Response blocked by output guard.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The Output Guard pipeline rejects the LLM's response (PII detected, custom block pattern matched) |

**Details.** When `arc.reactor.output-guard.enabled=true`, every LLM response passes through
the Output Guard before being returned to the caller. The built-in stages include PII masking
(controlled by `pii-masking-enabled`) and custom regex patterns configured under
`custom-patterns`. A pattern with action `REJECT` causes the response to be blocked entirely
with `OUTPUT_GUARD_REJECTED`.

**Client action.** The response was generated but blocked on the server. This is by design.
Clients cannot retrieve the blocked content. If this occurs unexpectedly, contact the operator
to review the Output Guard configuration.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Response blocked by output guard."
}
```

---

### `OUTPUT_TOO_SHORT`

| Field | Value |
|---|---|
| Default message | `Response is too short to meet quality requirements.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The LLM response is shorter than `boundaries.output-min-chars` and `boundaries.output-min-violation-mode` is set to `FAIL` |

**Details.** Output length enforcement is opt-in; `output-min-chars` defaults to 0 (disabled).
When enabled, three violation modes are available:
- `WARN` (default): log a warning, pass through the short response.
- `RETRY_ONCE`: make one additional LLM call requesting a longer response; fall back to `WARN` if still short.
- `FAIL`: return `OUTPUT_TOO_SHORT` immediately without passing the response.

**Client action.** Retry the request. If this error recurs, the model may be producing
consistently short responses for the given prompt. Adjust the system prompt to request more
detailed output, or reduce the `output-min-chars` threshold.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Response is too short to meet quality requirements."
}
```

---

### `CIRCUIT_BREAKER_OPEN`

| Field | Value |
|---|---|
| Default message | `Service temporarily unavailable due to repeated failures. Please try again later.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | The circuit breaker is in `OPEN` state because consecutive failures have exceeded `circuit-breaker.failure-threshold` |

**Details.** The circuit breaker is disabled by default (`arc.reactor.circuit-breaker.enabled=false`).
When enabled, it tracks consecutive LLM/tool call failures. After `failure-threshold` (default 5)
consecutive failures, the circuit opens and all subsequent requests immediately return
`CIRCUIT_BREAKER_OPEN` without calling the LLM. After `reset-timeout-ms` (default 30 000 ms),
the circuit transitions to `HALF_OPEN` and allows `half-open-max-calls` (default 1) trial
request(s). If the trial succeeds, the circuit closes; if it fails, the circuit opens again.

**Client action.** The upstream LLM or tool service is experiencing repeated failures. Wait
`reset-timeout-ms` before retrying. Implement exponential back-off in the client.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "Service temporarily unavailable due to repeated failures. Please try again later."
}
```

---

### `UNKNOWN`

| Field | Value |
|---|---|
| Default message | `An unknown error occurred.` |
| HTTP status (chat endpoint) | `200` (body: `success: false`) |
| When raised | An exception occurred that does not match any other classification pattern in `AgentErrorPolicy` |

**Details.** `UNKNOWN` is the catch-all classification in `AgentErrorPolicy.classify()`. It
is raised when an exception's message does not contain any of the keyword patterns that map
to more specific codes. The original exception is logged server-side at ERROR level.

**Client action.** Retry once. If the error persists, capture the request payload and
timestamp and contact the operator; the full stack trace is available in the server logs.

**Example response:**
```json
{
  "content": null,
  "success": false,
  "model": "gemini-2.0-flash",
  "toolsUsed": [],
  "errorMessage": "An unknown error occurred."
}
```

---

## Infrastructure Errors (HTTP-level)

These are raised by `GlobalExceptionHandler` and return non-200 HTTP status codes. They
occur before the agent executor runs, so they do not carry an `AgentErrorCode`.

| Scenario | HTTP Status | Error body |
|---|---|---|
| Validation failure (`@Valid` constraint) | `400` | `{"error": "Validation failed", "details": {"field": "message"}, "timestamp": "..."}` |
| Malformed request body | `400` | `{"error": "Invalid request: <reason>", "timestamp": "..."}` |
| File upload size exceeded | `400` | `{"error": "<reason>", "timestamp": "..."}` |
| Route not found | `404` | `{"error": "Not found", "timestamp": "..."}` |
| Unhandled server exception | `500` | `{"error": "Internal server error", "timestamp": "..."}` |

**Example validation error (400):**
```json
{
  "error": "Validation failed",
  "details": {
    "message": "message must not be blank"
  },
  "timestamp": "2026-02-28T10:00:00.000Z"
}
```

---

## Customizing Error Messages

All `AgentErrorCode` messages can be localized or overridden by registering an
`ErrorMessageResolver` bean:

```kotlin
@Bean
fun koreanErrorMessageResolver(): ErrorMessageResolver = ErrorMessageResolver { code, originalMessage ->
    when (code) {
        AgentErrorCode.RATE_LIMITED    -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        AgentErrorCode.TIMEOUT         -> "요청 시간이 초과되었습니다."
        AgentErrorCode.CONTEXT_TOO_LONG -> "입력이 너무 깁니다. 내용을 줄여주세요."
        AgentErrorCode.TOOL_ERROR      -> "도구 실행 중 오류가 발생했습니다: $originalMessage"
        else -> code.defaultMessage
    }
}
```

The custom bean is picked up via `@ConditionalOnMissingBean` in `ArcReactorAutoConfiguration`.
