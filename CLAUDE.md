# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot 3.5.12 / JDK 21).

## Build & Test

```bash
./gradlew compileKotlin compileTestKotlin                  # 컴파일 (0 warnings 필수)
./gradlew test                                             # 전체 테스트
./gradlew :arc-core:test --tests "*.ClassName"             # 모듈별 단일 파일
./gradlew test -Pdb=true                                   # PostgreSQL 프로파일
./gradlew test -PincludeHardening -PincludeSafety          # 보안 강화 테스트
./gradlew test -PincludeMatrix                             # Matrix/Fuzz 테스트
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필수)
```

## Modules

| Module | 역할 |
|--------|------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler |
| `arc-web` | REST/SSE, JWT 인증, 멀티모달, OpenAPI |
| `arc-slack` | Slack Events API + Socket Mode + Slash Command |
| `arc-admin` | 메트릭, 테넌트, 알림, 비용/SLO |
| `arc-app` | bootRun/bootJar 진입점 |

## IMPORTANT: Critical Gotchas

아래 규칙 위반은 프로덕션 장애를 유발한다. 수정 전 반드시 숙지.

1. **CancellationException**: `catch (e: Exception)` 첫 줄에 `e.throwIfCancellation()` → 미준수 시 코루틴 취소 파괴
2. **ReAct 무한 루프**: `maxToolCalls` 도달 → `activeTools = emptyList()` 필수
3. **.forEach 금지**: `suspend fun` 내에서 `.forEach {}` 대신 `for (item in list)` 사용
4. **메시지 쌍**: `AssistantMessage(toolCalls)` + `ToolResponseMessage` 항상 쌍으로 추가/제거
5. **Context trimming**: Phase 2 가드 `>` 사용 (not `>=`) → off-by-one 시 UserMessage 손실
6. **AssistantMessage**: 생성자 protected → `AssistantMessage.builder().content().toolCalls().build()`
7. **API key**: `application.yml`에 빈 기본값 절대 금지. `.env`는 `.gitignore` 필수
8. **Guard null userId**: `"anonymous"` 폴백 필수. Guard 건너뛰기 = 보안 취약점
9. **e.message 노출 금지**: HTTP 응답에 예외 메시지 포함 금지 → 서버 로그에만 기록
10. **toolsUsed**: 어댑터 존재 확인 후에만 추가 (LLM 환각 방지)

## 코드 규칙

**IMPORTANT: 파일 수정 전 반드시 Read로 먼저 읽을 것.**

```
한글 KDoc/주석 (영문 금지) | 메서드 ≤20줄 | 줄 ≤120자
```

- `private val logger = KotlinLogging.logger {}` — 파일 최상단, 클래스 밖
- `!!` 금지 → `.orEmpty()`, `?: default`, `as?` 사용
- `Regex()` 함수 내 생성 금지 → `companion object` 또는 top-level
- `ConcurrentHashMap` 금지 → Caffeine bounded cache (`maximumSize` + TTL)
- `@RequestBody` → 반드시 `@Valid` 동반
- 새 빈 → `@ConditionalOnMissingBean` 필수, 선택 의존성 → `ObjectProvider<T>`

## Guard / Hook / Tool 규율

```
Guard = fail-close (보안). Hook = fail-open (관측). 보안 로직은 반드시 Guard에만.
```

| 확장 포인트 | 규칙 | 실패 정책 |
|------------|------|----------|
| GuardStage | 내장 순서 1-5, 커스텀 10+ | 차단 (reject) |
| Hook | try-catch + `e.throwIfCancellation()` | 계속 (로그) |
| ToolCallback | `"Error: ..."` 문자열 반환, throw 금지 | LLM 대안 탐색 |
| Bean | `@ConditionalOnMissingBean`, JDBC는 `@Primary` | 사용자 교체 가능 |

## 새 기능 레시피

| 대상 | 절차 |
|------|------|
| **Guard** | GuardStage 구현 → order 10+ → AutoConfig 등록 → 강화 테스트 |
| **Hook** | Hook 구현 → try-catch 필수 → fail-open 보장 → 테스트 |
| **Controller** | `@Tag` + `@Operation` + `@Valid` + `isAdmin()` + `ErrorResponse` |
| **JDBC Store** | `@ConditionalOnBean(JdbcTemplate)` → Flyway (`IF NOT EXISTS`) → 테스트 |
| **메트릭** | `MeterRegistry?` nullable 주입 → `meterRegistry?.counter(...)?.increment()` |

## 테스트

- **IMPORTANT: 모든 assertion에 실패 메시지 필수**
- suspend mock: `coEvery`/`coVerify`, `runTest` > `runBlocking`
- Guard/Output Guard/Sanitizer/ReAct 경계 수정 → 강화 테스트 동시 추가
- 새 ToolCallback → 도구 출력 인젝션 테스트 추가

## 커밋 & PR

커밋 접두사: `feat:` `fix:` `refactor:` `sec:` `perf:` `docs:` `chore:` `test:` `ops:`

- CI 머지 게이트: `build`, `integration`, `docker` 전부 통과
- LLM 호출 추가 시 PR에 비용 영향 메모
- 외부 논문/기법 → `docs/en/reference/`에 출처 문서화

## Claude Code 팀 운영

### 검증 시퀀스 (작업 완료 후 필수)

```bash
./gradlew compileKotlin compileTestKotlin    # 1. 0 warnings
./gradlew test                                # 2. 전체 통과
git push origin main                          # 3. push
git branch -a                                 # 4. main만 확인
gh pr list --state open                       # 5. open PR 0개
```

### 병렬 팀 (대규모 작업)

1. **탐색**: Explore 에이전트 2-3개 → 모듈별 병렬 스캔
2. **계획**: 독립 작업 단위 5-7개 분해 (동일 파일 2워커 배정 금지)
3. **실행**: `isolation: "worktree"` + `run_in_background: true`
4. **병합**: `git fetch` → `merge --no-edit` → compile + test
5. **정리**: PR close(`--delete-branch`) → worktree 삭제 → `git worktree prune` → 브랜치 삭제 → `git fetch --prune` → push

### 릴리스

`build.gradle.kts` 버전 범프 → `git tag -a vX.Y.Z` → push → `docs/en/releases/vX.Y.Z.md`

## 상세 규칙 (경로별 자동 로드)

@.claude/rules/kotlin-spring.md
@.claude/rules/executor.md
@.claude/rules/testing.md
@.claude/rules/architecture.md
