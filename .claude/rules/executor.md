---
paths:
  - "arc-core/src/main/kotlin/com/arc/reactor/agent/impl/**"
---

# Agent Executor Rules

이 디렉토리는 프로젝트에서 가장 복잡한 파일들이 모여 있다. 수정 시 아래 규칙을 반드시 따를 것.

## CancellationException

`suspend fun` 내 모든 `catch (e: Exception)` 블록에서 첫 줄에 `e.throwIfCancellation()` 호출:

```kotlin
try {
    // ...
} catch (e: Exception) {
    e.throwIfCancellation()  // 반드시 첫 줄
    // 에러 처리
}
```

## ReAct Loop

- `maxToolCalls` 도달 → `activeTools = emptyList()` 필수 (로깅만 하면 무한 루프)
- `toolsUsed`에 도구명 추가 전 어댑터 존재 확인 (LLM 환각 도구명 방지)
- Tool 병렬 실행: `coroutineScope { map { async { } }.awaitAll() }` 패턴

## Context Window

- 트리밍 시 마지막 UserMessage(현재 프롬프트) 절대 제거 금지
- `AssistantMessage(toolCalls)` + `ToolResponseMessage`는 쌍으로 제거
- Phase 2 가드: `>` 사용 (not `>=`)

## Error Handling

- `AgentResult.failure()` 호출 시 반드시 `errorCode` 포함
- Hook/Memory 호출은 catch/finally 블록으로 감싸서 원본 에러 마스킹 방지
- 스트리밍 메모리 저장은 `withTimeout` 밖 `finally` 블록에서 실행
- 실패 시 (`!result.success`) `saveConversationHistory` 건너뛰기
