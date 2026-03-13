# Refactoring Plan

## Iteration 2 — Lens 2: 에러 전파 경로

- [x] [HIGH] P1: `StreamingCompletionFinalizer.kt:116` — output guard 예외 시 fail-open → fail-close. 커밋 d4b2515

## Iteration 3 — Lens 3: 리소스 수명

- [x] [HIGH] P1: `McpManager.kt:251` — handleConnectionError에서 MCP 클라이언트 close 누락 (SSE/STDIO 리소스 누수). 커밋 e01d0b0

## Iteration 4 — Lens 4: 데이터 흐름 무결성

- [x] [MED] P2: `PreExecutionResolver.kt:39-44` — GuardCommand.channel 미전달, guard audit 이벤트 channel 항상 null. 커밋 5186cdf

## Iteration 5 — Lens 5: 경계 조건

- [x] [MED] P2: `DynamicSchedulerService.kt:231` — maxRetryCount=0 + retryOnFailure=true 시 job 미실행 (1..0 빈 범위). coerceAtLeast(1) 적용
