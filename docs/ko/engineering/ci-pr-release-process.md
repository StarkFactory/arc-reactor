# PR/CI/릴리즈 운영 프로세스

이 문서는 Arc Reactor 저장소에서 PR이 열릴 때 어떤 검증이 실행되고, merge 이후와 tag 릴리즈가 어떻게 진행되는지 한 번에 정리합니다.

## 1. 핵심 답변

- `CI`는 워크플로 파일 하나(`.github/workflows/ci.yml`)입니다.
- 하지만 그 안에 `build`, `integration`, `docker` 3개 job이 있어서 GitHub PR 체크에는 각각 별도 항목으로 표시됩니다.
- 현재 merge 게이트(필수 체크)는 `pre_open`, `build`, `integration`, `docker`입니다.

즉, "CI 한 개냐?" 기준으로는 `예`, "docker도 포함되냐?" 기준으로도 `예`입니다.

## 2. PR 생성 시 실제로 도는 워크플로

`main` 대상 PR 기준:

1. `CI` (`.github/workflows/ci.yml`)
2. `Security Baseline` (`.github/workflows/security-baseline.yml`)

PR 체크 화면에 보통 아래 이름으로 나타납니다.

- `build (21)`
- `pre_open`
- `integration (21)`
- `docker`
- `Secret Scan (Gitleaks)`
- `Dependency Vulnerability Scan (Trivy)`

`(21)`은 Java 매트릭스 버전(`java: 21`) 때문에 붙는 표시입니다.

경로 기반 최적화가 적용되어 PR 내용에 따라 일부 체크는 `skipped`로 표시될 수 있습니다.
GitHub 보호 브랜치의 required status check는 `successful`뿐 아니라 `skipped`/`neutral`도 통과로 처리됩니다.

## 3. CI 워크플로 상세 (`ci.yml`)

실행 순서(의존성):

1. `changes` 실행 (변경 파일 분류)
2. `pre_open` 실행 (`needs: changes`, 조건부)
3. `build` 실행 (`needs: [changes, pre_open]`, 조건부)
4. `integration` 실행 (`needs: [changes, build]`, 조건부)
5. `docker` 실행 (`needs: [changes, build, integration]`, 조건부)

### 3.0 경로 기반 분기 규칙

`changes` job은 PR의 변경 파일을 아래 두 그룹으로 분류합니다.

- `ci_relevant`: 빌드/테스트가 필요한 변경
- `runtime_deploy`: 런타임/배포 영향 변경(도커 스모크 테스트 대상)

`build`/`integration` 실행 조건:

- `push(main)`이면 항상 실행
- `pull_request`면 `ci_relevant == true`일 때만 실행

`docker` 실행 조건:

- `push(main)`이면 항상 실행
- `pull_request`면 `runtime_deploy == true`일 때만 실행

### 3.0.1 런타임/배포 영향 PR 판정 기준

아래 경로가 변경되면 `runtime_deploy=true`로 간주합니다.

- `Dockerfile`, `.dockerignore`, `docker/**`, `compose*.yml`
- `arc-*/src/main/**`, `arc-*/src/main/resources/**`
- `arc-app/**`
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `buildSrc/**`
- `scripts/deploy/**`
- `.github/workflows/release.yml`

즉, 질문하신 "런타임/배포 영향 PR을 어떻게 구분하나?"의 답은
"파일 경로 규칙으로 판정"입니다.
문서/릴리즈노트/테스트 전용 변경은 기본적으로 이 그룹에 들어가지 않으므로 `docker`를 생략합니다.

### 3.1 `pre_open` job

주요 단계:

1. JDK/Gradle 설정
2. Gitleaks CLI 설치
3. `scripts/dev/pre-open-check.sh` 실행 (duration guard 적용)

### 3.2 `build` job

주요 단계:

1. JDK/Gradle 설정
2. 문서/정합성 가드
3. 빌드(`./gradlew build -x test`)
4. 안전 게이트 테스트(핵심 보안/인가 경로 타깃 실행)
5. 테스트 리포트 업로드

문서/정합성 가드 스크립트:

- `scripts/ci/check-agent-doc-sync.sh`
- `scripts/ci/check-doc-links.py`
- `scripts/ci/check-default-config-alignment.py`
- `scripts/ci/check-file-size-guard.sh`

### 3.3 `integration` job

주요 단계:

1. 통합 테스트 실행
2. 외부 통합 테스트가 잘못 포함됐는지 검증
3. 리포트 업로드

실행 명령:

```bash
./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"
```

### 3.4 `docker` job

주요 단계:

1. `bootJar` 생성
2. Dockerfile 빌드 스모크 테스트

실행 명령:

```bash
./gradlew bootJar -x test
docker build -t arc-reactor:ci .
```

## 4. Security Baseline 워크플로 상세 (`security-baseline.yml`)

PR 시 병렬로 실행됩니다.

- `Secret Scan (Gitleaks)`: 시크릿 유출 검사
- `Dependency Vulnerability Scan (Trivy)`: 의존성 취약점 검사(조건부)

의존성 취약점 스캔 실행 정책:

- `pull_request`: 의존성/빌드 파일 변경 시에만 실행
- `schedule`, `workflow_dispatch`: 항상 실행(전체 점검)

추가로 스케줄 실행도 있습니다.

- 매주 월요일 03:00 UTC (KST 월요일 12:00)

## 5. Merge 이후(`main` 푸시) 동작

PR이 머지되어 `main`에 커밋이 들어가면 `CI` 워크플로가 다시 실행됩니다.

- 트리거: `push` on `main`
- 목적: main 브랜치 건강 상태 재확인

`Security Baseline`은 기본적으로 `pull_request`, `schedule`, `workflow_dispatch` 트리거이므로 merge 직후 자동 재실행 대상은 아닙니다.

## 6. 릴리즈(tag) 프로세스 (`release.yml`)

트리거:

1. `v*` 태그 푸시
2. 수동 실행(`workflow_dispatch`)

실행 순서:

1. `security-gates`: Gitleaks + Trivy 통과 필요
2. `create-release`: 아티팩트 생성/서명/증명 후 GitHub Release 생성

생성 아티팩트(요약):

- 실행 JAR
- SBOM(CycloneDX JSON)
- SHA256 체크섬
- Cosign 서명(`.sig`) + 인증서(`.pem`)
- Build provenance attestation

릴리즈 노트 규칙:

1. `docs/en/releases/<tag>.md`가 있으면 해당 파일 본문 사용
2. 없으면 GitHub 자동 생성 노트 사용

## 7. 야간/수동 운영 워크플로

### 7.1 Nightly Matrix Tests (`nightly-matrix.yml`)

- 매일 18:20 UTC (KST 다음날 03:20) 자동 실행
- `-PincludeMatrix` 테스트 실행
- 실패 시 Slack 웹훅 알림 가능

### 7.2 Slack Runtime Validation (`slack-runtime-validation.yml`)

- 수동 실행 전용
- 실제 Slack 시크릿/토큰 기반 런타임 검증
- 필요 시 MCP 체크까지 포함

## 8. 운영자 체크리스트(실무형)

PR 전/중:

1. 로컬 `./gradlew test`
2. PR 생성 후 `build`, `integration`, `docker` 통과 확인
3. `pre_open` 통과 확인
4. 보안 스캔 실패 시 원인 분석 후 수정

merge:

1. 필수 체크 4개(`pre_open`, `build`, `integration`, `docker`) green 확인
2. 머지 방식 선택(일반 기능 PR은 보통 squash)

release:

1. 버전 범프 커밋
2. `git tag vX.Y.Z && git push origin vX.Y.Z`
3. Release 워크플로 성공 확인
4. Release 페이지에서 아티팩트/서명/SBOM 확인

## 9. 자주 헷갈리는 포인트

- PR 체크에 `CI` 하나만 보이지 않고 여러 줄로 보이는 이유:
  - 워크플로 1개 안의 job이 각각 status check로 분리 표시되기 때문입니다.
- `docker`는 별도 워크플로가 아니라 `CI` 워크플로 내부 job입니다.
- merge 게이트는 정책 파일(`AGENTS.md`) 기준 `pre_open`, `build`, `integration`, `docker`입니다.

## 10. 참고 파일

- `.github/workflows/ci.yml`
- `.github/workflows/security-baseline.yml`
- `.github/workflows/release.yml`
- `.github/workflows/nightly-matrix.yml`
- `.github/workflows/slack-runtime-validation.yml`
- `AGENTS.md`
