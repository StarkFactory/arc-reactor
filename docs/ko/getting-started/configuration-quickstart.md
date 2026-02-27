# 설정 Quickstart

Arc Reactor를 fork해서 사용할 때는 최소 설정부터 시작하세요.

## 1) 빠른 시작(bootstrap 스크립트)

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

이 스크립트는 `examples/config/application.quickstart.yml`을
`arc-core/src/main/resources/application-local.yml`로 복사(없을 때),
`GEMINI_API_KEY`를 검증하고 `:arc-app:bootRun`까지 실행합니다.

## 2) 필수 값(수동 경로)

첫 실행에 필요한 환경 변수는 1개입니다:

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

## 3) 로컬 YAML 파일(선택)

명시적인 로컬 설정이 필요하면 quickstart 예시부터 시작하세요:

- [`application.yml.example`](../../../application.yml.example)
- [`examples/config/application.quickstart.yml`](../../../examples/config/application.quickstart.yml)

예시:

```bash
cp examples/config/application.quickstart.yml arc-core/src/main/resources/application-local.yml
```

## 4) 기능은 단계적으로 활성화

주요 기능은 기본적으로 opt-in입니다(guard/security headers 제외):

- `arc.reactor.rag.enabled`
- `arc.reactor.auth.enabled`
- `arc.reactor.cors.enabled`
- `arc.reactor.circuit-breaker.enabled`
- `arc.reactor.cache.enabled`

## 5) 전체 설정이 필요하면

다음 문서/예시로 확장하세요:

- 전체 레퍼런스: [configuration.md](configuration.md)
- 고급 예시: [`examples/config/application.advanced.yml`](../../../examples/config/application.advanced.yml)
