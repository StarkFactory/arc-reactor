# 설정 Quickstart

Arc Reactor를 fork해서 사용할 때는 먼저 "확실히 동작하는 최소 실행 상태"를 만든 뒤 기능을 점진적으로 켜는 것이 안전합니다.

## 1) 빠른 경로 (bootstrap 스크립트)

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

이 스크립트가 수행하는 작업:

- `examples/config/application.quickstart.yml`을 `arc-core/src/main/resources/application-local.yml`로 복사(없을 때)
- `GEMINI_API_KEY` 검증
- `ARC_REACTOR_AUTH_JWT_SECRET`이 없으면 개발용 시크릿 자동 생성
- PostgreSQL 연결 환경 변수와 함께 `:arc-app:bootRun` 실행

## 2) 수동 경로 (필수 값)

Arc Reactor는 기동 전 preflight 검사를 수행합니다. 아래 값이 모두 필요합니다.

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

필수 값이 없거나 형식이 잘못되면 서버는 즉시 기동 실패합니다.

## 3) 로컬 YAML 파일 (선택)

아래 템플릿에서 시작하세요.

- [`application.yml.example`](../../../application.yml.example)
- [`examples/config/application.quickstart.yml`](../../../examples/config/application.quickstart.yml)
- [`examples/config/application.advanced.yml`](../../../examples/config/application.advanced.yml)

예시:

```bash
cp examples/config/application.quickstart.yml arc-core/src/main/resources/application-local.yml
```

## 4) 첫 API 스모크 테스트

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"qa@example.com","password":"passw0rd!","name":"QA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"한 문장으로 인사해줘."}'
```

선택 계약 점검(LLM 호출 없음):

```bash
./scripts/dev/validate-runtime-contract.sh --base-url http://localhost:8080
```

## 5) 선택 기능 점진 활성화

대표적인 opt-in 토글:

- `arc.reactor.rag.enabled`
- `arc.reactor.rag.ingestion.enabled`
- `arc.reactor.approval.enabled`
- `arc.reactor.tool-policy.dynamic.enabled`
- `arc.reactor.output-guard.enabled`
- `arc.reactor.admin.enabled`

## 6) 전체 설정이 필요하면

- 전체 레퍼런스: [configuration.md](configuration.md)
- 트러블슈팅: [troubleshooting.md](troubleshooting.md)
- 배포 가이드: [deployment.md](deployment.md)
