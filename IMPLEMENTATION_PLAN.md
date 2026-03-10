# Implementation Plan
> 마지막 업데이트: 2026-03-10

## P0 — 즉시 수정 (컴파일 경고, 테스트 실패)

- 없음. 컴파일 경고 0개. 테스트 3,222개 전체 통과 (943 클래스). XML report 쓰기 오류는 `./gradlew clean`으로 해결되는 일시적 이슈.

## P1 — Critical Gotchas 위반

Production 코드: 없음. 전수 검사 결과 모든 패턴 정상.

테스트 코드 CancellationException 누락 (suspend context에서 `catch (e: Exception)` 사용, `throwIfCancellation()` 미호출):
- [x] `ConversationMemoryStressTest.kt` — 9개 catch 블록 전부 수정 완료 (9657db0)
- ~~`InMemoryFeedbackStoreTest.kt:276`~~ — 오탐: Java executor.submit 내부이므로 코루틴 아님

Production 코드 검사 상세:
  - **CancellationException**: 모든 `suspend fun` 내 `catch (e: Exception)` 앞에 `catch (e: CancellationException)` 또는 `e.throwIfCancellation()` 존재
  - **maxToolCalls + activeTools**: 정상 (ManualReActLoopExecutor.kt:165-167 확인)
  - **.forEach in suspend context**: 모든 `.forEach {}` 사용이 (1) non-suspend 함수이거나 (2) suspend 함수지만 lambda 내부에 suspend 호출 없음
  - **AssistantMessage 직접 생성**: main 코드에서 직접 생성자 호출 없음 (테스트 코드만 사용 — 허용됨)
  - **Guard userId null 체크**: 정상 (PreExecutionResolver.kt:37 확인)

## P2 — 테스트 누락

### arc-core (주요 클래스)

- [x] `SpringAiAgentExecutor` — 이미 SpringAiAgentExecutorTest.kt 존재 (오탐)
- [x] `ToolCallOrchestrator` — 이미 ToolCallOrchestratorTest.kt 존재 (오탐)
- [x] `ManualReActLoopExecutor` — 이미 ManualReActLoopExecutorTest.kt 존재 (오탐)
- [x] `BlockingToolCallbackInvoker` — runBlocking 경계 테스트 (3f904ea)
- [x] `ToolArgumentParser` — 이미 ToolArgumentParserFuzzTest.kt 존재 (오탐)
- [x] `ToolResponsePayloadNormalizer` — 페이로드 정규화 엣지 케이스 (d471298)
- [x] `DynamicSchedulerService` — 이미 5개 전용 테스트 파일 존재 (오탐)
- [x] `TokenEstimator` — 토큰 추정 정확도 검증 (72e4753)
- [x] `UserMemoryManager` — 이미 UserMemoryControllerTest.kt에서 간접 테스트 (오탐)
- [x] `CompositeIntentClassifier` — 이미 CompositeIntentClassifierTest.kt 존재 (오탐)
- [x] `DefaultCircuitBreaker` — 이미 DefaultCircuitBreakerTest.kt 존재 (오탐)
- [x] `ModelFallbackStrategy` — 이미 FallbackStrategyTest.kt에 포함 (233줄, 오탐)
- [x] `RedisSemanticResponseCache` — 이미 RedisSemanticResponseCacheTest.kt 존재 (오탐)
- [x] `CaffeineResponseCache` — 이미 CaffeineResponseCacheTest.kt 존재 (오탐)
- [x] `DefaultRagPipeline` — 이미 RagPipelineMaxTokensTest.kt에서 테스트됨 (오탐)
- [x] `HybridRagPipeline` — 이미 HybridRagPipelineTest.kt 존재 (오탐)
- [x] `Bm25Scorer` — 이미 Bm25ScorerTest.kt 존재 (175줄, 오탐)
- [x] `RrfFusion` — 이미 RrfFusionTest.kt 존재 (오탐)
- [x] `ToolExecutionPolicyEngine` — 이미 WriteToolBlockHookTest.kt에서 간접 테스트 (오탐)
- [x] `MultiAgentOrchestrator` — 인터페이스만. 구현체 3개 모두 테스트 존재 (오탐)
- [x] `WorkerAgentTool` — 이미 SupervisorOrchestratorTest.kt에서 테스트됨 (오탐)

### arc-web (컨트롤러)

- [x] `ApprovalController` — 이미 ApprovalControllerAuthTest.kt 존재 (오탐)
- [x] `IntentController` — 이미 IntentControllerJdbcIntegrationTest.kt 존재 (오탐)
- [x] `SchedulerController` — 이미 SchedulerControllerAuthTest.kt 존재 (오탐)
- [x] `ToolPolicyController` — 이미 ToolPolicyControllerAuthTest.kt 존재 (오탐)
- [x] `RagIngestionPolicyController` — 이미 RagIngestionPolicyControllerAuthTest.kt 존재 (오탐)
- [x] `ProactiveChannelController` — arc-slack compileOnly 의존성으로 arc-web 테스트 불가. 제외.

### arc-slack

- [x] `SlackCommandController` — 이미 SlackUserJourneyScenarioTest.kt에서 테스트됨 (오탐)
- [x] `SlackEventController` — 이미 SlackUserJourneyScenarioTest.kt에서 테스트됨 (오탐)
- [x] `SlackEventDeduplicator` — 이미 SlackEventDeduplicatorTest.kt 존재 (오탐)
- [x] `DefaultSlackCommandHandler` — 이미 SlackUserJourneyScenarioTest.kt에서 테스트됨 (오탐)
- [x] `DefaultSlackEventHandler` — 이미 SlackUserJourneyScenarioTest.kt에서 테스트됨 (오탐)
- [x] `SlackSignatureWebFilter` — 이미 SlackSignatureWebFilterTest.kt 존재 (오탐)

### arc-error-report

- [x] `ErrorReportController` — 이미 ErrorReportControllerTest.kt 존재 (오탐)
- [x] `DefaultErrorReportHandler` — 이미 DefaultErrorReportHandlerTest.kt 존재 (오탐)

## P3 — 코드 품질

### TODO 미해결
- [ ] `DocumentController.kt:173` — TODO: "Store chunk_total in parent metadata at write time to avoid O(maxNumChunks)"

### 메서드 20줄 초과
- [ ] `SpringAiAgentExecutor.kt:255-303` — `execute()` 49줄
- [ ] `SpringAiAgentExecutor.kt:364-389` — `executeStream()` 26줄
- [ ] `SpringAiAgentExecutor.kt:439-498` — `executeWithTools()` 60줄
- [ ] `SupervisorOrchestrator.kt:45-94` — `execute()` 50줄

### 줄 120자 초과 (main 코드, 31개)
주요 위반 파일:
- [ ] `McpSwaggerCatalogController.kt` — 4곳 (에러 메시지, 로깅)
- [ ] `OutputGuardRuleController.kt` — 2곳 (에러 응답 메시지)
- [ ] `SlackSystemPromptFactory.kt` — 4곳 (시스템 프롬프트 텍스트, 최대 230자)
- [ ] `SlackApiClient.kt` — 4곳 (메서드 시그니처, 에러 로깅)
- [ ] `SlackToolsAutoConfiguration.kt` — 4곳 (Bean 팩토리 시그니처)
- [ ] `SlackResponseTextFormatter.kt` — 2곳 (한국어 시스템 프롬프트)
- [ ] 기타 11곳 (에러 메시지, 어노테이션 설명 등)

## P4 — 문서화

- 없음. 모든 컨트롤러 엔드포인트에 `@Tag` + `@Operation(summary)` 확인됨.

## 완료
- [x] P1: `ConversationMemoryStressTest.kt` — 9개 catch 블록에 throwIfCancellation 추가 (9657db0)
- [x] P2: `TokenEstimator` — DefaultTokenEstimator 단위 테스트 추가 (72e4753)
- [x] P2: `ToolResponsePayloadNormalizer` — 단위 테스트 추가 (d471298)
- [x] P2: `BlockingToolCallbackInvoker` — 단위 테스트 추가 (3f904ea)
- [x] P2: 나머지 30개 항목 — 기존 테스트 존재 확인 (오탐 정리)
