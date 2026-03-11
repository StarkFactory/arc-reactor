# Implementation Plan
> 마지막 업데이트: 2026-03-12 | 분석 반복: 5회차 (5개 병렬 서브에이전트 전체 탐색)

## P0 — 즉시 수정 (런타임 크래시, 데이터 손실)

(없음)

## P1 — Agent 동작 결함 (잘못된 응답, 무한 루프, 도구 실패 미처리)

(없음 — Critical Gotchas 위반 0건 확인)

## P2 — 견고성 (에러 복구 실패, 리소스 누수, 동시성 문제)

- [ ] `Bm25Scorer.kt:18` — 싱글톤 빈의 mutable 필드 5개 (docContents, termFrequencies 등)가 동기화 없이 index()/score()에서 읽기·쓰기. RAG 인제스션과 쿼리 동시 실행 시 corrupted scoring state 가능
- [ ] `PlatformAdminController.kt:282` — getCurrentMonthUsage() 실패 시 `catch (_: Exception)` 로 무조건 삼킴, 로깅 없음. DB 장애 시 모든 테넌트 사용량이 0으로 표시

## P3 — 보안 (권한 우회, 인젝션, 정보 노출)

- [ ] `McpAccessPolicyController.kt:286` — MCP 연결 실패 시 exception message를 응답에 그대로 반환, 내부 호스트명·포트 노출 가능. McpSwaggerCatalogController.kt:336, McpPreflightController.kt:142도 동일
- [ ] `ErrorReportController.kt:124-126` — API 키 비교에 `==` 사용, timing attack에 취약. MessageDigest.isEqual() 또는 상수 시간 비교 필요
- [ ] `McpConnectionSupport.kt:92-108` — SSE MCP 서버 URL에 사설 IP(127.0.0.1, 169.254.x, 10.x 등) 차단 없음, admin 계정 탈취 시 SSRF 가능

## P4 — 성능 / 코드 품질 / 문서화

### 핫패스 Regex 컴파일
- [ ] `ResponseValueInsights.kt:54` — `Regex("\\s+")` 매 호출마다 컴파일. companion object로 추출
- [ ] `WorkContextForcedToolPlanner.kt:1103` — `Regex("[^a-z0-9._-]")` inline 컴파일. companion object로 추출
- [ ] `OutputGuardRuleEvaluator.kt:16` — `Regex(rule.pattern)` 매 평가마다 컴파일. 패턴 캐시 필요
- [ ] `RagIngestionCaptureHook.kt:111-121` — compileRegex() 매 호출마다 실행, 캐시 없음

### 중복 로직
- [ ] `SemanticToolSelector.kt` + `SystemPromptBuilder.kt` + `WorkContextForcedToolPlanner.kt` — ISSUE_KEY_REGEX 3곳 동일 정의. 공통 상수 추출
- [ ] `SemanticToolSelector.kt` + `SystemPromptBuilder.kt` — OPENAPI_URL_REGEX, WORK_SERVICE_CONTEXT_HINTS 2곳 동일 정의
- [ ] `PiiDetectionGuard.kt:49-69` + `PiiMaskingOutputGuard.kt:61-85` — PII 정규식 패턴 (주민등록번호, 전화, 카드, 이메일) 동일 중복
- [ ] `DefaultGuardStages.kt:172-223` + `ToolOutputSanitizer.kt:56-74` — 프롬프트 인젝션 탐지 패턴 상당 부분 중복

### 기타 코드 품질
- [ ] `SlackApiClient.kt:759` — `Thread.sleep(delayMs)` 가 코루틴 컨텍스트에서 호출됨. `delay()` 또는 `withContext(Dispatchers.IO)` 필요
- [ ] `DocumentController.kt:173` — TODO: chunk_total 메타데이터 저장으로 O(maxNumChunks) 탐색 제거
- [ ] `ResponseValueInsights.kt:72` — `MessageDigest.getInstance("SHA-256")` 매 호출마다 룩업

## 완료
- [x] P2: `ToolCallOrchestrator.kt:102,240` — 실패한 도구의 에러 메시지 sanitize 적용 (613d0a0)
- [x] P2: `WorkerAgentTool.kt:58` — timeoutMs 미설정 → 15초 tool-call timeout으로 워커 강제 취소 — 수정 완료 (f452489)
- [x] P3: `SessionController.kt:135,152` — Content-Disposition header injection — 수정 완료 (6c31022)
- [x] P3: `AuthRateLimitFilter.kt:55` — X-Forwarded-For trust를 opt-in으로 변경 — 수정 완료 (6e8640f)
- [x] P5: `VerifiedSourcesResponseFilter` — casual/general 경계값 테스트 4개 추가 (83ade27, 3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:127-132` — looksLikeInformationRequest ?/패턴 과잉 차단 수정 (3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:162-164` — CASUAL_PROMPTS에 한국어 변형 추가 (83ade27)

## 완료 (이전 사이클)
- [x] P1: `ConversationMemoryStressTest.kt` — 9개 catch 블록에 throwIfCancellation 추가 (9657db0)
- [x] P2: `TokenEstimator` — DefaultTokenEstimator 단위 테스트 추가 (72e4753)
- [x] P2: `ToolResponsePayloadNormalizer` — 단위 테스트 추가 (d471298)
- [x] P2: `BlockingToolCallbackInvoker` — 단위 테스트 추가 (3f904ea)
