# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin 2.3.20 / Spring Boot 3.5.12 / JDK 21).

## Build & Test

```bash
./gradlew compileKotlin compileTestKotlin                  # 컴파일 (0 warnings 필수)
./gradlew test                                             # 전체 테스트
./gradlew :arc-core:test --tests "*.ClassName"             # 모듈별 단일 파일
./gradlew test -Pdb=true                                   # PostgreSQL 프로파일
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필수)
```

| JUnit Tag | Gradle Flag | 용도 |
|-----------|-------------|------|
| `hardening` | `-PincludeHardening` | Guard/보안 강화 |
| `safety` | `-PincludeSafety` | CI safety gate |
| `matrix` | `-PincludeMatrix` | Fuzz/조합 테스트 |
| `integration` | `-PincludeIntegration` | LLM API 필요 |
| `external` | `-PincludeExternalIntegration` | Docker/외부 네트워크 |
| `benchmark` | `-PincludeBenchmark` | 성능 벤치마크 |

## Modules

```
arc-app → arc-web, arc-slack, arc-admin → arc-core  (단방향 의존)
```

**IMPORTANT: `arc-core`는 다른 모듈을 절대 import하지 않는다.**

## IMPORTANT: Critical Gotchas

위반 시 프로덕션 장애. 수정 전 반드시 숙지.

1. **CancellationException**: `catch (e: Exception)` 첫 줄에 `e.throwIfCancellation()` (`import com.arc.reactor.support.throwIfCancellation`)
2. **ReAct 무한 루프**: `maxToolCalls` 도달 → `activeTools = emptyList()` 필수
3. **.forEach 금지**: `suspend fun` 내 `.forEach {}` 대신 `for (item in list)`
4. **메시지 쌍**: `AssistantMessage(toolCalls)` + `ToolResponseMessage` 항상 쌍으로 추가/제거
5. **Context trimming**: Phase 2 가드 `>` (not `>=`). off-by-one → UserMessage 손실
6. **AssistantMessage**: 생성자 protected → `.builder().content().toolCalls().build()`
7. **API key**: `application.yml`에 빈 기본값 절대 금지
8. **Guard null userId**: `"anonymous"` 폴백. Guard skip = 보안 취약점
9. **e.message 노출 금지**: HTTP 응답에 예외 메시지 포함 금지 → 서버 로그에만
10. **toolsUsed**: 어댑터 존재 확인 후에만 추가 (LLM 환각 방지)
11. **스트리밍 별도 경로**: Streaming은 별도 클래스 체인 (`StreamingExecutionCoordinator` → `StreamingReActLoopExecutor`). 비스트리밍 수정이 스트리밍에 자동 반영되지 않음 — 양쪽 모두 수정할 것
12. **설정 프로퍼티 이름 변경 금지**: 기존 `arc.reactor.*` 프로퍼티 rename/remove 시 silent 장애. 신규 추가 후 deprecated 처리

## 코드 규칙

**IMPORTANT: 파일 수정 전 반드시 Read로 먼저 읽을 것.**

- 한글 KDoc/주석 (영문 금지). 변수/함수/클래스명만 영문. 메서드 ≤20줄, 줄 ≤120자
- `private val logger = KotlinLogging.logger {}` — 파일 최상단, 클래스 밖
- `!!` 금지 → `.orEmpty()`, `?: default`, `as?`
- `Regex()` 함수 내 생성 금지 → `companion object` 또는 top-level
- `ConcurrentHashMap` 금지 → Caffeine bounded cache
- `@RequestBody` → `@Valid` 필수. Admin 엔드포인트 → `AdminAuthSupport.isAdmin(exchange)`
- 선택 의존성(compileOnly, VectorStore, JdbcTemplate) → `ObjectProvider<T>`. 필수 → 직접 주입
- Flyway: `arc-core/src/main/resources/db/migration/V{순번}__{설명}.sql`. `IF NOT EXISTS` 필수

## Guard / Hook / Tool

```
Guard = fail-close (보안)  |  Hook = fail-open (관측)  |  보안 로직은 반드시 Guard에만
```

- Guard: 7단계 (0.Unicode → 1.RateLimit → 2.Validation → 3.Injection → 4.Classification → 5.Permission → 10+.Custom)
- Hook: 4개 지점 (BeforeAgentStart, BeforeToolCall, AfterToolCall, AfterAgentComplete)
- Output Guard: 입력 Guard와 달리 `Modified` 반환 가능 (PII 마스킹 등 콘텐츠 변환)
- **보안 설정 변경 금지**: `guard.enabled`, `injection-detection-enabled` 등을 `false`로 변경 시 PR에 보안 영향 명시

## 새 기능 골든 패스

```kotlin
// 1. 인터페이스 (arc-core/.../myfeature/MyFeature.kt)
/** 기능 설명 (한글 KDoc) */
interface MyFeature {
    suspend fun execute(input: String): String
}

// 2. 구현체 (arc-core/.../myfeature/DefaultMyFeature.kt)
private val logger = KotlinLogging.logger {}

class DefaultMyFeature(
    private val required: RequiredDep,
    private val optional: ObjectProvider<OptionalDep>  // 선택 의존성
) : MyFeature {
    override suspend fun execute(input: String): String {
        return try {
            required.process(input)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "처리 실패: input=$input" }
            "Error: ${e.javaClass.simpleName}"
        }
    }
}

// 3. Configuration (arc-core/.../autoconfigure/MyFeatureConfiguration.kt)
@Bean @ConditionalOnMissingBean
fun myFeature(dep: RequiredDep, opt: ObjectProvider<OptionalDep>): MyFeature =
    DefaultMyFeature(dep, opt)

// 4. ArcReactorAutoConfiguration.kt의 @Import에 MyFeatureConfiguration::class 추가
// 5. 테스트 작성 (runTest + coEvery + 실패 메시지 필수)
// 6. ./gradlew compileKotlin compileTestKotlin → ./gradlew test
```

**AutoConfig 라우팅**: RAG → `RagConfiguration` | Guard → `GuardConfiguration` | JDBC → `JdbcStoreConfigurations` | Hook/MCP → `HookAndMcpConfiguration` | 신규 → 새 Configuration + @Import

## 테스트

- **IMPORTANT: 모든 JUnit assertion에 trailing lambda 실패 메시지 필수** (`AgentResultAssertions` 확장은 메시지 내장)
- suspend mock: `coEvery`/`coVerify`, `runTest` > `runBlocking`
- Guard/Sanitizer/ReAct 경계 수정 → 강화 테스트. 새 ToolCallback → 출력 인젝션 테스트
- MCP write 도구(생성/수정/삭제) → 멱등성 보장 또는 `ToolIdempotencyGuard` 적용

## 커밋 & PR

접두사: `feat:` `fix:` `refactor:` `sec:` `perf:` `docs:` `chore:` `test:` `ops:`
- CI 게이트: `build`, `integration`, `docker` 전부 통과
- LLM 호출 추가/retry 증가 → PR에 비용 영향 메모
- 외부 논문/기법 → `docs/en/reference/`에 출처

## Claude Code 팀

### 검증 시퀀스

```bash
./gradlew compileKotlin compileTestKotlin && ./gradlew test    # 1. compile + test
git push origin main                                            # 2. push
git branch -a && gh pr list --state open                       # 3. main만, PR 0개
```

### 병렬 팀

1. **탐색** → 2. **계획** (동일 파일 2워커 금지) → 3. **실행** (`isolation: "worktree"`) → 4. **병합** (fetch → merge → test) → 5. **정리** (PR close → worktree 삭제 → prune → push)

## 상세 규칙

@.claude/rules/kotlin-spring.md
@.claude/rules/executor.md
@.claude/rules/testing.md
@.claude/rules/architecture.md
