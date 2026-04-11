# Arc Reactor 상용화 준비도 보고서

> **작성일**: 2026-03-28  
> **현재 형식 전환일**: 2026-04-12  
> **대상 시스템**: Arc Reactor v6.x  
> **목적**: 출시 직전 운영 상태, 핵심 KPI, 최근 라운드 추세를 짧게 유지하는 상태판

---

## 1. 이 문서의 역할

이 문서는 더 이상 모든 라운드의 상세 로그를 한 파일에 누적하지 않는다.

- `docs/production-readiness-report.md`
  현재 상태, 핵심 게이트, 최근 Round 요약만 유지하는 메인 상태판
- `docs/reports/rounds/R{N}.md`
  각 Round의 상세 작업 기록
- `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`
  2026-04-12 이전의 대형 누적 보고서 아카이브

**운영 원칙**:

- 메인 보고서는 짧고 최신이어야 한다.
- 상세 로그는 라운드별 파일로 분리한다.
- 메인 보고서의 `10. 반복 검증 이력`에는 최근 Round 요약만 유지한다.
- 오래된 상세 이력은 아카이브나 개별 Round 파일에서 찾는다.

---

## 2. 현재 상태

### 종합 판단

출시 준비는 **진행 중**이다.  
코드베이스와 운영 루프는 많이 단단해졌지만, 메인 보고서 역시 에이전트가 반복 실행하기 쉬운 구조로 정리해야 했다.

### 현재 집중 축

- `grounded_retrieval`
- `cross_source_synthesis`
- `safe_action_workflows`
- `admin_productization`

### 운영 메모

- 메인 보고서는 상태판 역할만 수행한다.
- 상세 근거와 설계 판단은 각 `R{N}.md`에 남긴다.
- watchdog/cron은 메인 보고서의 마지막 Round 번호를 기준으로 다음 Round를 이어간다.

---

## 3. 핵심 출시 게이트

| 항목 | 상태 | 메모 |
|---|---|---|
| 빌드 안정성 | 진행 중 | warning baseline 악화 금지 원칙 적용 |
| 테스트 회귀 | 진행 중 | 전체 테스트는 계속 확인하되, 모듈별 회귀 증거를 우선 축적 |
| grounded answer 품질 | 진행 중 | 출처, tool family correctness, synthesis 품질 누적 개선 필요 |
| action safety | 진행 중 | preview/approval/write-policy 회귀 테스트 계속 보강 |
| 운영자 가시성 | 진행 중 | top missing query, blocked cluster, lane health 관측 강화 필요 |

---

## 4. 보고 규칙

새 Round를 수행할 때는 아래 두 군데를 함께 갱신한다.

1. `docs/reports/rounds/R{N}.md`
   상세 보고서. Benchmark hypothesis, 변경 파일, 테스트, evidence, remaining gap를 전부 기록
2. `docs/production-readiness-report.md`
   `10. 반복 검증 이력`에 짧은 요약 엔트리만 추가

메인 보고서의 `10. 반복 검증 이력`은 **최근 20개 Round만 유지**한다.  
20개를 넘기면 오래된 요약 엔트리는 메인 보고서에서 제거하고, 상세는 개별 Round 파일에 남긴다.

---

## 5. 최근 운영 관찰

- 거대한 단일 보고서는 읽기 비용이 높아 다음 Round 의사결정 품질을 떨어뜨린다.
- 메인 상태판과 상세 로그를 분리하면 최근 5개 Round 문맥을 더 안정적으로 읽을 수 있다.
- 이후 watchdog 루프는 메인 보고서에서 Round 번호와 최근 요약만 읽고, 필요 시 상세 Round 파일을 추가로 연다.

---

## 10. 반복 검증 이력

아래는 메인 상태판에 유지하는 **최근 Round 요약**이다.  
상세 내용은 legacy archive 또는 각 `docs/reports/rounds/R{N}.md` 파일에서 찾는다.

### Round 301 — 2026-04-12T19:30+09:00 — arc-admin blocking JDBC 1차 batch

- axis: `admin_productization`
- 분류: `direct_value`
- 요약: SlackActivity, ToolCall, EvalDashboard 컨트롤러를 suspend + IO dispatch로 이동
- 상세 위치: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`

### Round 302 — 2026-04-12T20:00+09:00 — arc-admin blocking JDBC 2차 batch

- axis: `admin_productization`
- 분류: `direct_value`
- 요약: Latency, TokenCost, Usage, ConversationAnalytics 컨트롤러 블로킹 경로 정리
- 상세 위치: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`

### Round 303 — 2026-04-12T20:30+09:00 — arc-admin blocking JDBC 3차 batch part 1

- axis: `admin_productization`
- 분류: `direct_value`
- 요약: Trace, TenantAdmin 컨트롤러 12개 핸들러를 suspend + IO로 전환
- 상세 위치: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`

### Round 304 — 2026-04-12T21:00+09:00 — PlatformAdminController blocking JDBC 정리

- axis: `admin_productization`
- 분류: `direct_value`
- 요약: PlatformAdminController의 blocking JDBC를 suspend + IO로 분리하고 cycle 5를 종결
- 상세 위치: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`

### Round 305 — 2026-04-12T21:30+09:00 — IntentRegistry Caffeine + startup fallback

- axis: `employee_value`
- 분류: `foundation`
- 요약: IntentConfiguration startup fallback과 InMemoryIntentRegistry bounded cache를 추가해 cycle 6을 시작
- 상세 위치: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`

### Round 330 — 2026-04-12T22:00+09:00 — cycle 10 시작: MCP connection race 묶음

- axis: `connector_permissions`
- 분류: `direct_value`
- 요약: `McpConnectionSupport` 공유 HttpClient.Builder mutate race 제거(`newHttpClientBuilder()` 함수화) + `handleConnectionError` client identity 비교 추가(`onConnectionError` 시그니처에 `failingClient` 추가, stale 콜백이 신규 클라이언트를 FAILED로 되돌리는 race 차단). 테스트 신규 1 + 기존 4 갱신, 4 모듈 PASS.
- 상세 위치: `docs/reports/rounds/R330.md`

### Round 331 — 2026-04-12T22:30+09:00 — cycle 10 2차: McpHealthPinger 0-tool 무한 재연결 루프 차단

- axis: `connector_permissions`
- 분류: `direct_value`
- 요약: `checkConnectedHealth`가 `tools.isEmpty()`을 무조건 degradation으로 간주해 legitimately 0-tool MCP 서버(MCP 프로토콜 상 유효)를 5분 쿨다운마다 영원히 재연결 루프에 태우던 버그를 수정. `seenNonEmptyServers` Caffeine 트래커를 도입해 "이전에 non-empty를 관찰한 적 있는 서버"에 한해서만 empty 전이를 퇴화로 간주. 기존 테스트 2개 semantics 업데이트 + R331 회귀 2건 신규(0-tool 안정 케이스 + non-empty → empty 전이 케이스). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R331.md`

### Round 332 — 2026-04-12T23:00+09:00 — cycle 10 3차: ensureConnected PENDING + autoConnect=true 첫 연결 지원

- axis: `connector_permissions`
- 분류: `direct_value`
- 요약: `McpHealthPinger.pingAllConnectedServers`(R173)는 PENDING 서버에 대해 `attemptReconnectWithCooldown → ensureConnected` 경로로 첫 연결을 시도하도록 확장됐으나, `DefaultMcpManager.ensureConnected`가 PENDING 상태를 거부해 R173 의도가 silent하게 무효화되고 있었다. autoConnect=true 서버만 PENDING에서 첫 연결을 허용하도록 분기 추가. autoConnect=false(수동 관리) 서버는 기존 대로 명시적 connect를 기다린다. 기존 테스트 1개 이름/의미 명확화 + R332 회귀 1건 신규(PENDING → FAILED 전이 검증으로 connect 도달 입증). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R332.md`

### Round 333 — 2026-04-12T23:30+09:00 — cycle 10 4차 / axis 전환: PlanExecute synthesize 실패 step 마커 구분

- axis: `cross_source_synthesis`
- 분류: `direct_value`
- 요약: `connector_permissions` 3회 연속 이후 `cross_source_synthesis`로 axis 전환. scanner가 ToolResponseSummarizer/PlanExecute/ToolCallOrchestrator에서 3건 발견, 그중 P1 HIGH(`PlanExecuteStrategy.synthesize`가 실패 step의 `"Error: TOOL_ERROR"` 내부 마커를 성공 step과 동등하게 LLM 프롬프트에 주입 → 혼합 케이스 환각 위험)만 처리. resultSummary 빌더를 3-way when 분기(성공 + non-blank / 성공 + blank / 실패)로 재작성해 실패 step은 `[실패] 답변 근거로 사용하지 마세요` 마커로만 대체, 원본 에러 문자열은 LLM 경로에서 완전 제거. 신규 회귀 1건(buildRequestSpec lambda로 synthesize 프롬프트 캡처 후 마커 포함/에러 문자열 미포함 검증). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R333.md`

### Round 334 — 2026-04-13T00:00+09:00 — cycle 10 5차: mergeSignalMetadata blockReason first-wins

- axis: `cross_source_synthesis` (2회 연속)
- 분류: `direct_value`
- 요약: 병렬 tool call 중 한 source가 `blockReason`(보안 차단 신호)을 먼저 보낸 뒤 다른 source가 다른 차단 사유를 보내면, 기존 last-wins 로직이 원래 차단의 맥락을 **덮어써** 보안 신호가 손실되던 버그 수정. 재평가 결과 `answerMode`/`freshness`/`retrievedAt`/`grounded` 4개 키는 `ToolCallOrchestratorTest:517` `"Last merged signal should win metadata projection"`이 lock한 **의도적 설계**였고, `blockReason`만 도메인 의미 상 **first-wins**(한 번 차단이 영구 차단)여야 한다. `mergeSignalMetadata`에서 `blockReason`만 narrow 분기 추가, 다른 4개 키 경로 불변. downstream 소비자(`AgentExecutionCoordinator`, `AgentMetrics`, `EvaluationMetricsHook`, `ExecutionResultFinalizer`)는 모두 존재 여부로 판정하므로 의미 변화가 downstream 의도와 일치. 신규 회귀 1건(병렬 2 tool 각자 다른 blockReason → 첫 차단 유지 + 리스트 누적 보존 + answerMode last-wins 교차 검증). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R334.md`

### Round 335 — 2026-04-13T00:30+09:00 — cycle 10 6차: ToolResponseSummarizerHook counter atomic compute

- axis: `cross_source_synthesis` (3회 연속)
- 분류: `direct_value`
- 요약: `ToolResponseSummarizerHook.incrementCounter`의 read-modify-write 3-step이 non-atomic 이어서 `ToolCallOrchestrator.executeInParallel`에서 병렬 tool 실행 시 카운터가 언더카운트되는 버그 수정. 기존 주석은 "원자적"이라 거짓 기재. `HookContext.metadata` 기본 backing이 `ConcurrentHashMap`이라 `compute` 함수로 atomic read-modify-write 수행. `MutableMap` 인터페이스 유지를 위해 runtime 타입 체크 + synchronized fallback 제공 → 테스트 double도 호환. 신규 회귀 1건(`runBlocking + Dispatchers.Default + async/awaitAll`로 200회 병렬 afterToolCall → 카운터 == 200 검증, `runTest`는 단일 쓰레드라 race 재현 불가하므로 명시적 Dispatchers.Default 강제). R333 scanner batch(P1 HIGH + P2 MED × 2) 완결. 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R335.md`

### Round 336 — 2026-04-13T01:00+09:00 — axis 전환 / cycle 10 7차: HITL approval modifiedArguments silent 무시 버그 수정

- axis: `safe_action_workflows`
- 분류: `direct_value`
- 요약: `cross_source_synthesis` 3회 연속 후 tie-break 다음 우선순위인 `safe_action_workflows`로 axis 전환. approval 경로 scanner가 P1 HIGH 1건 발견 — `ToolApprovalResponse.modifiedArguments`가 `ApprovalModels`/`Inmemory|Jdbc PendingApprovalStore`에는 완벽히 구현되어 있으나 `ToolCallOrchestrator`에 참조 0건이라 **사람이 승인 단계에서 파라미터를 수정해도 원본 LLM 인자로 실행**되던 silent 버그. HITL 핵심 UX("사람이 금액/대상 범위 조정하여 승인")가 모델/API 레이어까지만 동작하고 실행 레이어에서 완전히 무효화된 상태였다. `recordApprovalMetadata`에 side-channel 저장(`hitlModifiedArgs_{suffix}` — 기존 `hitlWaitMs_`/`hitlApproved_` 패턴 재사용) + `applyApprovedModifications` 헬퍼로 단일/병렬 두 실행 경로 모두에서 `toolCallContext.copy(toolParams=...)` 교체. 병렬 경로는 `serializeToolInput`으로 toolInput 재직렬화. 기존 `checkToolApproval` 체인의 `String?` signature 완전 불변 유지(minimum-invasive). 신규 end-to-end 회귀 1건(TrackingTool.capturedArgs로 amount 1000→500 수정 반영 검증 + orderId 비수정 필드 원본 유지 검증). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R336.md`

### Round 337 — 2026-04-13T01:30+09:00 — cycle 10 8차: InMemoryPendingApprovalStore evict overflow 응답 전달

- axis: `safe_action_workflows` (2회 연속)
- 분류: `direct_value`
- 요약: `InMemoryPendingApprovalStore`의 Caffeine bounded cache가 `maximumSize` 초과 시 entry를 evict하면서 `PendingEntry.deferred`를 완료시키지 않아 (1) 사용자 요청은 `withTimeoutOrNull` 전체 타임아웃(기본 5분)까지 대기, (2) 관리자 `approve(id)` 호출은 `getIfPresent(id) == null`이라 `false` 반환 → "승인 실패"로 보이는 silent UX 버그 수정. Caffeine builder에 `removalListener`를 등록해 `SIZE` cause evict 시 `deferred.complete(ToolApprovalResponse(approved=false, reason="Approval store overflow ..."))` 호출하여 대기 사용자에게 **즉시** overflow 응답 전달. `EXPLICIT`/`REPLACED`/`EXPIRED`/`COLLECTED` 등 정상 흐름은 무시. 테스트 전용 `internal forceCleanUp` helper 추가로 Caffeine 비동기 eviction을 deterministic하게 drain. 신규 회귀 1건(maxPending=1, 두 요청 중 W-TinyLFU admission 결과에 따라 "하나라도" overflow 응답을 받는지 검증). 기존 R310 bounded cache 회귀 전부 유지 PASS. 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R337.md`

---

## 11. 아카이브

- 대형 누적 보고서: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`
- 이후 상세 Round 파일: `docs/reports/rounds/`
