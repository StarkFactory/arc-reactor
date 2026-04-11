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

---

## 11. 아카이브

- 대형 누적 보고서: `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`
- 이후 상세 Round 파일: `docs/reports/rounds/`
