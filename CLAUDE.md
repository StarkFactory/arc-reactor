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

## PR Policy

- CI 머지 게이트: `build`, `integration`, `docker` — 전부 통과 필수
- LLM 호출 추가 시: PR에 비용 영향 메모 (예: 분당 추정 비용)
- Major 의존성 업그레이드: 마이그레이션 노트 + 롤백 플랜 포함
- Spring Boot major 업그레이드: 메인테이너 승인 필수
- 외부 논문/기법 기반 구현: `docs/en/reference/`에 출처 문서화

## Claude Code 개발 워크플로우

### 기본 규율

- 항상 feature 브랜치에서 작업, main 직접 수정 금지
- 커밋 전: `./gradlew compileKotlin compileTestKotlin` — 0 warnings
- PR 전: `./gradlew test` — 전체 통과
- 작업 완료 후: worktree 정리, remote 브랜치 삭제, `git fetch --prune`

### 병렬 팀 운영 (대규모 작업)

대규모 코드 검토/수정 시 병렬 에이전트 팀을 구성한다:

1. **탐색 단계**: Explore 에이전트 2-3개로 모듈별 병렬 스캔
2. **계획 단계**: 발견 이슈를 독립 작업 단위(5-7개)로 분해
3. **실행 단계**: 워커 에이전트를 `isolation: "worktree"`로 병렬 실행
4. **검증 단계**: 각 워커가 compile + test + simplify 후 PR 생성
5. **병합 단계**: 코디네이터가 PR 병합 → main push → 브랜치/worktree 정리

**작업 단위 원칙:**
- 각 단위는 독립적 (다른 단위의 PR에 의존하지 않음)
- 각 단위는 단일 모듈 또는 단일 관심사에 집중
- 동일 파일을 2개 이상 워커가 수정하지 않도록 분배

**검증 체크리스트 (모든 작업 후):**
- `./gradlew compileKotlin compileTestKotlin` — 0 warnings
- `./gradlew test` — 전체 통과
- `git branch -a` — main만 남아야 함
- `gh pr list --state open` — 0개
- `git log --oneline origin/main..HEAD` — 0개 (unpushed 없음)

### 릴리스 워크플로우

- 의미 있는 변경 누적 시 `build.gradle.kts` 버전 범프
- `git tag -a vX.Y.Z` + `git push origin vX.Y.Z`
- `docs/en/releases/vX.Y.Z.md` 릴리스 노트 작성
- Breaking change 없으면 minor, 있으면 major 범프

## 상세 규칙

@.claude/rules/kotlin-spring.md
@.claude/rules/executor.md
@.claude/rules/testing.md
@.claude/rules/architecture.md
