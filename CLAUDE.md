# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot). Fork 후 도구를 연결하여 사용.

## Tech Stack

- Kotlin 2.3.20, Spring Boot 3.5.12, Spring AI 1.1.3, JDK 21, Gradle 8.12
- Test: JUnit 5 + MockK 1.14.9 + Kotest assertions 6.1.7
- DB: H2 (test), PostgreSQL (prod, optional)

## Commands

```bash
./gradlew compileKotlin compileTestKotlin                  # 컴파일 (0 warnings 필수)
./gradlew test                                             # 전체 테스트
./gradlew test --tests "*.ClassName"                       # 단일 파일
./gradlew test -Pdb=true                                   # PostgreSQL 프로파일
./gradlew test -PincludeHardening -PincludeSafety          # 보안 테스트
./gradlew test -PincludeMatrix                             # Matrix/Fuzz 테스트
./gradlew test -PincludeIntegration                        # 통합 테스트 (API 키 필요)
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필수)
```

## 모듈 구조

| Module | Role | 테스트 명령 |
|--------|------|------------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler | `./gradlew :arc-core:test` |
| `arc-web` | REST/SSE 컨트롤러, 인증, 멀티모달, OpenAPI | `./gradlew :arc-web:test` |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) | `./gradlew :arc-slack:test` |
| `arc-admin` | 운영 제어: 메트릭, 테넌트, 알림, 비용/SLO | `./gradlew :arc-admin:test` |
| `arc-app` | 실행 조립 (bootRun/bootJar 진입점) | — |

## Critical Gotchas

**IMPORTANT: ReAct 루프나 코루틴 경계 수정 전 반드시 숙지.**

1. **CancellationException**: `suspend fun`에서 `catch (e: Exception)` 전에 반드시 `e.throwIfCancellation()` 호출 → 위반 시 코루틴 취소 파괴
2. **ReAct 무한 루프**: `maxToolCalls` 도달 시 `activeTools = emptyList()` 필수 → 로깅만 하면 무한 루프
3. **코루틴 내 .forEach**: `for (item in list)` 사용 → `.forEach {}` 람다는 suspend 불가
4. **메시지 쌍 무결성**: `AssistantMessage(toolCalls)` + `ToolResponseMessage`는 항상 쌍으로 추가/제거
5. **Context trimming**: Phase 2 가드 조건은 `>` (not `>=`) → off-by-one 시 마지막 UserMessage 손실
6. **AssistantMessage 생성자**: protected → `AssistantMessage.builder().content().toolCalls().build()`
7. **API key 환경변수**: `application.yml`에 빈 기본값 설정 절대 금지. `.env`는 `.gitignore` 필수
8. **MCP 서버**: REST API로만 등록. `application.yml` 하드코딩 금지
9. **Guard null userId**: 항상 `"anonymous"` 폴백. Guard 건너뛰기 = 보안 취약점
10. **Spring AI mock**: 스트리밍 테스트에서 `.options(any<ChatOptions>())` 명시적 mock 필수
11. **toolsUsed**: 어댑터 존재 확인 후에만 도구명 추가 → LLM 환각 도구명 방지
12. **에러 코드 사용**: 실패 시 `AgentErrorCode` 포함. `ErrorResponse` DTO로 반환

## 코드 작성 규칙

**IMPORTANT: 파일 수정 전 반드시 해당 파일을 Read로 먼저 읽을 것.**

- 한글 KDoc/주석 (영문 금지). 메서드 ≤20줄, 줄 ≤120자
- `private val logger = KotlinLogging.logger {}` — 파일 최상단, 클래스 밖
- `suspend fun` 내 `catch (e: Exception)` → 첫 줄에 `e.throwIfCancellation()`
- Regex는 `companion object` 또는 top-level `val`에 정의. 함수 내 `Regex()` 금지
- `!!` 금지 → `.orEmpty()`, `?: default`, `as?` 사용
- `ConcurrentHashMap` → Caffeine bounded cache (`maximumSize` + `expireAfter*`)
- 컨트롤러: `@Tag` + `@Operation(summary = "...")`. 모든 `@RequestBody`에 `@Valid`
- Admin 인증: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` 전용
- `e.message`를 HTTP 응답에 절대 포함 금지 → 서버 로그에만 기록
- 새 빈: `@ConditionalOnMissingBean` 필수. 선택 의존성: `ObjectProvider<T>`

## 테스트 규칙

- 모든 assertion에 실패 메시지 필수: `assertTrue(x) { "Expected Y" }`
- suspend mock: `coEvery`/`coVerify`. `runTest` > `runBlocking`
- Guard/Output Guard/Sanitizer/ReAct 경계 수정 시 → 강화 테스트 동시 추가
- 새 ToolCallback 추가 시 → 도구 출력 인젝션 테스트 추가

## 새 기능 추가 레시피

**Guard 추가**: GuardStage 인터페이스 구현 → order 지정 (내장 1-5, 커스텀 10+) → AutoConfiguration 등록 → 강화 테스트
**Hook 추가**: Hook 인터페이스 구현 → try-catch 필수 + CancellationException rethrow → fail-open 보장
**Controller 추가**: `@Tag` + `@Operation` + `@Valid` + `isAdmin()` 체크 + `ErrorResponse` 일관성
**JDBC Store 추가**: `@ConditionalOnBean(JdbcTemplate::class)` → Flyway 마이그레이션 → `ON CONFLICT`/`IF NOT EXISTS`
**메트릭 추가**: `MeterRegistry?` nullable 주입 → `meterRegistry?.counter(...)?.increment()` safe call

## 커밋 컨벤션

한글 본문 + 타입 접두사:
- `feat:` 새 기능 | `fix:` 버그 수정 | `refactor:` 리팩토링
- `sec:` 보안 | `perf:` 성능 | `docs:` 문서 | `chore:` 빌드/버전
- `test:` 테스트 추가 | `ops:` 운영 가시성

## PR Policy

- CI 머지 게이트: `build`, `integration`, `docker` 전부 통과
- LLM 호출 추가: PR에 비용 영향 메모
- Major 의존성: 마이그레이션 노트 + 롤백 플랜
- Spring Boot major: 메인테이너 승인 필수
- 외부 논문/기법: `docs/en/reference/`에 출처 문서화

## Claude Code 팀 워크플로우

### 검증 시퀀스 (모든 작업 후 필수)

```bash
./gradlew compileKotlin compileTestKotlin    # 1. 컴파일 0 warnings
./gradlew test                                # 2. 전체 테스트 통과
git push origin main                          # 3. push
git branch -a                                 # 4. main만 남았는지 확인
gh pr list --state open                       # 5. open PR 0개 확인
```

### 병렬 팀 운영

대규모 작업 시 5단계 프로세스:

1. **탐색**: Explore 에이전트 2-3개로 모듈별 병렬 스캔
2. **계획**: 발견 이슈를 독립 작업 단위(5-7개)로 분해. 동일 파일 2개 워커 배정 금지
3. **실행**: 워커를 `isolation: "worktree"` + `run_in_background: true`로 병렬 실행
4. **병합**: `git fetch origin <branch>` → `git merge --no-edit` → compile + test
5. **정리**: PR close(`--delete-branch`) → `rm -rf .claude/worktrees/agent-*` → `git worktree prune` → local branch 삭제 → `git fetch --prune` → push

### 릴리스

- 의미 있는 변경 누적 시: `build.gradle.kts` 버전 범프 → `git tag -a vX.Y.Z` → push
- `docs/en/releases/vX.Y.Z.md` 릴리스 노트 작성
- CLAUDE.md도 동기화 (라인수, 삭제/추가된 파일 반영)

## 상세 규칙

@.claude/rules/kotlin-spring.md
@.claude/rules/executor.md
@.claude/rules/testing.md
@.claude/rules/architecture.md
