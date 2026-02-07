# Arc Reactor

Spring AI 기반 AI Agent 프레임워크. Fork해서 도구를 붙여 사용하는 구조.

## Tech Stack

- Kotlin 2.3.0, Spring Boot 3.5.9, Spring AI 1.1.2, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.5 + Kotest assertions 5.9.1
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew test                                             # 전체 테스트 (267개)
./gradlew test --tests "com.arc.reactor.agent.*"           # 패키지 필터
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # 단일 파일
./gradlew compileKotlin compileTestKotlin                  # 컴파일 경고 확인 (0개 유지)
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필요)
```

## Architecture

핵심 요청 흐름: Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response

- `SpringAiAgentExecutor` (~1,060줄) — 코어 executor. 수정 시 주의 필요
- `ArcReactorAutoConfiguration` — 모든 빈 자동 설정. @ConditionalOnMissingBean으로 오버라이드 가능
- `ConversationManager` — executor에서 분리된 대화 이력 관리

상세: @docs/architecture.md, @docs/tools.md, @docs/supervisor-pattern.md

## Code Style

- Kotlin 관례를 따름. 특별한 포맷터 없음 (IntelliJ 기본)
- **메서드 길이: 20줄 이하**. 초과 시 extract method 리팩터링
- **라인 너비: 120자 이하**. 초과 시 줄바꿈
- `suspend fun` 사용 — executor, tool, guard, hook 모두 코루틴 기반
- `compileOnly` = 선택적 의존 (사용자가 필요 시 `implementation`으로 전환)
- example 패키지의 클래스는 `@Component` 주석 처리 상태 (프로덕션 자동등록 방지)

## Testing Rules

- JUnit 5 assertions 사용 (`org.junit.jupiter.api.Assertions.*`). Kotest는 matchers만 사용
- `AgentTestFixture` — 공유 mock setup. 모든 agent 테스트에서 사용
- `AgentResultAssertions` — `assertSuccess()`, `assertFailure()`, `assertErrorCode()` 확장 함수
- 모든 assertion에 실패 메시지 필수 (bare `assertTrue(x)` 금지)
- `@Nested` inner class로 논리 그룹핑
- 타이밍 테스트: `AtomicInteger`로 동시성 카운팅 (System.currentTimeMillis 사용 금지)
- `assertInstanceOf` 사용 (assertTrue(x is Type) 대신) — 캐스트된 객체를 반환함

## Critical Gotchas

- **CancellationException**: 모든 `suspend fun`에서 generic `Exception` catch 전에 반드시 catch & rethrow. withTimeout 구조적 동시성 위반 방지
- **ReAct 루프 종료**: maxToolCalls 도달 시 `activeTools = emptyList()` — 로그만 남기면 무한루프
- **컨텍스트 트리밍**: 마지막 UserMessage(현재 프롬프트) 보호 필수. Phase 2 guard에서 `>` 사용 (`>=` 아님)
- **메시지 쌍 무결성**: AssistantMessage(toolCalls) + ToolResponseMessage는 반드시 함께 제거
- **Guard null userId**: "anonymous" 폴백 사용. guard를 건너뛰면 보안 취약점
- **toolsUsed 기록**: adapter 존재 확인 후에만 추가 (LLM이 hallucinate한 도구명 방지)
- **Hook/Memory 예외**: catch/finally 블록 내 hook/memory 호출은 반드시 try-catch로 감싸기
- **Regex**: 핫 패스에서 컴파일하지 말 것. top-level val로 추출

## Domain Terms

- **ReAct**: Reasoning + Acting. LLM이 생각하고 도구를 쓰는 루프
- **Guard**: 요청 사전 검증 파이프라인 (5단계)
- **Hook**: 에이전트 생명주기 확장 포인트 (4개)
- **MCP**: Model Context Protocol. 외부 서버가 도구를 제공하는 표준
- **WorkerAgentTool**: 에이전트를 ToolCallback으로 감싸는 어댑터 (Supervisor 패턴 핵심)

## Key Files

| 파일 | 역할 |
|------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | ReAct 루프, 재시도, 컨텍스트 관리 (코어) |
| `agent/multi/SupervisorOrchestrator.kt` | Supervisor 멀티에이전트 오케스트레이션 |
| `agent/multi/WorkerAgentTool.kt` | 에이전트→도구 변환 어댑터 |
| `autoconfigure/ArcReactorAutoConfiguration.kt` | Spring Boot 자동 설정 |
| `controller/ChatController.kt` | REST API 엔드포인트 |
| `agent/config/AgentProperties.kt` | 모든 설정 (arc.reactor.*) |
| `test/../AgentTestFixture.kt` | 테스트 공유 mock setup |
