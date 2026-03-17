# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot). Fork 후 도구를 연결하여 사용.

## Tech Stack

- Kotlin 2.3.10, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
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

## Module Structure

| Module | Role |
|--------|------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler, 자동 구성 |
| `arc-web` | REST 컨트롤러, SSE 스트리밍, 인증, 멀티모달, OpenAPI |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) |
| `arc-admin` | 운영 제어: 메트릭 수집, 테넌트 관리, 비용 추적, 알림, SLO |
| `arc-app` | 실행 조립 모듈 (bootRun/bootJar 진입점) |

## Default Config Quick Reference

| Key | Default | Key | Default |
|-----|---------|-----|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `concurrency.max-concurrent-requests` | 20 | `llm.max-output-tokens` | 4096 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |
| `boundaries.input-max-chars` | 10000 | `boundaries.input-min-chars` | 1 |
| `tool-result-cache.enabled` | false | `tool-result-cache.ttl-seconds` | 60 |
| `citation.enabled` | false | `rag.compression.enabled` | false |
| `rag.adaptive-routing.enabled` | true | `scheduler.max-executions-per-job` | 100 |
| `memory.user.inject-into-prompt` | false | `memory.summary.enabled` | false |
| `approval.enabled` | false | `tool-policy.enabled` | false |
| `multimodal.enabled` | true | `prompt-caching.enabled` | false |

## Architecture

Request flow: **Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response**

### 핵심 파일

| File | Role |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | 핵심 ReAct 루프 (~626 lines). 수정 시 주의 |
| `agent/impl/AgentExecutionCoordinator.kt` | 실행 분기/캐시/폴백 조율 |
| `agent/impl/ExecutionResultFinalizer.kt` | 최종 후처리 (OutputGuard, 경계 검사, 필터, 히스토리 저장) |
| `agent/impl/ToolCallOrchestrator.kt` | 도구 병렬 실행, 타임아웃, 승인, sanitize |
| `agent/impl/ManualReActLoopExecutor.kt` | Non-streaming ReAct 루프 구현 |
| `agent/impl/StreamingExecutionCoordinator.kt` | SSE 스트리밍 실행 코디네이터 |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | 빈 자동 구성 |
| `agent/config/AgentProperties.kt` | 전체 설정 (`arc.reactor.*`) |
| `agent/config/AgentPolicyAndFeatureProperties.kt` | 모든 기능 토글 기본값 (~670 lines) |
| `memory/ConversationManager.kt` | 대화 히스토리 관리 + 요약 |
| `agent/multi/SupervisorOrchestrator.kt` | 멀티 에이전트 오케스트레이션 |
| `test/../AgentTestFixture.kt` | 에이전트 테스트 공유 mock 셋업 |

**Guard = fail-close** (거부 = 요청 차단). **Hook = fail-open** (로그 후 계속, `failOnError=true`면 fail-close). 보안 로직은 반드시 Guard에만.

## Design Philosophy

**Clean Architecture + 선택적 DDD**. AI Agent 프레임워크에 풀 DDD는 비효율적:
- ReAct 루프/Tool/Hook → 단순 인터페이스 유지 (도메인 객체 아님)
- Guard/Approval/Scheduler → 비즈니스 규칙이 있어 DDD 패턴 적합
- 가독성 + 성능 우선: 복잡도는 객체지향으로 분리, 메서드 ≤20줄

## Critical Gotchas

이 항목들은 미묘한 버그를 유발함 — ReAct 루프나 코루틴 경계 수정 전 반드시 숙지:

- **CancellationException**: 모든 `suspend fun`에서 generic `Exception` catch 전에 먼저 catch & rethrow. 위반 시 구조적 동시성 파괴
- **ReAct maxToolCalls**: 한도 도달 시 `activeTools = emptyList()` 설정 필수. 로깅만 하면 무한 루프
- **.forEach in coroutines**: `for` 루프 사용. `.forEach {}`는 non-suspend 람다
- **Message pair integrity**: `AssistantMessage(toolCalls)` + `ToolResponseMessage`는 항상 쌍으로 추가/제거
- **Context trimming**: 마지막 UserMessage 보호. Phase 2 가드 조건은 `>` (not `>=`)
- **Guard null userId**: `"anonymous"` 폴백 필수. Guard 건너뛰기 = 보안 취약점
- **Output guard errors**: `OUTPUT_GUARD_REJECTED`, `OUTPUT_TOO_SHORT` 코드를 매핑/테스트/문서에서 정확히 보존
- **toolsUsed**: 어댑터 존재 확인 후에만 append (LLM 환각 도구명 방지)
- **AssistantMessage**: 생성자가 protected → `AssistantMessage.builder().content().toolCalls().build()`
- **Spring AI providers**: `application.yml`에 빈 기본값으로 선언 금지. 환경변수만: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
- **Spring AI mock chain**: `.options(any<ChatOptions>())` 명시적 mock 필수

## Code Conventions

@.claude/rules/kotlin-spring.md 참조. 핵심 비자명 규칙:

- `compileOnly` = 선택적 의존성. example 패키지: `@Component` 항상 주석 처리
- 인터페이스는 패키지 루트, 구현체는 `impl/`, 데이터 클래스는 `model/`
- 모든 403 응답에 `ErrorResponse` 본문 필수 — 빈 `build()` 금지
- Admin 인증: 반드시 `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` — 중복 구현 금지
- 한글 KDoc/주석 사용 (영문 금지)

## Testing

```
AgentTestFixture: mockCallResponse(), mockToolCallResponse(), mockFinalResponse(), TrackingTool
AgentResultAssertions: assertSuccess(), assertFailure(), assertErrorCode(), assertErrorContains()
```

- 모든 assertion에 실패 메시지 필수 — bare `assertTrue(x)` 금지
- suspend mock은 `coEvery`/`coVerify`. `runTest` > `runBlocking`
- 스트리밍 테스트에서 `requestSpec.options(any<ChatOptions>())` 명시적 mock

## New Feature Checklist

1. 인터페이스 정의 (확장 가능하게)
2. 기본 구현체 작성
3. `ArcReactorAutoConfiguration`에 `@ConditionalOnMissingBean`으로 등록
4. `AgentTestFixture`로 테스트 작성
5. `./gradlew compileKotlin compileTestKotlin` — 0 warnings 확인
6. `./gradlew test` — 전체 테스트 통과 확인

**확장 포인트 규칙:**
- **ToolCallback**: `"Error: ..."` 문자열 반환 — throw 금지
- **GuardStage**: 내장 순서 1–5. 커스텀은 10+부터
- **Hook**: try-catch 필수. `CancellationException`은 항상 rethrow
- **Bean**: `@ConditionalOnMissingBean` 필수. 선택적 의존성은 `ObjectProvider<T>`. JDBC 저장소는 `@Primary`

## MCP Registration

REST API로만 등록 — `application.yml`에 하드코딩 금지:

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

HTTP transport는 MCP SDK 0.17.2에서 미지원. 출력 50,000자 제한.

## PR and Dependency Policy

- CI 머지 게이트 필수: `build`, `integration`, `docker`
- LLM 호출 추가 기능은 PR 설명에 비용 영향 메모 필수
- Patch/minor 의존성 업그레이드: CI 통과 후 머지. Major: 마이그레이션 노트 + 롤백 플랜 필수
- Spring Boot major 업그레이드는 메인테이너 명시적 승인 없이 차단

## Reference Policy

- 외부 논문/기법 기반 기능 구현 시 `docs/en/reference/`에 문서화
- 포함: 논문 제목, 저자, 연도, 링크, 프로젝트 내 적용 위치

## Docs

- `docs/en/architecture/` — 상세 아키텍처
- `docs/ko/architecture/` — 한국어 아키텍처
- `docs/en/reference/tools.md` — 도구 레퍼런스
- `docs/en/reference/rag-papers.md` — RAG 학술 레퍼런스
- `docs/en/engineering/testing-and-performance.md` — 테스트 패턴 및 예시
