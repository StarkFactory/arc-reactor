# Streaming ReAct Loop

## 이게 뭔데?

기존 `execute()`는 LLM 응답이 완전히 끝날 때까지 기다렸다가 한 번에 결과를 돌려줌.
`executeStream()`은 LLM이 글자를 생성하는 즉시 실시간으로 사용자에게 전달함.

**핵심 변경**: 기존 스트리밍은 텍스트만 그대로 흘려보냈지만, 이제는 **도구(Tool) 호출도 감지하고 실행**함.

```
[기존] 사용자 → LLM 스트리밍 → 텍스트 그대로 전달 (도구 호출 불가)
[변경] 사용자 → LLM 스트리밍 → 도구 감지 → 도구 실행 → 다시 스트리밍 → 최종 응답
```

---

## Before / After 비교

### Before (기존 executeStream)

```kotlin
// 단순 패스스루: 텍스트만 그대로 흘림
val flux = requestSpec.stream().content()  // Flux<String>
emitAll(flux.asFlow())
```

**문제점:**
- 도구 호출 감지 안 됨 (ReAct 루프 없음)
- Semaphore 동시성 제어 없음
- Timeout 없음
- BeforeToolCallHook / AfterToolCallHook 안 돌아감
- 대화 히스토리 저장 안 됨
- AfterAgentComplete Hook 안 돌아감
- Metrics 기록 안 됨

### After (새로운 executeStream)

```kotlin
// 구조화된 ChatResponse로 스트리밍
val flux = requestSpec.stream().chatResponse()  // Flux<ChatResponse>

flux.asFlow().collect { chunk ->
    // 텍스트 → 실시간 전달
    chunk.result?.output?.text?.let { emit(it) }
    // 도구 호출 → 감지해서 저장
    chunk.result?.output?.toolCalls?.let { pendingToolCalls = it }
}

// 도구 호출 감지됐으면 → 실행 → 다시 스트리밍 (ReAct 루프)
```

**해결된 것들:**
- 도구 호출 감지 + 실행 (전체 ReAct 루프)
- Semaphore 동시성 제어
- Timeout 적용
- BeforeToolCallHook / AfterToolCallHook 정상 동작
- 대화 히스토리 자동 저장
- AfterAgentComplete Hook 정상 동작
- Metrics 정상 기록
- MDC 로깅 컨텍스트

---

## 동작 흐름

```
사용자: "서울 날씨 알려줘"
          │
          ▼
    ┌─────────────┐
    │  Guard 체크   │ ← Rate Limit, Injection Detection 등
    └──────┬──────┘
           │ (통과)
           ▼
    ┌─────────────┐
    │ Before Hook  │ ← BeforeAgentStartHook
    └──────┬──────┘
           │ (통과)
           ▼
    ┌─────────────────────────────────────┐
    │         Streaming ReAct Loop         │
    │                                      │
    │  1. LLM 스트리밍 시작                  │
    │     "날씨를 확인해 볼게요"  ──→ emit()  │  ← 사용자에게 실시간 전달!
    │                                      │
    │  2. 도구 호출 감지                     │
    │     weather_tool({"city":"Seoul"})    │
    │                                      │
    │  3. BeforeToolCallHook 실행           │
    │     (위험한 도구면 여기서 차단)          │
    │                                      │
    │  4. 도구 실행                          │
    │     → "맑음, 25도"                    │
    │                                      │
    │  5. AfterToolCallHook 실행            │
    │     (로그 기록, 감사 등)               │
    │                                      │
    │  6. 도구 결과를 대화에 추가             │
    │                                      │
    │  7. LLM 다시 스트리밍                  │
    │     "서울은 현재 맑고 25도입니다"       │
    │     ──→ emit()                       │  ← 사용자에게 실시간 전달!
    │                                      │
    │  8. 도구 호출 없음 → 루프 종료          │
    └──────────────────┬──────────────────┘
                       │
                       ▼
    ┌─────────────────────────────────────┐
    │  대화 히스토리 저장                    │
    │  AfterAgentComplete Hook 실행        │
    │  Metrics 기록                        │
    └─────────────────────────────────────┘
```

---

## 사용자 입장에서의 예시

### 예시 1: 웹 채팅 (SSE)

```kotlin
@GetMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
suspend fun chatStream(@RequestParam message: String): Flow<String> {
    return agentExecutor.executeStream(
        AgentCommand(
            systemPrompt = "당신은 날씨 비서입니다.",
            userPrompt = message,
            userId = "user-123",
            metadata = mapOf("sessionId" to "session-abc")
        )
    )
}
```

**사용자가 보는 화면:**
```
[실시간 타이핑 중...] "날씨를 확인해 볼게요"
[잠깐 멈춤 - 도구 실행 중]
[실시간 타이핑 중...] "서울은 현재 맑고 25도입니다. 외출하기 좋은 날씨네요!"
```

### 예시 2: Slack 봇

Slack은 메시지를 업데이트할 수 있어서 스트리밍과 잘 맞음:

```kotlin
val chunks = StringBuilder()
agentExecutor.executeStream(command).collect { chunk ->
    chunks.append(chunk)
    slackClient.updateMessage(channelId, messageTs, chunks.toString())
}
```

**Slack에서 보이는 모습:**
```
봇: 날씨를 확인해 볼게요  ← 점점 타이핑되는 것처럼 보임
봇: 날씨를 확인해 볼게요 서울은 현재 맑고 25도입니다.  ← 도구 실행 후 이어서 타이핑
```

### 예시 3: 도구 여러 개 사용

```
사용자: "서울 날씨랑 지금 시간 알려줘"

[스트리밍] "두 가지를 확인해 볼게요."
[도구 실행: weather("Seoul") → "맑음 25도"]
[도구 실행: time("KST") → "오후 3시"]
[스트리밍] "서울은 현재 맑고 25도이며, 한국 시간으로 오후 3시입니다."
```

---

## execute()와 executeStream() 비교

| 항목 | execute() | executeStream() |
|------|-----------|-----------------|
| 반환 타입 | `AgentResult` | `Flow<String>` |
| 응답 방식 | 전체 완료 후 한번에 | 실시간 청크 단위 |
| ReAct 루프 | O | O |
| Guard 체크 | O | O |
| Before/After Hook | O | O |
| Tool Hook | O | O |
| maxToolCalls | O | O |
| Semaphore | O | O |
| Timeout | O | O |
| 대화 히스토리 저장 | O | O |
| Metrics 기록 | O | O |
| MDC 로깅 | O | O |
| 토큰 사용량 추적 | O | X (스트리밍 특성상) |

---

## 기술적 구현 포인트

### 1. `stream().chatResponse()` 사용

```kotlin
// 기존: 텍스트만 받음
requestSpec.stream().content()       // Flux<String>

// 변경: 구조화된 ChatResponse 받음
requestSpec.stream().chatResponse()  // Flux<ChatResponse>
```

`ChatResponse`에는 텍스트뿐 아니라 도구 호출 정보도 포함됨.
Spring AI 1.1.2에서 `hasToolCalls()` 메서드로 감지 가능.

### 2. internalToolExecutionEnabled = false

```kotlin
ToolCallingChatOptions.builder()
    .internalToolExecutionEnabled(false)  // Spring AI 자동 실행 끔
    .build()
```

Spring AI가 자체적으로 도구를 실행하는 것을 끄고, 우리가 직접 루프를 관리함.
이렇게 해야 Hook을 호출할 수 있고, maxToolCalls도 적용할 수 있음.

### 3. 실시간 텍스트 + 도구 감지 동시 처리

```kotlin
flux.asFlow().collect { chunk ->
    // 텍스트가 있으면 즉시 사용자에게 전달
    val text = chunk.result?.output?.text
    if (!text.isNullOrEmpty()) {
        emit(text)  // ← 실시간!
    }

    // 도구 호출 정보가 있으면 저장 (보통 마지막 청크에 있음)
    val toolCalls = chunk.result?.output?.toolCalls
    if (!toolCalls.isNullOrEmpty()) {
        pendingToolCalls = toolCalls
    }
}
```

### 4. Semaphore + Timeout

```kotlin
concurrencySemaphore.withPermit {      // 동시 요청 수 제한
    withTimeout(requestTimeoutMs) {     // 전체 시간 제한
        // ... 스트리밍 ReAct 루프
    }
}
```

`execute()`와 동일한 동시성 제어가 스트리밍에도 적용됨.

---

## 테스트 커버리지

총 **18개 TDD 테스트** 추가 (기존 9개 업데이트):

| 카테고리 | 테스트 | 설명 |
|----------|--------|------|
| 도구 호출 | 5개 | 감지, 실행, 다중 도구, 모드별 동작 |
| Hook | 3개 | BeforeTool, AfterTool, 거부 처리 |
| maxToolCalls | 1개 | 제한 초과 시 중단 |
| 타임아웃 | 1개 | 시간 초과 에러 처리 |
| AfterComplete | 4개 | Hook, Metrics, 도구 목록 포함 |
| 메모리 | 2개 | 히스토리 저장 (텍스트, 도구 사용 후) |
| 에러 처리 | 2개 | 도구 실패, 미등록 도구 |

---

## 파일 변경 내역

| 파일 | 변경 내용 |
|------|----------|
| `SpringAiAgentExecutor.kt` | `executeStream()` 전체 재작성 (ReAct 루프) |
| `StreamingTest.kt` | `content()` → `chatResponse()` 목 변경 |
| `StreamingReActTest.kt` | 신규 - 18개 TDD 테스트 |
