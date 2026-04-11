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

### Round 338 — 2026-04-13T02:00+09:00 — cycle 10 9차: JdbcPendingApprovalStore cleanup 쿼리 확장 (TIMED_OUT + resolved_at NULL 회수)

- axis: `safe_action_workflows` (3회 연속)
- 분류: `direct_value`
- 요약: `JdbcPendingApprovalStore.cleanupResolvedRows`의 DELETE 쿼리가 `AND resolved_at IS NOT NULL`만 조건으로 사용해, `resolved_at`이 NULL인 채 남은 resolved 상태 행(특히 폴링 루프 TIMED_OUT early return 경로, 다중 인스턴스 race, legacy migration, 외부 DBA 조작 등)이 retention 기간과 무관하게 **영구 잔류**하는 silent DB leak 수정. WHERE 절을 `(resolved_at IS NOT NULL AND resolved_at < cutoff) OR (resolved_at IS NULL AND requested_at < cutoff)`로 확장해 병적 행을 `requested_at` fallback으로 회수. 같은 cutoff 파라미터를 두 번 바인딩. 기존 정상 경로(`resolved_at` 세팅)는 behavior 완전 불변. 신규 회귀 1건(H2 embedded DB에 TIMED_OUT + resolved_at=NULL 행 3가지 변형 insert → listPending 호출로 cleanup 트리거 → 병적 old 행은 삭제되고 fresh 행과 정상 old 행은 기존 로직 대로 처리되는지 검증). 기존 R23 cleanup 회귀 PASS 유지. 전체 arc-core PASS. **`safe_action_workflows` 3회 연속 — R339부터 axis 전환 권장** (admin_productization 또는 employee_value).
- 상세 위치: `docs/reports/rounds/R338.md`

### Round 339 — 2026-04-13T02:30+09:00 — axis 전환 / cycle 10 10차: safety rejection reason tag 카디널리티 정규화

- axis: `admin_productization`
- 분류: `direct_value`
- 요약: `safe_action_workflows` 3회 연속 후 tie-break 다음 우선순위인 `admin_productization`으로 axis 전환. metrics/diagnostics scanner가 발견한 P2 MED 3건 중 가장 작은 scope + 직접 가치인 `eval.safety.rejection` reason tag unbounded 카디널리티 처리. `MicrometerEvaluationMetricsCollector.recordSafetyRejection`이 Guard/Hook이 자유 문자열로 설정하는 `blockReason`을 그대로 Micrometer Counter tag로 등록해 Prometheus 시계열 카디널리티 폭발 위험(PII/동적 ID 포함 시 장기 운영 중 registry OOM, scrape 지연, Grafana 대시보드 가치 저하). `EvaluationMetricsHook.normalizeReasonTag` private helper로 `recordSafetyRejections` 호출 직전 64자 상한 + `A-Za-z0-9_.-` 외 문자 `_` 치환 + blank `"unknown"` 폴백 적용. Collector 인터페이스는 완전 불변(caller 측 normalize로 모든 구현체 동시 보호). 신규 회귀 2건(긴 자유 문자열 한글/특수문자 정규화 + blank unknown 폴백) + 기존 3개 safety rejection 테스트 공백→`_` 정규화 의도로 업데이트. 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R339.md`

### Round 340 — 2026-04-13T03:00+09:00 — cycle 10 11차: MissingQueryAggregate lastOccurredAt AtomicReference max update

- axis: `admin_productization` (2회 연속)
- 분류: `direct_value`
- 요약: R339 scanner defer P2 MED #3 처리. `trackMissingQuery`가 `count.incrementAndGet()` 후 별도 라인에서 `lastOccurredAt = Instant.now()`를 실행하는 2-step non-atomic write → 병렬 스레드 간 write 순서 뒤바뀜으로 **stale timestamp**가 최종 값으로 남는 race. 운영자 Grafana "top missing query" 패널에 "count는 증가하는데 마지막 발생 시각은 정체"로 보이는 admin_productization 가시성 저하 버그. `MissingQueryAggregate.lastOccurredAt`을 `@Volatile var Instant` → `val AtomicReference<Instant>`로 변경하고 `updateAndGet { prev -> if (now.isAfter(prev)) now else prev }` CAS 루프로 atomic max 갱신. `MicrometerAgentMetrics` + `AgentMetrics` 두 공유 소비자 read/write 경로 업데이트, `topMissingQueries`의 정렬 키 및 `MissingQueryInsight` 생성 시 `.get()` 변환. public `MissingQueryInsight.lastOccurredAt: Instant` 타입 완전 불변. 신규 병렬 회귀 1건(runBlocking + Dispatchers.Default + 4 스레드 × 500회 = 2000회 호출 후 count 정확도 + lastOccurredAt이 [startAt, endAt] 범위 내 검증). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R340.md`

### Round 341 — 2026-04-13T03:30+09:00 — cycle 10 12차: MicrometerAgentMetrics channel tag bounded cardinality

- axis: `admin_productization` (3회 연속)
- 분류: `direct_value`
- 요약: R339 scanner defer P2 MED #2 처리 (scanner batch 완결). `MicrometerAgentMetrics.recordUnverifiedResponse`와 `recordStageLatency`가 `metadata["channel"]` raw 값을 Micrometer Counter/Timer 태그로 직접 등록해 Slack 채널 ID 등 무제한 카디널리티가 Prometheus 레지스트리에 누적되는 silent drift. `channelCounts` Caffeine(10k)는 `recordResponseObservation` 경로의 in-memory bucket만 보호하고 Micrometer 레지스트리 태그 경로는 무방비였다. 새 `unverifiedChannelTagBudget` Caffeine bounded cache(MAX_UNVERIFIED_CHANNEL_TAGS=128) + 공유 helper `boundedChannelTag`로 두 call site에 동일 정책 적용: null/blank→"unknown", 64자 절단, budget 내 재사용, budget 내 새 값 추가, 상한 초과 시 "other" 폴백. RecentTrustEvent는 이미 bounded deque라 raw 값 유지. companion object에 4개 상수(MAX_UNVERIFIED_CHANNEL_TAGS, MAX_CHANNEL_TAG_LENGTH, UNKNOWN_CHANNEL_TAG, OVERFLOW_CHANNEL_TAG) 추가. 신규 회귀 2건(budget+50개 고유 channel 주입 후 distinct 태그 수 ≤ budget+2 + "other" 존재 검증, null/empty/whitespace 3변형 → "unknown" 폴백 검증). **R339 admin_productization scanner batch(P2 MED #1/#2/#3) 완결.** 전체 arc-core PASS. **R342부터 axis 전환 권장** (employee_value).
- 상세 위치: `docs/reports/rounds/R341.md`

### Round 342 — 2026-04-13T04:00+09:00 — axis 전환 / cycle 10 13차: ExecutionResultFinalizer grounded false-negative 수정

- axis: `employee_value`
- 분류: `direct_value`
- 요약: `admin_productization` 3회 연속 후 tie-break 다음 우선순위인 `employee_value`로 axis 전환. response 조립 scanner가 P1 MED 1건 발견 — `mergeVerifiedSourcesMetadata`의 grounded 판정이 `latestSignal?.grounded ?: verifiedSources.isNotEmpty()` Elvis 체인이라 tool이 명시적으로 `grounded=false`(non-null)를 반환하면 우변이 평가되지 않아, 실제 verifiedSources가 존재해도 최종 metadata에 `grounded=false`가 기록되는 **false-negative**. tool의 partial match 신호 등이 이 경로를 트리거해 직원이 근거 있는 답변을 받았음에도 "ungrounded"로 평가절하되고 Grafana employee_value 대시보드의 grounded coverage KPI가 저평가되는 silent drift. 1줄 수정: `(latestSignal?.grounded == true) || verifiedSources.isNotEmpty()`로 변경해 "signal true 또는 sources 존재"를 grounded로 판정. "signal false + sources 없음" 케이스는 여전히 false 유지(의미 보존). 신규 회귀 2건(false-negative fix + negative 유지 cross-verify). 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R342.md`

### Round 343 — 2026-04-13T04:30+09:00 — cycle 10 14차: VerifiedSourceExtractor URL dedup 정규화

- axis: `employee_value` (2회 연속)
- 분류: `direct_value`
- 요약: R342 scanner LOW #1 처리. `VerifiedSourceExtractor.extract`의 `distinctBy { it.url }`이 문자열 raw 비교라 `https://wiki.company.com/page/123`, `.../page/123/`(trailing slash), `.../page/123#section`(fragment)이 각각 별개 source로 취급되어 직원이 응답 Sources 블록에서 같은 페이지 링크를 중복으로 보는 user-visible 버그. Confluence/Jira 도구가 `self.href` + `webUrl` + path 표현 변형을 동시에 반환하는 현실적 케이스에서 자주 트리거. MAX_SOURCES=12 한도가 동일 페이지 중복으로 낭비되어 실제 다른 출처가 잘려나가는 2차 영향도. `normalizeUrlForDedup` private helper 추가: fragment 제거 + trailing slash 정리 (scheme:// 엣지 케이스 보존). `distinctBy { normalizeUrlForDedup(it.url) }`로 key selector만 변경. 원본 URL은 `distinctBy`의 "첫 번째 발견 유지" 의미로 보존되어 사용자 링크 표시 자연스러움 유지. 신규 회귀 3건(trailing slash dedup, fragment dedup, 조합 dedup) + 기존 VerifiedSourceTest 3건 PASS 유지. 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R343.md`

### Round 344 — 2026-04-13T05:00+09:00 — cycle 10 15차: toolFamily 분류에 spec_ 추가

- axis: `employee_value` (3회 연속)
- 분류: `direct_value`
- 요약: R342 scanner LOW #2 처리. `ExecutionResultFinalizer.toolFamily`가 `confluence_`/`jira_`/`bitbucket_`/`work_`/`mcp_`만 인식하고 **`spec_`(Swagger/OpenAPI 도구) 분기가 없어 `"other"`로 폴백**. 반면 `VerifiedSourcesResponseFilter.WORKSPACE_TOOL_PREFIXES`는 이미 `spec_`을 workspace 도구로 인식해 두 코드 경로의 분류 의도가 silent 불일치. 결과: `spec_search`/`spec_detail` 같은 Swagger/OpenAPI 도구 호출이 `metadata["toolFamily"] = "other"`로 기록되어 Grafana "tool family usage" 패널에서 spec 도구 사용률이 invisible, `employee-value-rollout-300.md` "tool family correctness" KPI 저평가. 1줄 수정: `when` 분기에 `toolName.startsWith("spec_") -> "spec"` 추가. `mcp_` 앞에 배치해 prefix 순서 명시. 신규 회귀 2건(mockk slot capture로 `recordResponseObservation` event metadata 검증 + 7가지 prefix 매트릭스 cross-verify). 전체 arc-core PASS. **`employee_value` axis 3회 연속 완료 — R345부터 axis 전환 또는 cycle 10 cleanup 권장.**
- 상세 위치: `docs/reports/rounds/R344.md`

### Round 345 — 2026-04-13T05:30+09:00 — cycle 10 16차: stripSourcesBlock 본문 중간 절단 edge case 방어

- axis: `employee_value` (4회 연속)
- 분류: `direct_value`
- 요약: R342 scanner LOW #4 처리. `VerifiedSourcesResponseFilter.stripSourcesBlock`이 `matches.first()`로 첫 "Sources" 헤딩 위치부터 본문 끝까지 무조건 substring으로 잘라내던 구현에서, LLM이 답변 중간에 서술적 `Sources:` 단독 라인을 작성하고 뒤에 URL 없는 설명 + 결론 본문이 이어지는 경우 **본문 결론 전체가 조기 절단**되는 user-visible 버그. 첫 매칭 시작점은 유지하되 below content에 실제 출처 목록 패턴(`- http`, `* http`, `- [`, `* [`, 또는 bare `http` 시작 라인)이 **존재할 때만** 절단하고, 없으면 서술적 언급으로 간주해 본문 보존. `BULLET_CHARS = setOf('-', '*')` 상수 추가. 기존 31개 VerifiedSourcesResponseFilter 테스트 전부 PASS 유지(Arc Reactor 정상 Sources 블록은 bullet + markdown link 형식이라 새 check 통과) + 신규 회귀 2건(영문/한글 서술적 Sources 언급 + URL 없음 → 본문 결론 보존 검증). 라운드 중간에 `McpToolCallback` 에러 메시지 sanitize 시도를 먼저 진행했으나 기존 테스트가 클래스명 포함을 명시적 lock하고 있어 정책 변경 성격이라 safety rail §0.6 #8에 따라 철회 후 R342 LOW #4로 전환. 전체 arc-core PASS.
- 상세 위치: `docs/reports/rounds/R345.md`

---

## 11. 아카이브

- 대형 누적 보고서: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`
- 이후 상세 Round 파일: `docs/reports/rounds/`
