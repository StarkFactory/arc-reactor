# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot). Fork 후 도구를 연결하여 사용.

## Tech Stack

- Kotlin 2.3.20, Spring Boot 3.5.9, Spring AI 1.1.3, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew test                                             # 전체 테스트
./gradlew test --tests "com.arc.reactor.agent.*"           # 패키지 필터
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # 단일 파일
./gradlew compileKotlin compileTestKotlin                  # 컴파일 체크 (0 warnings 필수)
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필수)
./gradlew test -Pdb=true                                   # PostgreSQL/PGVector/Flyway 포함
./gradlew test -PincludeIntegration                        # @Tag("integration") 테스트 포함
```

## Environment Variables

| Variable | Required | When |
|----------|----------|------|
| `GEMINI_API_KEY` | 필수 | bootRun |
| `SPRING_AI_OPENAI_API_KEY` | 선택 | OpenAI 백엔드 |
| `SPRING_AI_ANTHROPIC_API_KEY` | 선택 | Anthropic 백엔드 |
| `SPRING_DATASOURCE_URL` | 선택 | 운영 DB (`jdbc:postgresql://localhost:5432/arcreactor`) |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | 선택 | 운영 DB (`arc` / `arc`) |
| `SPRING_FLYWAY_ENABLED` | 선택 | DB 마이그레이션 (`true`) |

**절대 `application.yml`에 빈 기본값으로 provider API 키를 설정하지 말 것.**

---

## AI Agent 개발 핵심 가치

| 가치 | 정의 | Arc Reactor 적용 |
|------|------|-----------------|
| **안전 우선** | 에이전트 행동은 결정론적 코드로 제한. LLM 지시에 의존하지 않는다 | Guard = fail-close. `ToolApprovalPolicy`로 위험 도구 실행 전 인간 승인 |
| **단순성 지향** | 가장 단순한 해법 우선. 측정 가능한 개선 시에만 복잡도 증가 | `AgentMode.STANDARD` vs `REACT` 분리 |
| **투명한 추론** | 에이전트 판단 과정이 사후 추적 가능해야 한다 | `AgentMetrics` + `ArcReactorTracer`로 매 단계 span 기록 |
| **결정론적 제어** | 정책·제한·종료 조건은 프롬프트가 아닌 코드로 강제 | `maxToolCalls` → `activeTools = emptyList()`. Rate limit → Guard |
| **확장 가능한 기본값** | 합리적 기본 동작 + 모든 구성 요소 교체 가능 | 전체 빈 `@ConditionalOnMissingBean` |
| **비용 인식** | 모든 설계에서 정확도-비용 트레이드오프 명시 | `CostAwareModelRouter`, `StepBudgetTracker`, `ResponseCache` |
| **실패 격리** | 하나의 실패가 전체 파이프라인을 붕괴시키지 않는다 | Guard = fail-close, Hook = fail-open |

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
| `agent/routing/CostAwareModelRouter.kt` | 비용/품질 기반 동적 모델 라우팅 |
| `agent/budget/StepBudgetTracker.kt` | ReAct 단계별 토큰 예산 추적 |
| `agent/checkpoint/CheckpointStore.kt` | 실행 중간 상태 체크포인트 |
| `tool/idempotency/ToolIdempotencyGuard.kt` | 도구 중복 실행 방지 (멱등성) |
| `tool/filter/ContextAwareToolFilter.kt` | 컨텍스트 기반 동적 도구 필터링 |
| `a2a/AgentCard.kt` | A2A 에이전트 능력 광고 (`/.well-known/agent-card.json`) |
| `agent/budget/CostCalculator.kt` | 모델별 비용 계산 |
| `agent/metrics/SlaMetrics.kt` | SLA/SLO 메트릭 |
| `cache/CacheMetricsRecorder.kt` | 캐시 히트율 메트릭 |
| `mcp/McpHealthPinger.kt` | MCP 서버 헬스체크 |
| `mcp/McpToolAvailabilityChecker.kt` | 도구 가용성 사전검사 |

---

## Critical Gotchas

**ReAct 루프나 코루틴 경계 수정 전 반드시 숙지:**

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
12. **에러 코드 사용**: 실패 시 적절한 `AgentErrorCode` 사용 — `OUTPUT_GUARD_REJECTED`(Output Guard 차단), `OUTPUT_TOO_SHORT`(품질 미달) 등. `ErrorResponse` DTO에 포함

---

## 설계 원칙

1. **도구는 API다** — LLM이 호출한다는 전제로 설계. 설명에 목적·형식·경계·차이 명시
2. **루프 탈출 조건 필수** — 85% 정확도 × 10단계 = ~20% 성공률. `maxToolCalls` + `withTimeout`
3. **Guard/Hook 분리** — 인증·인가·검증 → Guard. 로깅·메트릭·메모리 → Hook
4. **메시지 쌍 무결성** — AssistantMessage + ToolResponseMessage는 항상 쌍. 트리밍도 쌍 단위
5. **환경 근거** — 매 ReAct 단계마다 도구 결과를 컨텍스트에 포함. 추론만 연쇄 → 환각 누적
6. **컨텍스트 관리** — `ConversationManager` + `TokenEstimator`. RAG는 rerank 후 상위 N개만
7. **도구 출력 불신** — 외부 API/MCP 응답은 간접 인젝션 경로. `ToolOutputSanitizer` + Output Guard
8. **동시성 존중** — `CancellationException` 먼저 catch & rethrow. 위반 시 코루틴 취소 파괴
9. **명시적 전파** — Tool 실패 → `"Error: ..."` 반환. Guard 실패 → 예외 throw
10. **관측 가능성 필수** — `AgentMetrics` + `ArcReactorTracer`로 매 단계 추적

---

## Anti-Patterns

| 패턴 | 위험 | 대안 |
|------|------|------|
| **무한 루프** — 종료 조건 없음 | API 비용 폭증 | `maxToolCalls` + `withTimeout` |
| **프롬프트 의존 보안** | LLM은 확률적 | Guard fail-close + `ToolApprovalPolicy` |
| **전지적 컨텍스트** — 모든 것 주입 | Lost in the Middle | `TokenEstimator` + rerank + 트리밍 |
| **조용한 실패** — 빈 문자열 반환 | 잘못된 추론 지속 | `"Error: {원인}"` + 메트릭 |
| **환각 도구** — 미존재 도구 호출 | 루프 낭비 | `toolsUsed` 어댑터 확인 |
| **폴링 세금** — 같은 API 반복 | Rate limit | 이벤트 기반 + 지수 백오프 |

---

## 확장 포인트

| Component | 규칙 | 실패 정책 |
|-----------|------|----------|
| **ToolCallback** | `"Error: ..."` 문자열 반환, throw 금지 | LLM이 대안 탐색 |
| **GuardStage** | 내장 순서 1–5, 커스텀 10+ | fail-close (차단) |
| **Hook** | try-catch 필수, `CancellationException` rethrow | fail-open (계속) |
| **Bean** | `@ConditionalOnMissingBean` 필수, `ObjectProvider<T>` | JDBC는 `@Primary` |

### New Feature Checklist

1. 인터페이스 정의 → 2. 기본 구현체 → 3. AutoConfiguration 등록 → 4. 테스트 → 5. 컴파일 0 warnings → 6. 전체 테스트 통과

---

## 코드 규칙

- 모든 주요 인터페이스는 `suspend fun` (예외: `ArcToolCallbackAdapter`)
- 메서드 ≤20줄, 줄 ≤120자. 한글 KDoc/주석 (영문 금지)
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단
- 컨트롤러: `@Tag` + `@Operation(summary = "...")`
- Admin 인증: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` 전용
- 403 응답: `ErrorResponse` 본문 필수

## 테스트 규칙

- `AgentTestFixture`: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- `AgentResultAssertions`: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- 모든 assertion에 실패 메시지 필수
- suspend mock: `coEvery`/`coVerify`. `runTest` > `runBlocking`

### 강화 테스트 (Hardening Tests)

일반 단위 테스트는 "구현이 맞는지", 강화 테스트는 **"악의적 입력에도 버티는지"** 확인한다.

**아래 영역을 수정하면 반드시 강화 테스트를 함께 추가/갱신할 것:**
- Guard 파이프라인 (`InjectionPatterns.kt`, `DefaultGuardStages.kt`) 수정 시
- Output Guard (PII 마스킹, 카나리 토큰) 수정 시
- Tool Output Sanitizer 수정 시
- ReAct 루프 경계 (maxToolCalls, 타임아웃, 컨텍스트 트리밍) 수정 시
- 새 도구(ToolCallback) 추가 시 → 도구 출력 인젝션 테스트 추가
- 일반 서비스/컨트롤러/데이터 로직은 강화 테스트 불필요 (단위 테스트만)

| 카테고리 | 검증 대상 | 위치 |
|----------|----------|------|
| **프롬프트 인젝션** | 직접 인젝션, 역할 탈취, 유니코드 난독화, 컨텍스트 전환 | `hardening/PromptInjectionHardeningTest.kt` |
| **입력 경계값** | 빈 입력, 초대형 입력, 특수문자 (널 바이트, 이모지) | `hardening/ReActLoopHardeningTest.kt` |
| **도구 출력 정제** | 간접 인젝션 via 도구 응답, 출력 크기 제한 | `hardening/ToolOutputSanitizationHardeningTest.kt` |
| **출력 가드** | PII 유출 방지, 카나리 토큰 누출, false positive 방지 | `hardening/OutputGuardHardeningTest.kt` |
| **적대적 강화** | LLM 공격자 vs Guard 방어자 제로섬 게임 (ARLAS 논문 기반) | `hardening/AdversarialRedTeamTest.kt` |

```bash
./gradlew :arc-core:test --tests "com.arc.reactor.hardening.*"  # 정적 강화 테스트
GEMINI_API_KEY=... ./gradlew :arc-core:test --tests "*.AdversarialRedTeamTest" -PincludeIntegration  # LLM 동적 강화
```

**강화 테스트 작성 원칙:**
- `@Tag("hardening")` 태그 필수 (LLM 필요 시 `@Tag("integration")` 추가)
- 적대적 입력(공격 벡터)과 안전한 입력(false positive 방지)을 반드시 쌍으로 테스트
- Guard 인젝션 패턴 추가 시 `InjectionPatterns.kt`에 정규식 추가 + 강화 테스트 동시 추가
- **적대적 강화 루프**: 공격 성공(Guard 우회) → `InjectionPatterns.kt` 보강 → 테스트 재실행 → 우회율 감소 확인

## 기본 설정

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `concurrency.max-concurrent-requests` | 20 | `llm.max-output-tokens` | 4096 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |
| `boundaries.input-max-chars` | 10000 | `approval.enabled` | false |
| `memory.summary.enabled` | false | `scheduler.max-executions-per-job` | 100 |

| `model-routing.enabled` | false | `budget.max-tokens-per-request` | 0 (무제한) |
| `tool-idempotency.enabled` | false | `tool-idempotency.ttl-seconds` | 60 |
| `checkpoint.enabled` | false | `tool-filter.enabled` | false |
| `a2a.enabled` | false | `mcp.health.enabled` | false |
| `mcp.health.ping-interval-seconds` | 60 | `slack.response-url-max-retries` | 3 |
| `slack.response-url-initial-delay-ms` | 500 | `slack.user-rate-limit-enabled` | false |
| `slack.user-rate-limit-max-per-minute` | 10 | | |

**모든 기능은 기본 비활성(opt-in). API 키 하나로 안전하게 실행 가능.**

---

## Claude Code Workflow

- 항상 feature 브랜치에서 작업, main 직접 수정 금지
- 작업 전: `git status && git branch`
- 커밋 전: `./gradlew compileKotlin compileTestKotlin` — 0 warnings
- PR 전: `./gradlew test` — 전체 통과
- 커밋: `git diff` 확인 후 의미 단위 분리

## PR Policy

- CI 머지 게이트: `build`, `integration`, `docker`
- LLM 호출 추가: PR에 비용 영향 메모
- Major 의존성: 마이그레이션 노트 + 롤백 플랜
- Spring Boot major: 메인테이너 승인 필수

## MCP Registration

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

## Docs

| Path | Content |
|------|---------|
| `.claude/rules/kotlin-spring.md` | Kotlin/Spring 코드 컨벤션 |
| `docs/en/architecture/` | 상세 아키텍처 (EN) |
| `docs/ko/architecture/` | 상세 아키텍처 (KO) |
| `docs/en/reference/` | 도구·RAG 레퍼런스 |
| `docs/en/engineering/` | 테스트·성능 가이드 |

## Reference Policy

외부 논문/기법 기반 기능 구현 시 `docs/en/reference/`에 문서화 (제목, 저자, 연도, 링크, 적용 위치).
