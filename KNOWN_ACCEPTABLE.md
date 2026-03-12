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
| `MetricRingBuffer.kt:35` (torn write) | P2 | 의도적 lock-free 설계. null guard로 crash 방지, 메트릭 손실 허용 | 2026-03-13 |
| `GoogleCalendarTool.kt:61` (transport per-call) | P2 | NetHttpTransport는 JDK HttpURLConnection 기반, 리소스 없음 | 2026-03-13 |
| `DefaultCircuitBreaker.kt:54` (HALF_OPEN counter) | P2 | atomic ops로 실제 누수 없음. 최악 시 trial call 1회 추가 | 2026-03-13 |
| `InMemoryMemoryStore.kt:288` (sessionOwners) | P2 | 파일 경로 불일치 (scanner hallucination) | 2026-03-13 |
| `ToolCallOrchestrator.kt:143` (.forEach) | P4 | body가 non-suspend (add+merge만), 안전. 스타일 이슈 수준 | 2026-03-13 |
| `MemoryStore` default addMessage userId drop | P3 | 하위호환 설계 의도. InMemory/JDBC 모두 4-arg override 구현 | 2026-03-13 |
| `MemoryStore` default listSessionsByUserId | P3 | 하위호환 설계 의도. InMemory/JDBC 모두 userId 필터링 override 구현 | 2026-03-13 |
| `SessionController.isSessionOwner` null owner | P3 | 하위호환 설계 의도. owner 미추적 세션 접근 허용은 의도적 | 2026-03-13 |
| `ConversationManager.kt:259` catch (non-suspend) | P4 | non-suspend fun, CancellationException 도달 불가 | 2026-03-13 |
| `ToolCallOrchestrator.kt:215,420` double findToolAdapter | P4 | 도구 목록 최대 20개, linear scan 2회 비용 미미 | 2026-03-13 |
| `ToolCallOrchestrator.kt:308` verifiedSources O(n²) | P4 | 실 사용 시 sources 수 극소 (~1-3 per tool), 이론적 | 2026-03-13 |
| `ToolCallOrchestrator.kt:496` multiple filterIsInstance | P4 | 캐시됨, key 생성 비용 미미 | 2026-03-13 |
| `ManualReActLoopExecutor.kt:95` buildMap per LLM call | P4 | ReAct 루프당 1회, 최대 10회. 미미 | 2026-03-13 |
| `DefaultGuardStages.kt:57` cache key string concat | P4 | 단일 문자열 연결, hot path 아님 | 2026-03-13 |
| `RuleBasedClassificationStage.kt:27` text.lowercase() | P4 | 요청당 1회, 10K 상한. 패턴 (?i) 전환 시 breaking change | 2026-03-13 |
| `UnicodeNormalizationStage.kt:37` IntStream lambda | P4 | 미미한 allocation | 2026-03-13 |
| `JdbcMemoryStore.kt:72` listSessions N+1 | P4 | 관리 엔드포인트, hot-path 아님 | 2026-03-13 |
| `JdbcMemoryStore.kt:139` N individual DELETEs | P4 | cleanup 경로, hot-path 아님 | 2026-03-13 |
| `JdbcMemoryStore.kt:176` COUNT per insert | P4 | eviction 체크, 단일 쿼리 | 2026-03-13 |
| `SemanticToolSelector.kt` repeated lowercase | P4 | 요청당 1회, 도구 수 소규모 | 2026-03-13 |
| JDBC stores (JdbcMcpSecurityPolicyStore 등) test gap | P4 | 통합 테스트 레벨, DB 설정 필요. @Tag("integration") 대상 | 2026-03-13 |
| `UserMemoryManager.getContextPrompt()` test gap | P4 | 포맷팅 로직, 스토어 테스트에서 간접 커버 | 2026-03-13 |
| `RagIngestionDocumentSupport.kt` test gap | P4 | 단순 변환 로직 | 2026-03-13 |
| `McpAdminHmacSupport.kt` test gap | P3 | HMAC 서명 로직 미테스트. 향후 추가 필요 | 2026-03-13 |
| `TenantSpanProcessor.kt` test gap | P4 | OTel 컨텍스트 테스트 복잡도 높음 | 2026-03-13 |
| `AuthController.kt` 401 empty body | P4 | 인증 엔드포인트 관행. 클라이언트는 status code 확인 | 2026-03-13 |
| `McpSwaggerCatalogController.kt:91` @ApiResponses 누락 | P4 | 단일 어노테이션 누락, 기능 영향 없음 | 2026-03-13 |
| `PlatformAdminController.kt:472` empty ok body | P4 | action 엔드포인트 설계 선택 | 2026-03-13 |
| `SlackApiClient.kt:785` companion CachedThreadPool | P4 | daemon 스레드, JVM 종료 시 자동 정리 | 2026-03-13 |
| `McpMetricReporter.kt:61` executor 미정리 | P4 | 사용자 관리 클래스, auto-configure 아님 | 2026-03-13 |
| `McpAdminWebClientFactory` default param | P4 | Spring 컨텍스트에서 정상 주입. 테스트 전용 위험 | 2026-03-13 |
| `SchedulerController` teamsWebhookUrl SSRF | P4 | Admin 전용 API + TeamsWebhookClient SSRF 보호 존재 | 2026-03-13 |
| `FeedbackController.kt:53` input size | P4 | Spring WebFlux max-in-memory-size (256KB) 기본 제한 존재 | 2026-03-13 |
| `FeedbackController.kt:148` IDOR | P4 | 설계 의도 (docstring "any user"), UUID 추측 불가 | 2026-03-13 |
| `WorkerAgentTool.kt:88` error message | P4 | Supervisor LLM이 worker 실패 사유를 알아야 라우팅 결정 가능. 설계 의도 | 2026-03-13 |
| `SchedulerController` prompt size | P4 | Admin 전용 API, 관리자 책임 | 2026-03-13 |
| `ChatController` userId fallback | P4 | JwtAuthWebFilter 정상 동작 시 도달 불가. 공개 경로에서만 활성화 | 2026-03-13 |
| `EvaluationPipelineFactory.create()` concurrent reset | P2 | 싱글톤 LlmJudgeEvaluator 토큰 카운터 설계. reset은 순차 실행 개선. 동시 실행 시 budget 초과 가능하나 데이터 손실 없음 | 2026-03-13 |
