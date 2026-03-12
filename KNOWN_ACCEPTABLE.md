# Known Acceptable — 기각된 탐색 결과

| 파일:라인 | 후보 분류 | 기각 사유 | 날짜 |
|-----------|----------|-----------|------|
| `CreateScheduledJobTool.kt` | P3 | ToolCallback 규약: 에러를 문자열로 LLM에 반환. HTTP 클라이언트 노출 아님 | 2026-03-12 |
| `UpdateScheduledJobTool.kt` | P3 | 상동 | 2026-03-12 |
| `DeleteScheduledJobTool.kt` | P3 | 상동 | 2026-03-12 |
| `TriggerScheduledJobTool.kt` | P3 | 상동 | 2026-03-12 |
| `SlackMessagingService.kt` | P3 | 내부 결과 객체, HTTP 응답에 노출되지 않음 | 2026-03-12 |
| `InMemoryTokenRevocationStore.kt` | P4 | maxEntries=10000 하드코딩 — dev 전용 스토어, lazy cleanup 존재 | 2026-03-12 |
| `ChatController.kt` (@Size) | P4 | Jakarta 컴파일타임 상수 필수. Guard 파이프라인이 설정 가능 제한 담당 | 2026-03-12 |
| `ManualReActLoopExecutor.kt:102` | P2 | TokenUsage Long→Int: Int.MAX=2.1B, 실제 최대 ~1M 토큰. 파괴적 API 변경 불필요 | 2026-03-12 |
| `StreamingReActLoopExecutor.kt:112` | P2 | 상동 | 2026-03-12 |
| `BlockingToolCallbackInvoker.kt` | P4 | nested runBlocking: 드물게 사용되는 fallback 경로 | 2026-03-12 |
| `JdbcUserMemoryStore.kt` (init) | P4 | 운영 노이즈 수준, 기능 영향 없음 | 2026-03-12 |
| `SemanticToolSelector.kt` (ALL fallback) | P4 | 설계 의도: graceful degradation | 2026-03-12 |
| `ErrorReportController.kt` (스택트레이스) | P4 | 제출자 책임, 수신자는 truncate만 | 2026-03-12 |
| `TenantResolver.kt` (크로스테넌트) | P4 | self-hosted 배포 설계 의도 | 2026-03-12 |
| 임베딩 캐시 fingerprint | P4 | 런타임에 도구 설명 변경 불가, 실제 영향 없음 | 2026-03-12 |
| `ErrorReportController.kt` (전역 rate limit) | P4 | 선택적 모듈, 설계 선택 | 2026-03-12 |
| `LlmProviderHealthIndicator.kt` (Gemini key) | P4 | gemini.api.key 바인딩 엣지 케이스, 실제 영향 극히 낮음 | 2026-03-12 |
| `ConversationMemory.kt:258` (getHistoryWithinTokenLimit) | P1 | False positive — Kotlin expression-body + lock.read 람다의 마지막 표현식이 리턴값. 정상 동작 | 2026-03-12 |
| `UserMemoryController.kt:118` (anonymous bypass) | P1 | False positive — JwtAuthWebFilter가 인증 없는 요청을 401로 차단. currentActor() "anonymous" 폴백은 도달 불가 | 2026-03-12 |
| `TenantResolver.kt:83` (manager tenant override) | P3 | Self-hosted 배포 설계 의도 (기존 항목과 동일) | 2026-03-12 |
| `ToolCallOrchestrator.kt:441,472` (error message) | P3 | 커밋 613d0a0에서 sanitize 적용 완료. ToolOutputSanitizer 경유 확인 | 2026-03-12 |
| `ToolCallOrchestrator.kt:217` (tool name logging) | P3 | 표준 로깅 관행. 실행은 차단됨. P5 수준 | 2026-03-12 |
| `SlackMessagingService.kt` (response URL SSRF) | P2 | Slack 서명 검증 필수 전제. 시크릿 탈취 없이 exploit 불가. P5 수준 | 2026-03-12 |
