# 모듈 레이아웃 가이드

현재 Gradle 모듈 경계와 권장 실행/빌드 엔트리포인트를 정리한 문서입니다.

## Gradle 모듈

`settings.gradle.kts` 기준:

- `arc-core`: 에이전트 엔진, 정책, Memory/RAG, MCP 매니저, 공통 도메인
- `arc-web`: REST API 컨트롤러 및 웹 계층 통합
- `arc-slack`: Slack 채널 게이트웨이
- `arc-discord`: Discord 채널 게이트웨이
- `arc-line`: LINE 채널 게이트웨이
- `arc-error-report`: 에러 리포팅 확장 모듈
- `arc-app`: 실행 조립(assembly) 모듈

## `arc-app` 도입 이유

`arc-core`를 라이브러리 스타일로 유지하고, 실행 조립 책임을 `arc-app`으로 분리했습니다.

- `arc-core`:
  - `bootJar` 비활성화
  - 다른 모듈 의존을 위한 plain `jar` 유지
- `arc-app`:
  - `arc-core` 의존
  - web/channel 모듈을 runtime 의존으로 조립
  - 실행 `mainClass` 지정

## 권장 명령어

- 로컬 실행: `./gradlew :arc-app:bootRun`
- 실행 JAR: `./gradlew :arc-app:bootJar`
- 기본 전체 테스트: `./gradlew test --continue`
- 통합 테스트 포함: `./gradlew test -PincludeIntegration`

## 의존 방향(요약)

- channel/web 모듈은 `arc-core`를 의존
- `arc-app`이 런타임을 조립
- `arc-core`는 channel/web로 역방향 runtime 결합을 피해야 함

## 관련 문서

- [아키텍처 개요](architecture.md)
- [배포 가이드](../getting-started/deployment.md)
- [테스트/성능 가이드](../engineering/testing-and-performance.md)
