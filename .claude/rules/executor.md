---
paths:
  - "src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt"
---

# SpringAiAgentExecutor Rules

이 파일은 프로젝트에서 가장 복잡한 파일 (~1,060줄). 수정 시 아래 규칙을 반드시 준수.

## CancellationException 처리

모든 suspend fun에서 generic Exception catch 전에 CancellationException을 catch & rethrow:

```kotlin
try {
    // ...
} catch (e: CancellationException) {
    throw e  // 구조적 동시성 보장
} catch (e: Exception) {
    // 에러 처리
}
```

`java.util.concurrent.CancellationException`은 `kotlin.coroutines.cancellation.CancellationException`의 typealias임.

## ReAct 루프

- maxToolCalls 도달 시 반드시 `activeTools = emptyList()` — 도구를 제거해야 LLM이 최종 답변 생성
- 도구 실행은 `coroutineScope { map { async { } }.awaitAll() }` 패턴 (병렬)
- `toolsUsed`에 도구명 추가는 adapter 존재 확인 후에만 (LLM hallucination 방지)

## 컨텍스트 윈도우 관리

- 트리밍 시 마지막 UserMessage(현재 프롬프트) 절대 제거 금지
- AssistantMessage(toolCalls) + ToolResponseMessage는 쌍으로 제거
- Phase 2 가드: `>` 사용 (`>=` 아님)

## 에러 처리

- 모든 `AgentResult.failure()` 호출에 적절한 `errorCode` 포함
- `classifyError(e)` → `AgentErrorCode` 매핑 사용
- catch/finally 블록 내 hook/memory 호출은 try-catch 감싸기 (원래 에러 마스킹 방지)
- streaming memory save는 `withTimeout` 밖 `finally` 블록에서 실행
- 실패 시 `saveConversationHistory` 건너뛰기 (`!result.success`)

## 스트리밍

- `lastIterationContent`만 저장 (전체 누적 content 아님)
- callWithRetry는 Flux 생성만 감싸고 소비는 감싸지 않음 (known limitation)
