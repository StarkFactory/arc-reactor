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
| `ToolOutputSanitizer` error output wrapping | P3 | 에러 메시지도 일관되게 sanitize — 에러 경로 skip 시 injection 벡터 발생. 안전한 설계 선택 | 2026-03-13 |
| `SupervisorOrchestrator` recursion depth | P2 | agentFactory는 사용자 설정. maxToolCalls+timeout으로 각 레벨 제한. 의도적 재귀만 가능 | 2026-03-13 |
| `ConversationManager.saveMessages` non-atomic | P2 | non-suspend private fun, 동일 세션 동시 요청은 클라이언트 책임. 스토어 per-op 락 존재 | 2026-03-13 |
| `DocumentController.searchDocuments` no isAdmin | P3 | 설계 의도 — 읽기 전용 API, 쓰기만 admin 필요. 테스트명 "should allow search without admin check" 확인 | 2026-03-13 |
| `ToolObservabilityAspect.kt:110` Regex per call | P4 | 에러 경로에서만 실행 (normalizeExceptionCode). hot-path 아님 | 2026-03-13 |
| `ToolExecutionPolicyEngine.kt:43` test gap | P4 | work_action_items_to_jira dryRun 브랜치 미테스트. 정책 로직 단순 | 2026-03-13 |
| `InjectionPatterns.kt`+`PiiPatterns.kt` test gap | P4 | 상위 Guard/Sanitizer 테스트에서 간접 커버. 패턴 자체 단위 테스트 없음 | 2026-03-13 |
| `McpPreflightController`+`McpAccessPolicyController` factory default | P2 | Spring 컨텍스트에서 Bean 주입 정상 작동. default는 비-Spring 컨텍스트 폴백 | 2026-03-13 |
| `SystemPromptBuilder.kt` 44x prompt.lowercase() | P4 | 요청당 1회, 프롬프트 길이 ~10K. lowercase 비용 미미 | 2026-03-13 |
| `CacheKeyBuilder.kt:58` MessageDigest.getInstance per call | P4 | JCA provider 캐시 존재. hex 인코딩도 32 바이트 수준 | 2026-03-13 |
| `WorkspaceMutationIntentDetector.kt` 14 regexes | P4 | 이미 companion object에 컴파일. 요청당 1회, 14회 match 비용 미미 | 2026-03-13 |
| `StructuredOutputValidator.kt:38` new Yaml() per call | P4 | YAML 포맷 활성 시만. 드물게 사용 | 2026-03-13 |
| `RuleBasedIntentClassifier.kt:72,83` lowercase in loop | P4 | intent 수 소규모, 요청당 1회 분류 | 2026-03-13 |
| `AdminAuthorizationSupport.kt:45` MessageDigest.getInstance | P4 | 관리자 경로만, hot-path 아님 | 2026-03-13 |
| `SimpleReranker.kt:120` repeated lowercase+split in MMR | P4 | document 수 소규모 (topK 기본 5), 영향 미미 | 2026-03-13 |
| `ManualReActLoopExecutor.kt:131` AtomicInteger per iteration | P4 | 최대 10 할당, 미미 | 2026-03-13 |
| `ApiVersionContractWebFilter.kt:44` joinToString per request | P4 | 단일 문자열 연결, 미미한 할당 | 2026-03-13 |
| `SlackSignatureVerifier.kt:62` Mac.getInstance per request | P4 | Slack webhook 전용 경로, hex 인코딩 32 바이트 | 2026-03-13 |
| `McpAdminHmacSupport.kt:64` hex + MessageDigest per request | P4 | MCP admin proxy 전용, 저빈도 | 2026-03-13 |
| `MetricGuardAuditPublisher.kt:55` hex encoding per event | P4 | guard audit 경로, hex 32 바이트 | 2026-03-13 |
| `GoogleCredentialProvider.kt:28` disk read per tool call | P4 | Google tool 호출 시만 (저빈도), 스코프별 다름 | 2026-03-13 |
| `GoogleGmailTool.kt:76` N+1 messages.get | P4 | maxResults=10 제한, Gmail API 제약 | 2026-03-13 |
| `SlackApiClient.kt:756` Thread.sleep in retry | P4 | retry backoff 표준 패턴, max 2s × 3회 | 2026-03-13 |
| `TeamsWebhookClient.kt:42` .block() threading | P4 | scheduler 스레드에서 호출, reactive context 아님 | 2026-03-13 |
| `WriteOperationIdempotencyService.kt:105` MessageDigest per call | P4 | tool 호출당 1회, 미미 | 2026-03-13 |
| `SlackApiClient.kt:157,297` O(n) dedup | P4 | limit=50 상한, 실질 영향 없음 | 2026-03-13 |
| `SlackBotResponseTracker.kt:47` sort on overflow | P4 | maxEntries=50K 초과 시만, 드문 경로 | 2026-03-13 |
| `SlackReminderStore.kt:81` removeAt(0) on COWAL | P4 | maxPerUser=50, 소규모 | 2026-03-13 |
| `AlertScheduler.kt:57,59,65` 3x findActiveAlerts | P4 | 스케줄러 경로, 분 단위 실행 | 2026-03-13 |
| `PlatformAdminController.kt:279` tenantAnalytics N+1 | P4 | 관리자 대시보드, 저빈도 | 2026-03-13 |
| `TenantAdminController.kt:202` CSV buffered in memory | P4 | 관리자 export, 30일 제한 | 2026-03-13 |
| `McpServerController.kt:432` getStatus per server in list | P4 | MCP 서버 수 소규모 | 2026-03-13 |
| `McpSwaggerCatalogController.kt:369` JSON double-pass | P4 | proxy 응답 재직렬화, 저빈도 | 2026-03-13 |
| `MetricCollectionHook.kt:49` toString+toLongOrNull | P4 | agent 완료당 4회, 미미 | 2026-03-13 |
| `DashboardService.kt:89` in-memory sort | P4 | 관리자 대시보드, tool 수 소규모 | 2026-03-13 |
| `McpSecurityPolicyStore.kt:64` normalize() per check | P4 | 보안 정책 체크당 normalize, 등록/동기화 시만 | 2026-03-13 |
| `FeedbackAnalyzer.kt:66` full list for count | P4 | 스케줄러 경로, 저빈도 | 2026-03-13 |
| `McpManager.kt:344` keys.sorted() on snapshot | P4 | connect/disconnect 시만, 정상 상태에서 드묾 | 2026-03-13 |
| promptlab eval/analysis `jacksonObjectMapper()` instances | P4 | Spring 싱글톤 빈, 시작 시 1회 생성 | 2026-03-13 |
| `McpConnectionSupport.kt:184` per-tool lambda | P4 | connect 시만, tool 수 소규모 | 2026-03-13 |
| `ProactiveChannelController.kt` test gap | P4 | 표준 admin CRUD 패턴, 다른 controller 테스트에서 동일 패턴 검증 | 2026-03-13 |
| `McpSecurityPolicyProvider` test gap | P4 | normalize/cache 로직 단순, MCP 통합테스트에서 간접 커버 | 2026-03-13 |
| `SystemPromptBuilder.kt` looksLike* test gap | P4 | 프롬프트 분류 함수, 동작은 정상. 회귀 보호 수준 | 2026-03-13 |
| `ExecutionResultFinalizer.kt` suppression branches test gap | P4 | boolean 조건 단순, 기존 테스트에서 주요 경로 커버 | 2026-03-13 |
| `ToolCallOrchestrator.kt` accountId enrichment test gap | P4 | requesterEmail 경로 테스트됨, accountId는 동일 패턴 | 2026-03-13 |
| `SlackThreadTracker.kt` test gap | P4 | TTL/eviction 로직, handler 테스트에서 간접 커버 | 2026-03-13 |
| `QuotaEnforcerHook.kt` monthly reset test gap | P4 | AtomicLong CAS 패턴 단순, 주요 경로 테스트됨 | 2026-03-13 |
| `McpPreflightController.kt` missing-token 400 test gap | P4 | 방어 코드 존재, admin auth 테스트됨 | 2026-03-13 |
| `Bm25WarmUpRunner.kt` test gap | P4 | 시작 시 1회 실행, 예외 swallow은 의도적 | 2026-03-13 |
| `OutputGuardRuleEvaluator.kt` invalidPatterns cache test gap | P4 | 캐시 로직 단순, 주요 경로 테스트됨 | 2026-03-13 |
| `SemanticToolSelector.kt` cosineSimilarity edge test gap | P4 | 제로 벡터 → 0.0 반환 정상, 차원 불일치는 IllegalArgumentException | 2026-03-13 |
| `OutputGuardRuleAuditStore.kt` limit clamp test gap | P4 | coerceIn 단일 호출, 표준 패턴 | 2026-03-13 |
| `AdminErrorResponse` vs `ErrorResponse` 스키마 차이 | P4 | 모듈 경계 설계 선택, admin 모듈 독립 | 2026-03-13 |
| `SchedulerController.kt:146,161` triggerJob/dryRunJob 404 문서 불일치 | P4 | GlobalExceptionHandler가 500으로 처리, 기능 영향 없음 | 2026-03-13 |
| `PlatformAdminController.kt:239,258,437` empty 404 body | P4 | CLAUDE.md 규칙은 403 한정, 404 빈 body는 일반 관행 | 2026-03-13 |
| `SessionController.kt:89` empty 404 body | P4 | 상동 | 2026-03-13 |
| `IntentController.kt:69,114` empty 404 body | P4 | 상동 | 2026-03-13 |
| `PromptLabController.kt` multiple empty 404 | P4 | 상동 | 2026-03-13 |
| `StreamingCompletionFinalizer.kt:91` Modified 시 원본 저장 | P4 | 스트리밍 한계 — 이미 전송된 내용 변경 불가, 의도적 | 2026-03-13 |
| `AgentResult.content` nullable on success | P4 | factory method가 non-null 강제, 직접 구성은 내부 사용 | 2026-03-13 |
| `HookResult.Reject.reason` verbatim propagation | P4 | Hook은 개발자 작성 내부 컴포넌트, 사용자 입력 아님 | 2026-03-13 |
| `StreamingExecutionCoordinator.kt:146` INVALID_RESPONSE 코드 명칭 | P4 | 에러 코드 네이밍 개선 수준 | 2026-03-13 |
| `ArcReactorSchedulerConfiguration.kt:57` waitForTasks 미설정 | P4 | DynamicSchedulerService.destroy()가 futures 취소. 운영 개선 수준 | 2026-03-13 |
| `PlatformAdminController.kt` writeErrorsTotal 미노출 | P4 | 로그에서 확인 가능, 대시보드 기능 개선 수준 | 2026-03-13 |
| `AgentPolicyAndFeatureProperties.kt:497` inputMaxChars 5000 vs yml 10000 | P4 | 의도적 보수적 코드 기본값, yml이 운영 기본값 | 2026-03-13 |
| `PlatformAdminController.kt:238,266` catch without logging | P4 | debug 로그 추가 수준, 기능 영향 없음 | 2026-03-13 |
| `AlertScheduler.kt:74` failure 4-9 silent | P4 | 연속 실패 시 3+매10번째 로그, 운영 개선 수준 | 2026-03-13 |
| `ToolObservabilityAspect.kt:88` JSON parse silent catch | P4 | 메트릭 정확도 개선 수준, 기능 영향 없음 | 2026-03-13 |
| `DefaultMcpManager` AutoCloseable inference | P4 | Spring 6.x 자동 추론 정상 작동, 문서화 개선 수준 | 2026-03-13 |
| `AssistantMessage()` 생성자 테스트 코드 사용 | P4 | 프로덕션은 builder 사용. 테스트는 protected 접근 가능. Spring AI 변경 시 대응 | 2026-03-13 |
| `PersonaController.getPersona` no isAdmin | P3 | 설계 의도 — exchange 파라미터 미포함, 런타임 persona 조회용. list는 admin | 2026-03-13 |
| `PromptTemplateController` read endpoints no isAdmin | P3 | 설계 의도 — read 개방, write admin. DocumentController과 동일 패턴 | 2026-03-13 |
| `ErrorReportController.kt:130` apiKey blank bypass | P3 | 선택적 모듈 설계 의도 — @ConditionalOnProperty opt-in, 미설정 시 개방 | 2026-03-13 |
| MCP admin proxy SSRF (adminUrl) | P3 | admin 전용 — 관리자가 MCP 서버 등록 시 URL 결정. 신뢰 경계 내 | 2026-03-13 |
| MCP STDIO command injection | P3 | admin 전용 — 관리자가 MCP STDIO 명령어 등록. 신뢰 경계 내 | 2026-03-13 |
| `PlatformAdminController` privilege escalation | P3 | 설계 선택 — admin은 신뢰 역할, 단일 행위자 권한 부여 가능 | 2026-03-13 |
| `SpringAiVectorStoreRetriever.kt:80,84` log injection | P4 | Logback 프레임워크 보호, CRLF 실행 불가 | 2026-03-13 |
| `DefaultErrorReportHandler.kt:25` serviceName log injection | P4 | 상동 | 2026-03-13 |
| MCP proxy `resolveRequestId` header injection | P4 | admin 전용 + Spring WebClient 헤더 검증 | 2026-03-13 |
| `FeedbackController.kt:177` input value echo in 400 | P4 | API 응답 내 반사, XSS 불가 | 2026-03-13 |
| `ToolCallOrchestrator.kt:44` springToolCallbackCache unbounded | P4 | Spring 싱글톤 기반, 실제 무한 증가 이론적 | 2026-03-13 |
| `DynamicSchedulerService.kt:190` catch(Exception) non-suspend | P4 | non-suspend fun, runBlocking 내 CancellationException → FAILED 기록 적절 | 2026-03-13 |
| `DynamicSchedulerService.kt:218` runBlocking on scheduler thread | P4 | 설계 선택 — 스케줄러가 agent 실행. timeout으로 제한 | 2026-03-13 |
| `McpReconnectionCoordinator.kt:59` catch around delay | P4 | throwIfCancellation 정상 호출, 스타일 이슈 | 2026-03-13 |
| `SequentialOrchestrator.kt:59` no try-catch on execute | P4 | AgentExecutor 계약상 예외 미발생, ParallelOrchestrator와 비대칭이나 방어적 코딩 수준 | 2026-03-13 |
| `ToolCallOrchestrator.kt:215` hallucinated tool bypass maxToolCalls | P4 | 이론적 — 요청 타임아웃+컨텍스트 윈도우로 자연 제한, 실행 없음 | 2026-03-13 |
| `StreamingReActLoopExecutor.kt:143` toolStart/toolEnd 불일치 | P4 | executeInParallel 내부 catch로 예외 미발생, 이론적 | 2026-03-13 |
| `SystemPromptBuilder.kt` 44+ redundant `.lowercase()` | P4 | 요청당 1회, input 10K 상한. 44×10K=440K 할당이나 GC 부담 미미 | 2026-03-13 |
| `RuleBasedIntentClassifier.kt:72,83` keyword `.lowercase()` per call | P4 | 키워드 수 소규모, 분류당 1회. 미미 | 2026-03-13 |
| `OutputGuardRuleEvaluator.kt:59,74` `.toList()` defensive copies | P4 | 규칙 수 소규모 (<10), 미미 | 2026-03-13 |
| `SchedulerController.kt` non-suspend blocking in WebFlux | P4 | Admin 전용 엔드포인트, 저트래픽. 다른 admin 컨트롤러와 일관된 패턴 | 2026-03-13 |
| `SchedulerController.kt:146` trigger/dryRun 404 API doc mismatch | P4 | trigger()는 "Job not found" 문자열 반환 (예외 아님). 200으로 응답 | 2026-03-13 |
| `SchedulerController.kt` CRUD endpoint test gap | P4 | Auth 테스트 존재. CRUD 로직은 DynamicSchedulerService 테스트에서 커버 | 2026-03-13 |
| `TeamsWebhookClient.kt` SSRF integration test gap | P4 | 핵심 SSRF 로직은 SsrfProtectionTest에서 단위 테스트. 통합 경로만 미커버 | 2026-03-13 |
| `StructuredOutputValidator/Repairer` YAML test gap | FP | False positive — StructuredOutputTest+ValidatorTest에서 YAML 포맷 8개 테스트 | 2026-03-13 |
| `AuthController.kt:64` 403 uses AuthResponse not ErrorResponse | P4 | register()는 ResponseEntity<AuthResponse> 타입. 일관된 반환 타입 설계 | 2026-03-13 |
| `ArcReactorSchedulerConfiguration.kt:57` waitForTasksToComplete 미설정 | P4 | executeJob catch(Exception)이 InterruptedException 처리 | 2026-03-13 |
| `WebhookNotificationHook.kt:40` WebClient.create() 미관리 | P4 | Reactor Netty 글로벌 이벤트 루프 공유. 리소스 누수 아님 | 2026-03-13 |
| `ErrorReportController.kt:130` blank apiKey bypass | P4 | 설계 의도 — KDoc "Blank = no auth required". 선택적 모듈, 사용자 책임 | 2026-03-13 |
| `AgentProperties.kt:214` default canary seed | P4 | 오픈소스 프로젝트. 코드 코멘트로 변경 권고. 운영 가이드 수준 | 2026-03-13 |
| `StreamingReActLoopExecutor.kt:94` pendingToolCalls last-wins | P4 | Spring AI 스트리밍 표준 패턴 | 2026-03-13 |
| `SequentialOrchestrator.kt:75` null content → "" | P4 | 방어적 코딩. NPE 방지 | 2026-03-13 |
| `ConversationMessageTrimmer.kt:153` group size 1 for broken pair | P4 | 이미 깨진 쌍 정상 처리 | 2026-03-13 |
| `SlackSocketModeGateway.kt:54` double-start race | P4 | Spring DefaultLifecycleProcessor가 start() 동기 호출 | 2026-03-13 |
| `SlackApiClient.kt:631` Thread.sleep in executeWithRetry | P4 | non-suspend 함수, IO 디스패처 elastic pool | 2026-03-13 |
| `DefaultSlackCommandHandler.kt:101` mrkdwn injection (@channel/@here) | P3 | 인증된 Slack 사용자만 접근, 동일 채널 내 반복. 워크스페이스 멘션 제한 우회 가능하나 설계 의도 수준 | 2026-03-13 |
| `SlackMessagingService.kt:172` rate limiter TOCTOU | P4 | AtomicLong TOCTOU이나 Slack API 자체 rate limit이 최종 방어선 | 2026-03-13 |
| `ManualReActLoopExecutor.kt:110` null chatResponse → empty success | P4 | OutputBoundaryEnforcer가 OUTPUT_TOO_SHORT 감지. null ChatResponse 극히 드묾 | 2026-03-13 |
| `ParallelOrchestrator.kt:100` all-fail → null errorMessage | P4 | success=false 정상 설정. nodeResults에 개별 실패 상세 존재. 메시지 품질 이슈 | 2026-03-13 |
| `PromptLabController.kt:176,308` scope.launch/runningJobs race | P4 | Dispatchers.IO — launch returns Job before body executes. 이론적 race만 존재 | 2026-03-13 |
| `RagIngestionCaptureHook.kt:125` admin regex ReDoS | P4 | Admin 전용 blockedPatterns. OutputGuardRuleEvaluator와 동일 신뢰 경계. 자가 DoS 수준 | 2026-03-13 |
| `AgentErrorPolicy.kt:38` "tool" substring heuristic | P3 | 광범위한 매칭이나 실제 Spring AI 에러 메시지에서 오분류 가능성 극히 낮음. 리팩토링 대비 위험 높음 | 2026-03-13 |
| `MultipartChatController.kt:100` always HTTP 200 | P3 | ChatController와 다르게 에러도 200 반환. 멀티파트 엔드포인트 저사용, API 변경 시 하위호환 깨짐 | 2026-03-13 |
| `CaffeineResponseCache.kt` zero maxSize/ttl | P4 | Caffeine이 0 max → unbounded, 0 ttl → no-expiry 처리. 설정 오류이나 기능 정상. 문서화 수준 | 2026-03-13 |
| `TopicDriftDetectionStage.kt` windowSize=0 | P4 | 빈 windowed 리스트 → 드리프트 미감지, 기능 비활성화 효과. 검증보다 문서화 | 2026-03-13 |
| `SchedulerProperties` threadPoolSize/executionTimeoutMs 0/음수 | P4 | Spring ThreadPoolTaskScheduler 0→기본값, 음수→IAE. 설정 검증 수준 | 2026-03-13 |
| `AgentProperties` maxConversationTurns 0 | P4 | trimmer가 0 turns → 전부 제거, 정상 동작이나 의도 불분명. 설정 문서화 수준 | 2026-03-13 |
| Web controller 입력 검증 gap (size/range) | P4 | Guard 파이프라인 + Spring WebFlux 기본 제한이 최종 방어선. Jakarta validation은 코드 기본값 패턴 | 2026-03-13 |
| `ToolCallOrchestrator.kt:113,257` unprotected executeAfterToolCall | P3 | fail-close afterToolCall hook 예외 시 병렬 도구 결과 폐기. fail-close 계약상 정상 동작 | 2026-03-13 |
| `FeedbackResponse` sessionId/userId 미포함 | P3 | toResponse()+toExportItem() 일관적 누락. 프라이버시 설계 선택 | 2026-03-13 |
| `ChatResponse` durationMs/tokenUsage 미포함 | P3 | 공개 API 표면 설계 선택. 메트릭은 내부 관찰 경로로 수집 | 2026-03-13 |
| `LlmClassificationStage.kt:41` fail-open in fail-close pipeline | P3 | KDoc 명시: "Fail-open: LLM errors → Allowed. Defense-in-depth, not primary." 설계 선택 | 2026-03-14 |
| `ToolCallback.kt:124` SpringAiToolCallbackAdapter throws | P3 | ArcToolCallbackAdapter가 항상 래핑하여 catch+Error: 반환. 직접 사용 시에도 ToolCallOrchestrator catch 존재 | 2026-03-14 |
| JDBC stores @ConditionalOnMissingBean 미사용 | FP | @Primary 패턴 정상 — @ConditionalOnMissingBean 추가 시 InMemory가 먼저 등록되어 JDBC 비활성화됨 | 2026-03-14 |
| `IntentRegistry` InMemory default bean 미등록 | P3 | intent.enabled=true는 JDBC 필수 (persistence 없이 무의미). opt-in 기능, InMemoryIntentRegistry 클래스는 테스트용으로 존재 | 2026-03-14 |
| `McpSwaggerCatalogController.kt` 5 endpoints @ApiResponses 누락 | P4 | updateSource, syncSource, listRevisions, getDiff, publishRevision — Swagger 문서화 누락. 런타임 영향 없음 | 2026-03-14 |
| `DefaultRateLimitStage.kt:54-96` Caffeine 만료 시 다른 lock 객체 | P3 | TTL 만료 시점에만 발생 — 윈도우 전환 시 카운터 리셋은 의도된 동작. 최악 시 경계에서 1-2 추가 요청 | 2026-03-14 |
| `McpManager.kt:248` handleConnectionError mutex 미사용 | P3 | non-suspend 빠른 함수, clients.remove()?.let이 double-call 방어. reconnection은 지연 스케줄. 실제 race window 극소 | 2026-03-14 |
| `TenantService.create()` slug TOCTOU | P3 | InMemoryTenantStore 전용 — JdbcTenantStore는 DB UNIQUE 제약. Admin-only 저빈도 | 2026-03-14 |
| `TenantService.updatePlan/suspend/activate` read-modify-write | P3 | Admin-only 저빈도. JdbcTenantStore는 DB 레벨 처리. InMemory는 dev/test 전용 | 2026-03-14 |
| `InMemoryScheduledJobStore.kt:42` updateExecutionResult read-modify-write | P3 | dev/test 전용 스토어. JdbcScheduledJobStore는 DB 레벨 처리. Admin 경로 저빈도 | 2026-03-14 |
| `SemanticToolSelector.kt:597` clear+putAll 비원자적 | P3 | 빈 캐시 시 inline embedding fallback 동작 정상. 추가 API 호출 비용만 발생 | 2026-03-14 |
| `StageTimingSupport.kt:7` timing metadata read-modify-write | P4 | 단일 요청 내 순차 실행, 동시 접근 불가. 코스메틱 메타데이터 | 2026-03-14 |
| `InMemoryPersonaStore.kt:139` getDefault() 미동기화 | P3 | dev/test 전용. save()/update() 동시 실행 윈도우 극소. 최악 시 DEFAULT_SYSTEM_PROMPT 폴백 | 2026-03-14 |
| `QuotaEnforcerHook.kt:153` resetLocalCountersIfNewMonth 비원자적 clear | P3 | 월 1회 발생, fail-open 설계, DB 레이어가 권위적 답변 제공 | 2026-03-14 |
| `SlackReminderStore.collectDueReminders` filter-then-remove | P3 | COWAL remove() 반환값으로 중복 전송 방지. 정상 동작 | 2026-03-14 |
| `TenantResolver.currentTenantId()` ThreadLocal in WebFlux | P3 | 코드에 문서화됨 ("Unreliable in WebFlux"). OTel Context 이중 전파로 완화 | 2026-03-14 |
| `AuthConfiguration.authProperties` @ConditionalOnMissingBean 누락 | P3 | 복잡한 검증 로직 포함. override 시 직접 제외 필요. 컨벤션 위반이나 의도적 | 2026-03-14 |
| `AuthConfiguration.jwtSecretValidator` @ConditionalOnMissingBean 누락 | P3 | authProperties와 동일 패턴. auto-config exclude로 override | 2026-03-14 |
| `ArcReactorCoreBeansConfiguration.runtimePreflightMarker` @ConditionalOnMissingBean 누락 | P3 | 시작 검증용 마커 빈. override 불필요한 설계 | 2026-03-14 |
| `OtlpExporterConfiguration` beans @ConditionalOnMissingBean 누락 | P3 | tracing 모듈 (arc-admin). @ConditionalOnProperty 이중 게이트. 사용자 override 시 exclude | 2026-03-14 |
| `AdminJdbcConfiguration.metricWriter` @ConditionalOnMissingBean 누락 | P3 | JDBC admin 모듈 내부 빈. start() 호출 포함 — 중복 시 이중 writer. exclude로 해결 | 2026-03-14 |
| `AdminJdbcConfiguration.metricEventStore` @Primary/@ConditionalOnMissingBean 누락 | P3 | JDBC admin 모듈 내부 빈. JDBC 스토어지만 @Primary 패턴 미적용 | 2026-03-14 |
| `ErrorReportController` @ConditionalOnBean 누락 | P3 | error-report.enabled=true + LLM 미설정 시 시작 실패. property 조합 에지 케이스 | 2026-03-14 |
| `SlackEventController`+`SlackCommandController` @ConditionalOnBean 누락 | P3 | slack.enabled=true + LLM 미설정 시 시작 실패. 동일 에지 케이스 패턴 | 2026-03-14 |
| `FeedbackStore` InMemory default 미등록 | P3 | prompt-lab.enabled=true는 JDBC 필수. IntentRegistry와 동일 패턴 | 2026-03-14 |
| `RagIngestionCandidateStore` InMemory default 미등록 | P3 | rag.ingestion.enabled=true는 JDBC 필수. 동일 패턴 | 2026-03-14 |
| `SlackToolsAutoConfiguration.slackApiClient` nullable MeterRegistry | P4 | Kotlin nullable 정상 동작. ObjectProvider 미사용은 컨벤션 비일관 수준 | 2026-03-14 |
| `AdminAutoConfiguration.agentTracingHooks` bean 순서 | P4 | 외부 Tracer 제공 시 정상. arc-admin TracingAutoConfiguration만 사용 시 이론적 순서 문제 | 2026-03-14 |
| `AuthModels.kt:84` /api/auth/register missing from publicPaths | FP | ArcReactorAuthConfiguration:63-64에서 selfRegistrationEnabled=true 시 자동 추가. 테스트 검증됨 | 2026-03-14 |
| `JwtAuthWebFilter.kt:57` blank sub → "anonymous" | P4 | JWT 서명키 탈취 전제. 키 보유 시 어떤 userId도 위장 가능. 방어적 폴백 수준 | 2026-03-14 |
| `WebhookNotificationHook.kt:40` SSRF (webhookProperties.url) | P4 | arc.reactor.webhook.url 운영자 설정 속성. MCP admin proxy와 동일 신뢰 경계 | 2026-03-14 |
| `MediaAttachment.uri` 사설IP 미검증 | P4 | http/https+host 검증 존재(ChatController:378). URI는 LLM 프로바이더 API로 전달, 서버 직접 fetch 아님 | 2026-03-14 |
| `StreamingCompletionFinalizer.kt:48` emitBoundaryMarkers after guard rejection | P4 | 스트리밍 모드에서 content 이미 클라이언트에 전송됨. markers는 크기 메타데이터만 | 2026-03-14 |
| `InMemoryAlertRuleStore.kt:46` alert 무한 누적 | P4 | dev/test 전용 스토어. 프로덕션은 JdbcAlertRuleStore 사용. 다른 InMemory 스토어와 동일 패턴 | 2026-03-14 |
| `PromptLabController.kt:167,307` 비원자적 concurrent cap | P4 | 기존 KA (176,308 scope.launch/runningJobs race) 동일 이슈. Dispatchers.IO 이론적 race | 2026-03-14 |
| `GoogleSheetsTool/DriveTool/GmailTool` NetHttpTransport per call | P4 | GoogleCalendarTool:61 KA와 동일. JDK HttpURLConnection 글로벌 풀 관리, close()는 no-op | 2026-03-14 |
| `AgentRunContextManager.kt:48-51` close() MDC removal | P4 | MDCContext(map) 도입으로 coroutine snapshot 정확. close()는 best-effort thread cleanup | 2026-03-14 |
| `ToolPreparationPlanner.kt:57-60` WeakHashMap non-atomic cache | P4 | 동일 ToolCallback에 중복 ArcToolCallbackAdapter 생성 가능하나 기능 동일. 추가 할당 1회 | 2026-03-14 |
| `AgentRunContextManager.kt` MDCContext scope 제한 | P4 | open()에서 MDCContext(map)은 snapshot 정확성 보장. 코루틴 전체 전파는 별도 개선. MDC.put best-effort | 2026-03-14 |
| `TokenEstimator.kt:44` codePoints().forEach int boxing | P4 | 미캐시 메시지만. WeakHashMap 캐시 존재. micro-optimization 수준 | 2026-03-14 |
| `AgentExecutionCoordinator.kt:160` mcpToolCallbacks() 이중 호출 | P4 | 캐시 키 생성 + ToolPreparationPlanner에서 재호출. MCP 도구 수 소규모, map 순회 비용 미미 | 2026-03-14 |
| `StreamingExecutionCoordinator.kt:55` error dispatch 미테스트 | P4 | BlockedIntent/Timeout/Unexpected 3경로. coordinator 단위 테스트, 핵심 로직은 하위 모듈에서 커버 | 2026-03-14 |
| `ToolPreparationPlanner.kt:27` LocalToolFilter exception fallback 미테스트 | P4 | 방어적 fallback (warn+이전 목록 유지). fail-open 설계, 필터 자체 테스트에서 간접 커버 | 2026-03-14 |
| `GoogleCredentialProvider.kt:28` path traversal | P4 | config property (arc.reactor.google.service-account-key-path). 운영자 설정, admin 신뢰 경계 | 2026-03-14 |
| `ExecutionResultFinalizerTest:444` re-guard hook 검증 누락 | P4 | hookExecutor relaxed mock 사용. 첫 guard rejection 테스트(line 136)에서 hook 호출 검증됨. 동일 코드 경로 | 2026-03-14 |
