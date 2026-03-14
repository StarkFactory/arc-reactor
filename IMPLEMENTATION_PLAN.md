# Implementation Plan
> 마지막 업데이트: 2026-03-14 | Ralph 반복 18 — 렌즈 4 (성능) P0-P3 0건

## P0 — 즉시 수정 (런타임 크래시, 데이터 손실)

(없음)

## P1 — Agent 동작 결함 (잘못된 응답, 무한 루프, 도구 실패 미처리)

(없음 — 전부 완료)

## P2 — 견고성 (에러 복구 실패, 리소스 누수, 동시성 문제)

(없음 — 전부 완료)

## P3 — 보안 (권한 우회, 인젝션, 정보 노출)

(없음 — 전부 완료)

## P4 — 성능 / 코드 품질 / 문서화

### 핫패스 Regex 컴파일
(없음 — 전부 완료)

### 중복 로직
(없음 — 전부 완료)

### 기타 코드 품질
(없음 — 전부 완료)

## 완료
- [x] P2: `Bm25Scorer.kt` — @Synchronized 추가로 동시성 문제 해결 (f270cf8)
- [x] P2: `PlatformAdminController.kt` — 이미 logger.warn(e) 로깅 존재, 확인 완료 (f270cf8)
- [x] P3: `McpAccessPolicyController.kt` — 에러 메시지 제네릭화 이미 적용 (f270cf8)
- [x] P3: `ErrorReportController.kt` — MessageDigest.isEqual() 상수시간 비교 적용 (f270cf8)
- [x] P3: `McpConnectionSupport.kt` — SSRF 차단 (사설IP+멀티캐스트+클라우드메타데이터) (312c56e)
- [x] P4: `ResponseValueInsights.kt` — Regex/SHA256 top-level val+ThreadLocal 추출 완료 (f270cf8)
- [x] P4: `WorkContextForcedToolPlanner.kt` — Regex companion object 추출 (54bc7f1)
- [x] P4: `OutputGuardRuleEvaluator.kt` — 패턴 캐시 적용 (3aeeb78)
- [x] P4: `RagIngestionCaptureHook.kt` — Regex 캐시 적용 (509c229)
- [x] P4: ISSUE_KEY_REGEX/OPENAPI_URL_REGEX → WorkContextPatterns 공통 추출 (533bca3)
- [x] P4: PII 패턴 → PiiPatterns 공통 추출 (06d5560)
- [x] P4: `SlackApiClient.kt` — Thread.sleep 수정 완료 (f270cf8)
- [x] P4: `ResponseValueInsights.kt` — MessageDigest ThreadLocal 캐시 적용 (f270cf8)
- [x] P2: `ToolCallOrchestrator.kt:102,240` — 실패한 도구의 에러 메시지 sanitize 적용 (613d0a0)
- [x] P2: `WorkerAgentTool.kt:58` — timeoutMs 미설정 → 15초 tool-call timeout으로 워커 강제 취소 — 수정 완료 (f452489)
- [x] P3: `SessionController.kt:135,152` — Content-Disposition header injection — 수정 완료 (6c31022)
- [x] P3: `AuthRateLimitFilter.kt:55` — X-Forwarded-For trust를 opt-in으로 변경 — 수정 완료 (6e8640f)
- [x] P5: `VerifiedSourcesResponseFilter` — casual/general 경계값 테스트 4개 추가 (83ade27, 3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:127-132` — looksLikeInformationRequest ?/패턴 과잉 차단 수정 (3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:162-164` — CASUAL_PROMPTS에 한국어 변형 추가 (83ade27)
- [x] P4: 인젝션 탐지 패턴 → InjectionPatterns 공통 추출 (d75d351)
- [x] P4: `DocumentController.kt` TODO → 설계 근거 코멘트로 교체 (b8b323a)
- [x] P2: `GoogleCredentialProvider.kt` — FileInputStream .use{} 리소스 누수 수정 (3809cf9)
- [x] P3: `SchedulerController.kt:78,115` — 예외 메시지 직접 노출 → 제네릭 메시지로 교체 (5d7dc6c)
- [x] P3: `PlatformAdminController.kt:210` — 예외 메시지 직접 노출 → 제네릭 메시지로 교체 (e4adf80)
- [x] P2: `McpAdminWebClientFactory.kt` — DisposableBean 구현 + Bean 등록으로 ConnectionProvider 정리 (a507155)
- [x] P3: `ToolCallOrchestrator.kt:388` — checkToolApproval 예외 시 raw e.message 제거 (1bd4abf)
- [x] P3: `TeamsWebhookClient.kt:27` — SSRF 보호 (isPrivateOrReservedAddress) 적용 (1bd4abf)
- [x] P2: `ErrorReportController.kt:50` — DisposableBean + scope.cancel() 리소스 정리
- [x] P2: `SlackReminderScheduler.kt:26` — DisposableBean + destroy() → shutdown() 리소스 정리
- [x] P2: `AgentTracingHooks.kt:43` — DisposableBean + 잔여 span 정리 (CancellationException 누수 방지)
- [x] P2: `SlackSocketModeGateway.kt:44` — startupJob @Volatile 추가
- [x] P2: `TeamsWebhookClient.kt` — SSRF 보호로 인한 테스트 행 수정 (ssrfProtectionEnabled 파라미터)
- [x] P3: `WorkerAgentTool.kt:62` — ToolCallback 규약 준수: try-catch + "Error: ..." 반환 (e3f8b82)
- [x] P4: `TopicDriftDetectionStage.kt:39` — 불필요 .lowercase() 제거 ((?i) 패턴) (7198214)
- [x] P4: `SchedulerExecutionViewSupport.kt:13` — Regex 추출 top-level (7198214)
- [x] P3: `ToolInputValidation.kt` — 21개 단위 테스트 추가 (d4d8cf9)
- [x] P2: `PromptLabController.kt:67` — DisposableBean + scope.cancel() (756bb3c)
- [x] P2: `PromptLabScheduler.kt:71` — throwIfCancellation() 추가 (9d430df)
- [x] P2: `EvaluationPipelineFactory.create()` — LlmJudge 토큰 사용량 리셋 (9d430df)
- [x] P1: `ExperimentOrchestrator.kt:75` — TimeoutCancellationException 별도 catch로 FAILED 상태 저장
- [x] P3: `BlockingToolCallbackInvoker.kt:29` — timeoutErrorMessage() 중복 "Error:" 프리픽스 제거
- [x] P4: `McpToolCallback.kt:42` — inputSchema get() → val 캐시로 매 접근 시 JSON 직렬화 제거 (b3d02b3)
- [x] P2: `SlackSocketModeGateway.kt:46-47` — slack/socketModeClient @Volatile 추가 (d6bede4)
- [x] P3: `SlackReminderScheduler.kt:64` — throwIfCancellation() 추가 (d6bede4)
- [x] P2: `StreamingReActLoopExecutor.kt:85` — chunk.result null-check 추가, usage-only 청크 NPE 방지 (da1d329)
- [x] P3: `SlackSignatureVerifier.kt:62` — blank signingSecret fail-close 추가 (2783a67)
- [x] P1: `ToolCallOrchestrator.kt:440,471` — e.message null → "Unknown error" 폴백 추가
- [x] P2: `PromptLabController.kt:302` — autoOptimize maxConcurrentExperiments 가드 추가
- [x] P3: `ExecutionResultFinalizer.kt:58-66` — attemptLongerResponse output guard 우회 수정: boundary retry 후 content 변경 시 output guard 재실행
- [x] P2: `AgentRunContextManager.kt:24-27` — MDC race: MDCContext(map) 생성자로 thread-local 경합 방지 (26e7213)
- [x] P3: `StreamingCompletionFinalizer.kt:46` — empty streaming content orphaned user turn 방지 (6ea86a8)

## 완료 (이전 사이클)
- [x] P1: `ConversationMemoryStressTest.kt` — 9개 catch 블록에 throwIfCancellation 추가 (9657db0)
- [x] P2: `TokenEstimator` — DefaultTokenEstimator 단위 테스트 추가 (72e4753)
- [x] P2: `ToolResponsePayloadNormalizer` — 단위 테스트 추가 (d471298)
- [x] P2: `BlockingToolCallbackInvoker` — 단위 테스트 추가 (3f904ea)
