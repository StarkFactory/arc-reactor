# 테스트 및 성능 가이드

현재 테스트 기본 범위와 실무형 속도 최적화 방법을 정리합니다.

## 테스트 태그

| 태그 | 용도 | Gradle 플래그 | 기본값 |
|------|------|--------------|--------|
| `@Tag("integration")` | 외부 의존(DB, Spring 컨텍스트) 필요 테스트 | `-PincludeIntegration` | 제외 |
| `@Tag("matrix")` | 조합/퍼즈 회귀 스위트 | `-PincludeMatrix` | 제외 |
| `@Tag("external")` | 네트워크/NPX/Docker 의존 | `-PincludeExternalIntegration` (`-PincludeIntegration` 필요) | 제외 |
| `@Tag("safety")` | 보안 검증 게이트 (CI 전용) | `-PincludeSafety` | 제외; include-only (safety 태그 테스트만 실행) |
| `@Tag("regression")` | 타겟 회귀 마커 | 없음 | 항상 포함 |

태그 제외는 루트 `build.gradle.kts`의 `tasks.withType<Test>` 블록에서 설정됩니다(모든 서브프로젝트에 적용). `safety` 태그는 `excludeTags` 대신 `includeTags`를 사용하므로, `-PincludeSafety`는 safety 태그 테스트만 실행합니다.

## 기본 테스트 범위

모든 모듈은 기본적으로 `@Tag("integration")`, `@Tag("external")`, `@Tag("matrix")` 테스트를 제외합니다.

- 기본 실행: `./gradlew test --continue`
- 통합 테스트 포함: `./gradlew test -PincludeIntegration`
- 매트릭스/퍼즈 테스트 포함: `./gradlew test -PincludeMatrix`
- 통합 API 스위트(core + web): `./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"`
- 외부 의존 통합 테스트 포함(npx/docker/network): `./gradlew test -PincludeIntegration -PincludeExternalIntegration`
- safety 게이트만 실행: `./gradlew :arc-core:test :arc-web:test -PincludeSafety`

로컬 피드백 루프를 빠르게 유지하고, 통합 경로는 명시적으로 실행할 수 있게 설계되어 있습니다.

## 빠른 로컬 검증 순서

대규모 변경 시 다음 순서를 권장합니다:

1. 모듈 단위 타깃 테스트
2. 변경 패키지 중심 테스트
3. 전체 `test --continue`

예시:

- `./gradlew :arc-core:test --tests "com.arc.reactor.mcp.*"`
- `./gradlew :arc-web:test --tests "com.arc.reactor.controller.*"`
- `./gradlew test --continue`

개발 보조 스크립트:

- 빠른 기본 로컬 스위트: `scripts/dev/test-fast.sh`
- 통합 테스트 포함: `INCLUDE_INTEGRATION=1 scripts/dev/test-fast.sh`
- 매트릭스/퍼즈 테스트 포함: `INCLUDE_MATRIX=1 scripts/dev/test-fast.sh`
- 외부 통합 테스트 포함: `INCLUDE_EXTERNAL=1 scripts/dev/test-fast.sh`
- 기존 XML 리포트 기준 느린 테스트 상위 조회: `scripts/dev/slow-tests.sh 30`
- 문서/동기화 품질 검사: `scripts/dev/check-docs.sh`

## 느린 테스트의 대표 원인

- 실패 경로 테스트에서 과도하게 긴 timeout
- 실패 검증 테스트에서도 reconnect 루프 활성화
- thread/latch 구성이 잘못되어 고정 대기 발생
- 외부 의존 시작/다운로드 지연(예: MCP `npx` 서버 부팅)
- 로컬 반복 실행에서 `--no-daemon`/`--rerun-tasks`/`--no-build-cache`를 자주 사용
- 동일 워크스페이스에서 Gradle 테스트 명령을 동시에 여러 개 실행

## 매트릭스/퍼즈 운영 정책

- 조합 폭이 큰 회귀/랜덤 검증 테스트는 `@Tag("matrix")`로 분류합니다.
- PR 기본 경로에서는 `matrix`를 제외해 피드백 속도를 유지합니다.
- 필요 시 `-PincludeMatrix`로 수동 실행하고, nightly CI에서 정기 검증합니다.

## 권장 사항

- 실패 경로 테스트는 짧은 timeout 사용
- reconnect 검증 테스트가 아니면 reconnect 비활성화
- 통합 테스트는 태그 기반 opt-in 유지
- 외부 의존 대신 빠르게 실패하는 고정 invalid endpoint 활용

## Gradle 기본 실행 설정

`gradle.properties`에 로컬 기여자를 위한 기본 속도 설정이 포함되어 있습니다:

- `org.gradle.daemon=true`
- `org.gradle.parallel=true`
- `org.gradle.caching=true`
- `kotlin.incremental=true`

## CI 시간 가드

CI는 `scripts/ci/run-with-duration-guard.sh`로 실행 시간을 제한합니다:

| 스위트 | 예산 | CI 환경변수 |
|--------|------|------------|
| safety 게이트 (`-PincludeSafety`) | 120초 | `SAFETY_TEST_MAX_SECONDS` |
| 통합 API 스위트 (`-PincludeIntegration`) | 150초 | `INTEGRATION_TEST_MAX_SECONDS` |
| API 회귀 플로우 (단일 테스트) | 120초 | `ci.yml`에 하드코딩 |
| pre-open 게이트 (전체 프리플라이트) | 1200초 | `PRE_OPEN_MAX_SECONDS` |
| nightly matrix (`-PincludeMatrix`) | 420초 | `MATRIX_TEST_MAX_SECONDS` |

예산을 초과하면 CI를 즉시 실패시켜 시간 회귀를 빠르게 감지합니다.
통합 게이트에서는 외부 MCP 통합 테스트가 포함되지 않았는지도 함께 검증합니다.

Nightly matrix 워크플로:

- `.github/workflows/nightly-matrix.yml`에서 `:arc-core:test -PincludeMatrix`를 주기 실행합니다.
- 워크플로 요약에는 JUnit 총계와 실패 케이스가 자동 정리됩니다(`scripts/ci/summarize-junit-failures.sh`).
- `NIGHTLY_MATRIX_SLACK_WEBHOOK_URL` 시크릿이 설정되어 있으면 실패 시 Slack 알림을 전송합니다.

## CI 구조 가드

핵심 오케스트레이션/설정 파일은 라인 수 상한을 CI에서 강제합니다:

- `SpringAiAgentExecutor.kt` <= 900줄
- `ArcReactorCoreBeansConfiguration.kt` <= 350줄
- `AgentPolicyAndFeatureProperties.kt` <= 500줄

가드 스크립트: `scripts/ci/check-file-size-guard.sh`

## CI 문서 가드

CI는 문서 정합성과 탐색 가능성도 함께 검증합니다:

- `scripts/ci/check-agent-doc-sync.sh`: `AGENTS.md`가 `CLAUDE.md`의 핵심 섹션을 포함하는지 검사 (부분 집합 검증, 바이트 동일성 아님).
- `scripts/ci/check-doc-links.py`: 로컬 마크다운 링크 + `docs/en`/`docs/ko` 패키지 README 인덱스 검사.
- `scripts/ci/check-default-config-alignment.py`: 문서의 기본 설정값이 소스 코드와 일치하는지 검사.

## CI Flyway 마이그레이션 가드

CI는 기존 Flyway 마이그레이션의 불변성을 강제합니다:

- `scripts/ci/check-flyway-migration-immutability.sh`: 이미 버전이 지정된 `V*.sql` 파일이 수정, 삭제 또는 이름 변경되면 실패합니다.
- 새로 추가된 버전 마이그레이션 파일은 가드를 통과합니다.

## 테스트 픽스처 및 어설션

### AgentTestFixture

`arc-core/src/test/kotlin/com/arc/reactor/agent/AgentTestFixture.kt`

에이전트 테스트를 위한 공유 목(mock) 설정. `ChatClient` / `RequestSpec` / `CallResponseSpec` 중복 설정을 제거합니다.

인스턴스 메서드:

| 메서드 | 용도 |
|--------|------|
| `mockCallResponse(content)` | 간단한 성공 응답 설정 |
| `mockToolCallResponse(toolCalls)` | 도구 호출을 포함하는 `CallResponseSpec` 생성 (ReAct 루프 트리거) |
| `mockFinalResponse(content)` | 최종(도구 호출 없는) 응답 `CallResponseSpec` 생성 |

Companion object 헬퍼:

| 메서드 | 용도 |
|--------|------|
| `simpleChatResponse(content)` | 텍스트 콘텐츠만 있는 `ChatResponse` 빌드 |
| `defaultProperties()` | `runTest`용 요청 타임아웃 비활성화된 `AgentProperties` 빌드 |
| `toolCallback(name, description, result)` | 고정 결과를 반환하는 `ToolCallback` 생성 |
| `delayingToolCallback(name, delayMs, result)` | 코루틴 delay 포함 `ToolCallback` 생성 (`Thread.sleep` 아님) |
| `textChunk(text)` | 텍스트 콘텐츠 `ChatResponse` 청크 생성 (스트리밍 테스트) |
| `toolCallChunk(toolCalls, text)` | 도구 호출 `ChatResponse` 청크 생성 (스트리밍 테스트) |

### TrackingTool

`TrackingTool`은 호출 횟수와 캡처된 인수를 기록하는 `ToolCallback` 구현체입니다:

```kotlin
val tracker = TrackingTool("search", result = "found it")
// ... 에이전트 실행 ...
assertEquals(2, tracker.callCount, "search should be called twice")
assertEquals("query-value", tracker.capturedArgs[0]["query"], "first call should pass query")
```

### AgentResultAssertions

`arc-core/src/test/kotlin/com/arc/reactor/agent/AgentResultAssertions.kt`

실패 시 실제 에러를 표면화하는 `AgentResult` 확장 함수:

| 메서드 | 용도 |
|--------|------|
| `assertSuccess(message)` | `success == true` 확인, 실패 시 `errorMessage` 표시 |
| `assertFailure(message)` | `success == false` 확인, 실패 시 `content` 표시 |
| `assertErrorContains(expected)` | `errorMessage`에 `expected` 포함 확인 (대소문자 무시) |
| `assertErrorCode(expected)` | 특정 `AgentErrorCode` 확인 |

## Gradle 테스트 JVM 설정

루트 `build.gradle.kts`가 모든 서브프로젝트의 테스트 JVM 인수를 설정합니다:

```kotlin
tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs(
        "-XX:+UseParallelGC",
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=1"
    )
}
```

## H2/JDBC 검증

DB 관련 회귀 검증은 재현 가능성을 우선합니다:

- 로컬 회귀는 H2 중심으로 빠르게 확인
- 컨테이너 기반 통합 검증은 `integration` 실행 시에만 수행

## 관련 문서

- [기능 인벤토리](../reference/feature-inventory.md)
- [모듈 레이아웃](../architecture/module-layout.md)
