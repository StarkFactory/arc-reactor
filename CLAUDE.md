# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot). Fork 후 도구를 연결하여 사용.

## Tech Stack

- Kotlin 2.3.20, Spring Boot 3.5.12, Spring AI 1.1.3, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 6.1.7
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

## 모듈 구조

| Module | Role |
|--------|------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler |
| `arc-web` | REST/SSE 컨트롤러, 인증, 멀티모달, OpenAPI |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) |
| `arc-admin` | 운영 제어: 메트릭, 테넌트, 알림, 비용/SLO |
| `arc-app` | 실행 조립 (bootRun/bootJar 진입점) |

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
12. **에러 코드 사용**: 실패 시 적절한 `AgentErrorCode` 사용. `ErrorResponse` DTO에 포함

## 코드 규칙

- 모든 주요 인터페이스는 `suspend fun` (예외: `ArcToolCallbackAdapter`)
- 메서드 ≤20줄, 줄 ≤120자. 한글 KDoc/주석 (영문 금지)
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단
- 컨트롤러: `@Tag` + `@Operation(summary = "...")`
- Admin 인증: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` 전용
- 403 응답: `ErrorResponse` 본문 필수

## 테스트 규칙

- 모든 assertion에 실패 메시지 필수
- suspend mock: `coEvery`/`coVerify`. `runTest` > `runBlocking`
- 강화 테스트 대상: Guard, Output Guard, Sanitizer, ReAct 경계, 새 ToolCallback

## Claude Code Workflow

- 항상 feature 브랜치에서 작업, main 직접 수정 금지
- 커밋 전: `./gradlew compileKotlin compileTestKotlin` — 0 warnings
- PR 전: `./gradlew test` — 전체 통과

## 상세 규칙

@.claude/rules/kotlin-spring.md
@.claude/rules/executor.md
@.claude/rules/testing.md
@.claude/rules/architecture.md
