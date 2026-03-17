# Arc Reactor — Agent Instructions

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot).
ReAct 루프, Guard 파이프라인, Hook 라이프사이클, MCP 통합, 멀티 에이전트 오케스트레이션을 Spring Boot 자동 구성으로 제공.

## Environment Setup

실행에 필요한 환경변수:
```bash
GEMINI_API_KEY=...                    # bootRun 필수
SPRING_AI_OPENAI_API_KEY=...          # 선택 — OpenAI 백엔드
SPRING_AI_ANTHROPIC_API_KEY=...       # 선택 — Anthropic 백엔드
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor  # 선택 — 운영 DB
SPRING_DATASOURCE_USERNAME=arc
SPRING_DATASOURCE_PASSWORD=arc
SPRING_FLYWAY_ENABLED=true            # 선택 — DB 마이그레이션
```

`application.yml`에 빈 문자열 기본값으로 provider API 키를 절대 설정하지 말 것. 환경변수만 사용.

## Validate Commands

```bash
./gradlew compileKotlin compileTestKotlin   # 0 warnings 필수
./gradlew test                              # PR 전 반드시 통과
./gradlew test --tests "*.YourTestClass"    # 단일 테스트 실행
./gradlew test -Pdb=true                    # PostgreSQL/PGVector/Flyway 테스트 포함
./gradlew test -PincludeIntegration         # @Tag("integration") 테스트 포함
```

CI 머지 게이트: `build`, `integration`, `docker` 모두 통과 필수.

## Module Structure

| Module | Purpose |
|--------|---------|
| `arc-core` | 핵심 프레임워크: 에이전트 실행기, Guard, Hook, Tool, RAG, Memory, Scheduler, Persona |
| `arc-web` | REST 컨트롤러, WebFlux, 인증, 멀티모달 업로드, OpenAPI |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) |
| `arc-admin` | 운영 제어: 메트릭 파이프라인, 테넌트 관리, 알림, 비용/SLO 추적 |
| `arc-app` | 실행 조립 모듈 (bootRun/bootJar, 런타임 의존성 결합) |

모든 기능은 `@ConditionalOnMissingBean`으로 opt-in — 자체 빈으로 오버라이드 가능.

## Architecture at a Glance

요청 흐름: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

### 핵심 파일

- `SpringAiAgentExecutor.kt` — 핵심 ReAct 루프 (~626 lines). 수정 시 주의
- `AgentExecutionCoordinator.kt` — 실행 분기, 캐시, 폴백 조율
- `ExecutionResultFinalizer.kt` — 최종 후처리 (OutputGuard, 경계, 필터, 히스토리)
- `ToolCallOrchestrator.kt` — 도구 병렬 실행, 타임아웃, 승인
- `ArcReactorAutoConfiguration.kt` — 빈 자동 구성. `@ConditionalOnMissingBean`으로 오버라이드
- `AgentPolicyAndFeatureProperties.kt` — 전체 `arc.reactor.*` 설정 및 기능 토글 기본값

**Guard = fail-close** (차단). **Hook = fail-open** (로그 후 계속). 보안 로직은 Guard에만.

## Critical Gotchas (버그 유발 주의사항)

1. **CancellationException**: 모든 `suspend fun`에서 generic `catch (e: Exception)` 전에 먼저 catch & rethrow. 위반 시 구조적 동시성 자동 파괴
2. **ReAct 무한 루프**: `maxToolCalls` 도달 시 `activeTools = emptyList()` 필수. 로깅만 하면 무한 루프
3. **코루틴 내 .forEach**: `for (item in list)` 사용. `list.forEach {}` 람다는 suspend 불가
4. **메시지 쌍 무결성**: `AssistantMessage(toolCalls)` + `ToolResponseMessage`는 항상 쌍으로 추가/제거
5. **Context trimming**: Phase 2 가드 조건은 `>` (not `>=`). off-by-one 시 마지막 UserMessage 손실
6. **AssistantMessage 생성자**: protected → `AssistantMessage.builder().content().toolCalls().build()`
7. **API key 환경변수**: `application.yml`에 빈 기본값 설정 금지
8. **MCP 서버**: REST API로만 등록 (`POST /api/mcp/servers`). `application.yml` 하드코딩 금지
9. **Guard null userId**: 항상 `"anonymous"` 폴백. Guard 건너뛰기 = 보안 취약점
10. **Spring AI mock chain**: 스트리밍 테스트에서 `.options(any<ChatOptions>())` 명시적 mock 필수
11. **toolsUsed 리스트**: 어댑터 존재 확인 후에만 도구명 추가

## Key Defaults

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `concurrency.max-concurrent-requests` | 20 | `llm.max-output-tokens` | 4096 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |
| `boundaries.input-max-chars` | 10000 | `approval.enabled` | false |
| `memory.summary.enabled` | false | `scheduler.max-executions-per-job` | 100 |

## Code Rules

- 모든 주요 인터페이스는 코루틴 기반 (`suspend fun`). `ArcToolCallbackAdapter`만 예외 (Spring AI 제약)
- 메서드 ≤20줄, 줄 ≤120자. 한글 KDoc/주석 사용
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단, 클래스 바깥
- 모든 컨트롤러에 `@Tag`. 모든 엔드포인트에 `@Operation(summary = "...")`
- Admin 인증: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` — 중복 구현 금지
- 모든 403 응답에 `ErrorResponse` 본문 필수 — 빈 `build()` 금지

## Extension Points

| Component | Rule |
|-----------|------|
| ToolCallback | `"Error: ..."` 문자열 반환 — throw 금지 |
| GuardStage | 내장 순서 1–5. 커스텀은 10+부터 |
| Hook | try-catch 필수. `CancellationException`은 항상 rethrow |
| Bean | `@ConditionalOnMissingBean` 필수. 선택적 의존성은 `ObjectProvider<T>` |

## Testing Rules

- 새 기능에 테스트 필수. 버그 수정에 회귀 테스트 필수
- `AgentTestFixture` 사용: `mockCallResponse()`, `mockToolCallResponse()`, `mockFinalResponse()`, `TrackingTool`
- `AgentResultAssertions` 사용: `assertSuccess()`, `assertFailure()`, `assertErrorCode()`, `assertErrorContains()`
- 모든 assertion에 실패 메시지 필수 — bare `assertTrue(x)` 금지
- suspend mock은 `coEvery`/`coVerify`. `runTest` > `runBlocking`
- 타이밍 민감 테스트: `CountDownLatch` + `Thread.sleep()` 사용 (가상 시계가 아닌 실제 시간)

## PR Rules

- LLM 호출 추가 PR에 비용 영향 메모 필수
- Patch/minor 의존성: CI 통과 후 머지. Major: 마이그레이션 노트 + 롤백 플랜 필수
- Spring Boot major 업그레이드는 메인테이너 명시적 승인 없이 차단
