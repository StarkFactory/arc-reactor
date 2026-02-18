# 테스트 및 성능 가이드

현재 테스트 기본 범위와 실무형 속도 최적화 방법을 정리합니다.

## 기본 테스트 범위

모든 모듈은 기본적으로 `@Tag("integration")` 테스트를 제외합니다.

- 기본 실행: `./gradlew test --continue`
- 통합 테스트 포함: `./gradlew test -PincludeIntegration`
- 통합 API 스위트(core + web): `./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"`
- 외부 의존 통합 테스트 포함(npx/docker/network): `./gradlew test -PincludeIntegration -PincludeExternalIntegration`

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
- 외부 통합 테스트 포함: `INCLUDE_EXTERNAL=1 scripts/dev/test-fast.sh`
- 기존 XML 리포트 기준 느린 테스트 상위 조회: `scripts/dev/slow-tests.sh 30`

## 느린 테스트의 대표 원인

- 실패 경로 테스트에서 과도하게 긴 timeout
- 실패 검증 테스트에서도 reconnect 루프 활성화
- thread/latch 구성이 잘못되어 고정 대기 발생
- 외부 의존 시작/다운로드 지연(예: MCP `npx` 서버 부팅)
- 로컬 반복 실행에서 `--no-daemon`/`--rerun-tasks`/`--no-build-cache`를 자주 사용
- 동일 워크스페이스에서 Gradle 테스트 명령을 동시에 여러 개 실행

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

- 기본(unit) 테스트: 90초
- 통합 API 스위트: 150초

예산을 초과하면 CI를 즉시 실패시켜 시간 회귀를 빠르게 감지합니다.
통합 게이트에서는 외부 MCP 통합 테스트가 포함되지 않았는지도 함께 검증합니다.

## CI 구조 가드

핵심 오케스트레이션/설정 파일은 라인 수 상한을 CI에서 강제합니다:

- `SpringAiAgentExecutor.kt` <= 900줄
- `ArcReactorCoreBeansConfiguration.kt` <= 350줄
- `AgentPolicyAndFeatureProperties.kt` <= 500줄

가드 스크립트: `scripts/ci/check-file-size-guard.sh`

## H2/JDBC 검증

DB 관련 회귀 검증은 재현 가능성을 우선합니다:

- 로컬 회귀는 H2 중심으로 빠르게 확인
- 컨테이너 기반 통합 검증은 `integration` 실행 시에만 수행

## 관련 문서

- [기능 인벤토리](../reference/feature-inventory.md)
- [모듈 레이아웃](../architecture/module-layout.md)
