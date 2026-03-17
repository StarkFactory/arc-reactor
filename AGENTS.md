# Arc Reactor — Agent Instructions

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot).
이 파일은 모든 AI 코딩 에이전트(Claude, Gemini, Cursor 등)가 이 코드베이스를 이해하고 안전하게 작업하기 위한 도메인 지식을 담고 있다.

---

## AI Agent 개발 핵심 가치

| 가치 | 정의 | Arc Reactor 적용 |
|------|------|-----------------|
| **안전 우선** | 에이전트 행동은 결정론적 코드로 제한. LLM 지시 이행에 의존하지 않는다 | Guard = fail-close. `ToolApprovalPolicy`로 위험 도구 실행 전 인간 승인 |
| **단순성 지향** | 가장 단순한 해법 우선. 측정 가능한 개선 시에만 복잡도 증가 | `AgentMode.STANDARD` vs `REACT` 분리. 도구 불필요 시 ReAct 비활성 |
| **투명한 추론** | 에이전트 판단 과정이 사후 추적 가능해야 한다 | `AgentMetrics` + `ArcReactorTracer`로 매 단계 span 기록 |
| **결정론적 제어** | 정책·제한·종료 조건은 프롬프트가 아닌 코드로 강제 | `maxToolCalls` → `activeTools = emptyList()`. Rate limit → Guard 카운터 |
| **확장 가능한 기본값** | 합리적 기본 동작 + 모든 구성 요소 교체 가능 | 전체 빈 `@ConditionalOnMissingBean`. 사용자 빈으로 오버라이드 |
| **비용 인식** | 모든 설계에서 정확도-비용 트레이드오프 명시적 고려 | `ResponseCache`, `CircuitBreaker`, `TokenEstimator` |
| **실패 격리** | 하나의 실패가 전체 파이프라인을 붕괴시키지 않는다 | Guard = fail-close, Hook = fail-open. `FallbackStrategy`로 대체 모델 전환 |

---

## Architecture

### 요청 흐름

```
Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response
```

**Guard = fail-close** (실패 = 차단). **Hook = fail-open** (실패 = 로깅 후 계속). 보안 로직은 반드시 Guard에만.

### 모듈 구조

| Module | Role |
|--------|------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler |
| `arc-web` | REST/SSE 컨트롤러, 인증, 멀티모달, OpenAPI |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) |
| `arc-admin` | 운영 제어: 메트릭, 테넌트, 알림, 비용/SLO |
| `arc-app` | 실행 조립 (bootRun/bootJar 진입점) |

### 핵심 파일 맵

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | 핵심 ReAct 루프 (~626 lines). 수정 시 극도로 주의 |
| `agent/impl/AgentExecutionCoordinator.kt` | 실행 분기, 캐시, 폴백 조율 |
| `agent/impl/ExecutionResultFinalizer.kt` | 최종 후처리 (OutputGuard, 경계, 필터, 히스토리) |
| `agent/impl/ToolCallOrchestrator.kt` | 도구 병렬 실행, 타임아웃, 승인 |
| `agent/impl/ManualReActLoopExecutor.kt` | Non-streaming ReAct 루프 구현 |
| `agent/impl/StreamingExecutionCoordinator.kt` | SSE 스트리밍 실행 |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | 빈 자동 구성 |
| `agent/config/AgentProperties.kt` | 전체 설정 (`arc.reactor.*`) |
| `agent/config/AgentPolicyAndFeatureProperties.kt` | 기능 토글 기본값 (~670 lines) |
| `memory/ConversationManager.kt` | 대화 히스토리 + 요약 |

---

## Critical Gotchas

**이 항목들은 미묘한 버그를 유발한다 — ReAct 루프나 코루틴 경계 수정 전 반드시 숙지:**

1. **CancellationException**: 모든 `suspend fun`에서 generic `Exception` catch 전에 먼저 catch & rethrow → 위반 시 구조적 동시성 파괴
2. **ReAct 무한 루프**: `maxToolCalls` 도달 시 `activeTools = emptyList()` 필수 → 로깅만 하면 무한 루프
3. **코루틴 내 .forEach**: `for (item in list)` 사용 → `.forEach {}` 람다는 suspend 불가
4. **메시지 쌍 무결성**: `AssistantMessage(toolCalls)` + `ToolResponseMessage`는 항상 쌍으로 추가/제거
5. **Context trimming**: Phase 2 가드 조건은 `>` (not `>=`) → off-by-one 시 마지막 UserMessage 손실
6. **AssistantMessage 생성자**: protected → `AssistantMessage.builder().content().toolCalls().build()`
7. **API key 환경변수**: `application.yml`에 빈 기본값 설정 절대 금지
8. **MCP 서버**: REST API로만 등록 → `application.yml` 하드코딩 금지
9. **Guard null userId**: 항상 `"anonymous"` 폴백 → Guard 건너뛰기 = 보안 취약점
10. **Spring AI mock**: 스트리밍 테스트에서 `.options(any<ChatOptions>())` 명시적 mock 필수
11. **toolsUsed**: 어댑터 존재 확인 후에만 도구명 추가 → LLM 환각 도구명 방지

---

## 확장 포인트

| Component | 규칙 | 실패 정책 |
|-----------|------|----------|
| **ToolCallback** | `"Error: ..."` 문자열 반환, throw 금지 | 에러 문자열 → LLM이 대안 탐색 |
| **GuardStage** | 내장 순서 1–5, 커스텀 10+ | fail-close (예외 → 요청 차단) |
| **Hook** | try-catch 필수, `CancellationException` rethrow | fail-open (로그 후 계속) |
| **Bean** | `@ConditionalOnMissingBean` 필수, `ObjectProvider<T>` for optional | JDBC 저장소는 `@Primary` |

### New Feature Checklist

1. 인터페이스 정의 (확장 가능하게)
2. 기본 구현체 작성
3. `ArcReactorAutoConfiguration`에 `@ConditionalOnMissingBean`으로 등록
4. `AgentTestFixture`로 테스트 작성
5. `./gradlew compileKotlin compileTestKotlin` — 0 warnings
6. `./gradlew test` — 전체 통과

---

## 코드 규칙

- 모든 주요 인터페이스는 `suspend fun` (예외: `ArcToolCallbackAdapter` — Spring AI 제약)
- 메서드 ≤20줄, 줄 ≤120자
- 한글 KDoc/주석 사용 (코드 내 영문 주석 금지)
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단, 클래스 바깥
- 모든 컨트롤러에 `@Tag`, 모든 엔드포인트에 `@Operation(summary = "...")`
- Admin 인증: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` 전용
- 모든 403 응답에 `ErrorResponse` 본문 필수

## 테스트 규칙

- `AgentTestFixture`: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- `AgentResultAssertions`: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- 모든 assertion에 실패 메시지 필수 — bare `assertTrue(x)` 금지
- suspend mock: `coEvery`/`coVerify`. `runTest` > `runBlocking`
- 타이밍 테스트: `CountDownLatch` + `Thread.sleep()` (가상 시계 아닌 실제 시간)

## 기본 설정 레퍼런스

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `concurrency.max-concurrent-requests` | 20 | `llm.max-output-tokens` | 4096 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |
| `boundaries.input-max-chars` | 10000 | `approval.enabled` | false |
| `memory.summary.enabled` | false | `scheduler.max-executions-per-job` | 100 |

**모든 기능은 기본 비활성(opt-in). API 키 하나로 안전하게 실행 가능.**

---

## 설계 원칙

### 1. 도구는 API다 — LLM이 호출한다는 전제로 설계하라

도구 설명에 목적, 파라미터 형식, 경계 조건, 유사 도구 차이를 명시. LLM은 스키마를 추론 못하면 파라미터를 환각한다.

### 2. 루프에는 반드시 탈출 조건이 있어야 한다

행동당 85% 정확도의 에이전트가 10단계를 성공할 확률은 ~20%. `maxToolCalls` + `withTimeout`으로 코드 수준 종료.

### 3. 보안은 Guard에, 확장은 Hook에 — 역할을 섞지 마라

인증·인가·검증·Rate limit → Guard. 로깅·메트릭·메모리 주입 → Hook.

### 4. 메시지 쌍 무결성을 보장하라

`AssistantMessage(toolCalls)` + `ToolResponseMessage`는 항상 쌍. 트리밍 시에도 쌍 단위.

### 5. 에이전트는 환경에서 근거를 얻어야 한다

매 ReAct 단계마다 도구 실행 결과를 LLM 컨텍스트에 포함. 결과 없이 추론만 연쇄하면 환각 누적.

### 6. 컨텍스트 윈도우는 유한한 자원이다

`ConversationManager`로 히스토리 관리, `TokenEstimator`로 추적. RAG는 rerank 후 상위 N개만.

### 7. 도구 출력을 신뢰하지 마라

외부 API/MCP 응답은 간접 인젝션 경로. `ToolOutputSanitizer` + Output Guard로 정제·검증.

### 8. 동시성 모델을 존중하라

`CancellationException`을 generic `Exception` 전에 먼저 catch & rethrow. 위반 시 코루틴 트리 취소 전파 파괴.

### 9. 실패를 숨기지 말고 명시적으로 전파하라

Tool 실패 → `"Error: ..."` 반환 (LLM이 대안 탐색). Guard 실패 → 예외 throw (요청 차단).

### 10. 관측 가능성은 선택이 아니라 필수다

`AgentMetrics` + `ArcReactorTracer`로 각 ReAct 단계 추적. HTTP 200만 모니터링하면 논리적 실패 감지 불가.

---

## Anti-Patterns — 피해야 할 패턴

| 패턴 | 위험 | 대안 |
|------|------|------|
| **무한 루프** — 코드 수준 종료 조건 없음 | API 비용 폭증, 서비스 장애 | `maxToolCalls` + `withTimeout` |
| **프롬프트 의존 보안** — "삭제하지 마세요" 지시만 | LLM은 확률적. Instruction Drift | Guard fail-close + `ToolApprovalPolicy` |
| **전지적 컨텍스트** — 모든 것을 주입 | 토큰 비용, Lost in the Middle | `TokenEstimator` + rerank + 트리밍 |
| **조용한 실패** — 에러를 빈 문자열로 반환 | 잘못된 전제 위 추론 지속 | `"Error: {원인}"` 반환, 메트릭 기록 |
| **프레임워크 블랙박스** — 내부 흐름 미이해 | 디버깅 불가 | `ManualReActLoopExecutor` 직접 확인 |
| **환각 도구** — 존재하지 않는 도구 호출 | 루프 낭비, 잘못된 실행 | `toolsUsed` 어댑터 확인 후 추가 |
| **폴링 세금** — 같은 API 수백 번 반복 | Rate limit 도달, 비용 폭증 | 이벤트 기반 + 지수 백오프 + `tool-call-timeout-ms` |
