# Arc Reactor

[![CI](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml/badge.svg)](https://github.com/StarkFactory/arc-reactor/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-4.7.3-blue.svg)](https://github.com/StarkFactory/arc-reactor/releases/tag/v4.7.3)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

한국어 문서 / [English](README.md)

**Spring AI 기반의 엔터프라이즈급 AI Agent 런타임. Fork하고, 커스터마이즈하고, 배포하세요.**

## Arc Reactor란?

Arc Reactor는 강력한 거버넌스 제어가 필요한 팀을 위한 프로덕션 수준의 AI Agent 플랫폼 템플릿입니다.
기존 프로젝트에 추가하는 라이브러리가 아니라, Fork하여 자체 플랫폼으로 운영하는 완전한 애플리케이션입니다.

런타임의 핵심은 **ReAct 루프** (Reasoning + Acting)입니다. LLM이 어떤 Tool을 호출할지 결정하고,
Arc Reactor의 Tool 시스템을 통해 실행한 뒤 결과를 관찰하고, 최종 답변을 생성할 때까지 반복합니다.
이 과정의 모든 단계는 사용자에게 도달하기 전에 구성 가능한 안전 및 거버넌스 레이어를 통과합니다.

Arc Reactor는 PoC가 프로덕션 서비스로 전환된 이후 나타나는 어려운 운영 문제들을 해결합니다.
어떤 채널에서 어떤 Tool을 실행할지 제어하고, 재시작 후에도 대화 기록을 유지하고, 프롬프트를
버전 관리하고, 모든 작업을 감사하고, 위험도 높은 Tool 실행을 사전에 승인받고, 웹 API와 함께
Slack으로 챗 서비스를 통합하는 문제들이 그 예입니다.

> Arc Reactor는 라이브러리가 아닙니다. 권장 사용 방식은 **Fork → 커스터마이즈 → 배포**입니다.
> 업스트림 메인테이너는 업스트림 레포지터리에 대해서만 책임을 집니다. 다운스트림 Fork 운영자는
> 자신의 배포, 보안 강화, 컴플라이언스, 인시던트 대응에 대해 전적으로 책임집니다.
> 다운스트림 운영에 대한 SLA, 보증, 손해배상은 제공되지 않습니다.

## 주요 기능

- **ReAct 실행 엔진** — 제한된 Tool 호출 반복 횟수, 설정 가능한 재시도, 자동 컨텍스트 트리밍,
  구조화된 출력(Text / JSON / YAML)과 유효성 검증 및 자동 복구
- **5단계 Guard 파이프라인** — 실패 시 차단(fail-close) 입력 검증: 요청 속도 제한, 입력 길이,
  Unicode 정규화, 분류(선택), Canary Token 감지
- **4개 Hook 생애주기** — BeforeStart, AfterToolCall, BeforeResponse, AfterComplete —
  감사 로깅, 과금, 정책 적용, 사이드 이펙트 처리용
- **동적 MCP 등록** — REST API를 통해 재시작 없이 런타임에 MCP(Model Context Protocol) 서버(STDIO 또는 SSE) 등록; 서버별 접근 정책 제어
- **Human-in-the-Loop 승인** — Tool 실행 전 사람의 검토를 위한 큐잉
- **Tool 정책 엔진** — 채널별 쓰기 Tool 거버넌스: 특정 채널에서 쓰기 Tool 차단, 채널별 허용 Tool 목록 설정
- **프롬프트 템플릿 버전 관리** — 프롬프트 변형 저장, 버전 관리, 승격; LLM Judge 점수를 활용한 자동 평가를 위한 Prompt Lab
- **RAG 파이프라인** — 쿼리 변환, PGVector 검색, 리랭킹, 컨텍스트 주입; API를 통한 동적 수집 거버넌스
- **멀티 에이전트 오케스트레이션** — Sequential, Parallel, Supervisor 패턴과 WorkerAgentTool 래핑
- **멀티채널 전송** — REST + SSE 스트리밍, Slack(Socket Mode / HTTP)
- **관리자 감사 로그** — API로 접근 가능한 모든 관리자 작업의 감사 로그
- **출력 Guard 규칙** — LLM 응답에 적용되는 런타임 설정 가능한 콘텐츠 정책
- **복원성** — 서킷 브레이커, 설정 가능한 요청/Tool 타임아웃, 폴백 모델 체인
- **관찰 가능성** — OpenAPI/Swagger, Spring Actuator, Prometheus 메트릭, OpenTelemetry 트레이싱
- **보안** — JWT 인증, 보안 헤더(HSTS, CSP), CORS 제어, MCP 서버 허용 목록
- **Kubernetes 지원** — HPA, Ingress, 시크릿 관리, Liveness/Readiness Probe, Graceful Shutdown을 포함한 프로덕션용 Helm 차트

## 빠른 시작

### 1. 클론

```bash
git clone https://github.com/StarkFactory/arc-reactor.git
cd arc-reactor
```

### 2. 필수 환경 변수 설정 후 실행

**빠른 방법 (부트스트랩 스크립트):**

```bash
./scripts/dev/bootstrap-local.sh --api-key your-gemini-api-key --run
```

**수동 방법:**

```bash
docker compose up -d db
export GEMINI_API_KEY=your-gemini-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

필수 런타임 값이 없으면 Arc Reactor는 기동 시점에 즉시 실패합니다:

- `ARC_REACTOR_AUTH_JWT_SECRET` (최소 32바이트)
- `SPRING_DATASOURCE_URL` (`jdbc:postgresql://...` 형식)
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

기본 LLM 제공자는 Gemini입니다. OpenAI나 Anthropic을 사용하려면
`SPRING_AI_OPENAI_API_KEY` 또는 `SPRING_AI_ANTHROPIC_API_KEY`를 설정하세요.

### 3. 동작 확인

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"qa@example.com","password":"passw0rd!","name":"QA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message": "3 더하기 5는?"}'
```

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- 헬스 체크: `http://localhost:8080/actuator/health`

### 런타임 필수 조건 요약

| 필수 항목 | 이유 |
|---|---|
| `GEMINI_API_KEY` (또는 다른 provider 키) | `ChatClient` provider 빈 생성에 필요 |
| `ARC_REACTOR_AUTH_JWT_SECRET` | JWT 인증 필터/토큰 발급에 필수 |
| PostgreSQL datasource 환경 변수 (`SPRING_DATASOURCE_*`) | 런타임 preflight 기본값(`arc.reactor.postgres.required=true`) 충족 |

### 인증/테넌트 모델 (중요)

- 인증은 항상 필수이며 `arc.reactor.auth.enabled` 토글은 제거되었습니다.
- `/api/auth/*`에서 발급되는 JWT에는 `tenantId` 클레임이 포함됩니다 (기본값: `default`).
- 런타임 테넌트 해석 우선순위:
  1. JWT의 `tenantId` 클레임
  2. `X-Tenant-Id` 요청 헤더
- 테넌트 컨텍스트가 없으면 `/api/chat`, `/api/chat/stream`은 fail-close로 HTTP 400을 반환합니다.

## 아키텍처

Arc Reactor는 세 가지 플레인으로 역할을 구분합니다.

1. **Control Plane** — 정책, 거버넌스, 상태 관리를 위한 관리자 API
2. **Execution Plane** — ReAct 런타임 (LLM + Tool 루프 + 안전 파이프라인)
3. **Channel Plane** — 전송 게이트웨이 (REST, Slack, Discord, LINE)

요청 흐름:

```
사용자 / 채널 게이트웨이
        |
        v
+----------------------------------+
|  Guard 파이프라인 (fail-close)   |
|  요청 속도 제한 → 입력 검증      |
|  → Unicode → 분류               |
+----------------------------------+
        |
        v
+----------------------------------+
|  Hook: BeforeStart               |
+----------------------------------+
        |
        v
+----------------------------------+
|  ReAct 실행기                    |
|  LLM <-> Tool 루프               |
|  재시도 / 타임아웃 / 컨텍스트 트리밍  |
|  구조화 출력 유효성 검증         |
+----------------------------------+
        |
        v
+----------------------------------+
|  Hook: AfterComplete             |
+----------------------------------+
        |
        v
응답 + 감사 로그 + 메트릭
```

## 모듈 구성

| 모듈 | 설명 | 사용 시기 |
|---|---|---|
| `arc-app` | 실행 가능한 어셈블리 (`bootRun`, `bootJar`) | 항상 — 진입점 |
| `arc-core` | Agent 엔진: ReAct 루프, Guard, Hook, 메모리, RAG, MCP, 정책 | 항상 — 핵심 런타임 |
| `arc-web` | REST 컨트롤러, OpenAPI 스펙, 보안 헤더, CORS | 항상 — HTTP API |
| `arc-admin` | 관리자 모듈: 메트릭, 트레이싱, 운영 대시보드 | 선택 — `arc.reactor.admin.enabled=true` |
| `arc-slack` | Slack 게이트웨이 (Socket Mode 및 HTTP Events) | Slack 통합 시 |
| `arc-error-report` | 오류 보고 확장 모듈 (오류 분석 전용 Agent) | 선택적 기능 모듈 |

## 설정

주요 속성과 기본값입니다. 전체 레퍼런스: [`docs/ko/getting-started/configuration-quickstart.md`](docs/ko/getting-started/configuration-quickstart.md)

```yaml
arc:
  reactor:
    max-tool-calls: 10               # 요청당 최대 Tool 반복 횟수
    max-tools-per-request: 20        # LLM에 노출할 최대 Tool 수

    llm:
      default-provider: gemini
      temperature: 0.3
      max-output-tokens: 4096

    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000      # 30초
      tool-call-timeout-ms: 15000    # Tool당 15초

    guard:
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100

    boundaries:
      input-min-chars: 1
      input-max-chars: 5000
```

### 프로덕션 기본 설정 (권장 베이스라인)

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  flyway:
    enabled: true

arc:
  reactor:
    auth:
      jwt-secret: ${ARC_REACTOR_AUTH_JWT_SECRET}
    approval:
      enabled: true
    tool-policy:
      enabled: true
      dynamic:
        enabled: true
    output-guard:
      enabled: true
    rag:
      enabled: true
    mcp:
      security:
        allowed-server-names: [atlassian, filesystem]
```

### Feature Toggle

| 기능 | 기본값 | 속성 |
|---|---|---|
| Guard | ON | `arc.reactor.guard.enabled` |
| 보안 헤더 | ON | `arc.reactor.security-headers.enabled` |
| 멀티모달 업로드 | ON | — |
| 인증 (JWT) | 필수 | `arc.reactor.auth.jwt-secret` |
| 승인 (HITL) | OFF | `arc.reactor.approval.enabled` |
| Tool 정책 | OFF | `arc.reactor.tool-policy.dynamic.enabled` |
| RAG | OFF | `arc.reactor.rag.enabled` |
| 인텐트 분류 | OFF | `arc.reactor.intent.enabled` |
| 스케줄러 | OFF | `arc.reactor.scheduler.enabled` |
| 피드백 | OFF | `arc.reactor.feedback.enabled` |
| CORS | OFF | `arc.reactor.cors.enabled` |
| 서킷 브레이커 | OFF | `arc.reactor.circuit-breaker.enabled` |
| 출력 Guard | OFF | `arc.reactor.output-guard.enabled` |
| 관리자 모듈 | OFF | `arc.reactor.admin.enabled` |

## 배포 옵션

### 로컬 JVM

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
./gradlew :arc-app:bootRun
```

### Docker Compose (앱 + PostgreSQL)

RAG 워크로드를 위한 pgvector 포함:

```bash
cp .env.example .env
# .env 편집 — GEMINI_API_KEY 및 DB 자격 증명 설정

docker-compose up -d
```

종료: `docker-compose down`

### 사전 빌드 Docker 이미지 (ghcr.io)

모든 버전 태그에서 이미지가 자동으로 게시됩니다:

```bash
docker pull ghcr.io/starkfactory/arc-reactor:4.7.2
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=your-key \
  -e ARC_REACTOR_AUTH_JWT_SECRET=replace-with-32-byte-secret \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/arcreactor \
  -e SPRING_DATASOURCE_USERNAME=arc \
  -e SPRING_DATASOURCE_PASSWORD=arc \
  ghcr.io/starkfactory/arc-reactor:4.7.2
```

사용 가능한 태그: 정확한 버전(예: `4.7.2`), 마이너 스트림(예: `4.7`), 짧은 SHA(`sha-<commit>`).

로컬 비프로덕션 실험에서 PostgreSQL 없이 실행하려면
`-e ARC_REACTOR_POSTGRES_REQUIRED=false`를 추가하세요(프로덕션 비권장).

### Kubernetes (Helm)

프로덕션용 Helm 차트가 `helm/arc-reactor/`에 포함되어 있습니다:

```bash
helm install arc-reactor ./helm/arc-reactor \
  -f helm/arc-reactor/values-production.yaml \
  --set secrets.geminiApiKey=your-api-key
```

HPA, Ingress, 시크릿 관리, Liveness/Readiness Probe, Graceful Shutdown을 포함합니다.
Kubernetes 1.25 이상이 필요합니다. 전체 레퍼런스는 [`helm/arc-reactor/README.md`](helm/arc-reactor/README.md)를 참조하세요.

## Control Plane API 레퍼런스

| 기능 | API 기본 경로 | 활성화 조건 |
|---|---|---|
| 챗 런타임 | `/api/chat` | 항상 |
| SSE 스트리밍 | `/api/chat/stream` | 항상 |
| 세션 및 모델 관리 | `/api/sessions`, `/api/models` | 항상 |
| 페르소나 관리 | `/api/personas` | 항상 |
| 프롬프트 템플릿 버전 관리 | `/api/prompt-templates` | 항상 |
| MCP 서버 레지스트리 | `/api/mcp/servers` | 항상 (조회/쓰기 모두 ADMIN) |
| MCP 접근 정책 | `/api/mcp/servers/{name}/access-policy` | 항상 |
| 출력 Guard 규칙 | `/api/output-guard/rules` | `arc.reactor.output-guard.enabled=true` + `arc.reactor.output-guard.dynamic-rules-enabled=true` |
| 관리자 감사 로그 | `/api/admin/audits` | 항상 |
| 운영 대시보드 | `/api/ops` | 항상 |
| 인증 | `/api/auth` | 항상 |
| Human-in-the-Loop 승인 | `/api/approvals` | `arc.reactor.approval.enabled=true` |
| 동적 Tool 정책 | `/api/tool-policy` | `arc.reactor.tool-policy.dynamic.enabled=true` |
| 인텐트 레지스트리 | `/api/intents` | `arc.reactor.intent.enabled=true` |
| RAG 문서 | `/api/documents` | `arc.reactor.rag.enabled=true` |
| RAG 수집 거버넌스 | `/api/rag-ingestion/policy`, `/api/rag-ingestion/candidates` | `arc.reactor.rag.ingestion.dynamic.enabled=true` |
| 스케줄러 (cron MCP) | `/api/scheduler/jobs` | `arc.reactor.scheduler.enabled=true` |
| 피드백 | `/api/feedback` | `arc.reactor.feedback.enabled=true` |

### 예시: MCP 서버 등록

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "description": "로컬 파일 Tool",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "autoConnect": true
  }'
```

### 예시: Tool 정책 적용

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "allowWriteToolNamesInDenyChannels": ["jira_create_issue"]
  }'
```

### 예시: 프롬프트 템플릿으로 챗 요청

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "INC-1234 인시던트 요약해줘",
    "promptTemplateId": "incident-template",
    "metadata": {"channel": "web"}
  }'
```

## 영속성

Arc Reactor는 모든 영구 상태에 PostgreSQL을 사용합니다. 개발 환경에서는 인메모리 저장소를 사용할 수 있지만, 재시작 시 데이터가 초기화됩니다.

| 도메인 | 저장소 |
|---|---|
| 대화 메모리 | PostgreSQL (JDBC) 또는 InMemory |
| 페르소나 | PostgreSQL (JDBC) 또는 InMemory |
| 프롬프트 템플릿 및 버전 | PostgreSQL (JDBC) |
| MCP 서버 레지스트리 | PostgreSQL (JDBC) 또는 InMemory |
| 출력 Guard 규칙 및 감사 | PostgreSQL (JDBC) |
| 관리자 감사 로그 | PostgreSQL (JDBC) |
| 승인 요청 (HITL) | PostgreSQL (JDBC) |
| Tool 정책 저장소 | PostgreSQL (JDBC) |
| RAG 수집 정책 | PostgreSQL (JDBC) |
| 피드백 | PostgreSQL (JDBC) |
| 스케줄러 작업 | PostgreSQL (JDBC) |

스키마 마이그레이션은 Flyway로 관리됩니다. `SPRING_FLYWAY_ENABLED=true`로 활성화하세요.

## 문서

- [문서 홈](docs/ko/README.md)
- [시작하기](docs/ko/getting-started/README.md)
- [설정 퀵스타트](docs/ko/getting-started/configuration-quickstart.md)
- [배포 가이드](docs/ko/getting-started/deployment.md)
- [아키텍처 개요](docs/ko/architecture/README.md)
- [ReAct 루프 내부 구조](docs/ko/architecture/react-loop.md)
- [MCP 런타임 관리](docs/ko/architecture/mcp/runtime-management.md)
- [Prompt Lab](docs/ko/architecture/prompt-lab.md)
- [인증](docs/ko/governance/authentication.md)
- [Human-in-the-loop](docs/ko/governance/human-in-the-loop.md)
- [Tool 정책 관리](docs/ko/governance/tool-policy-admin.md)
- [Tool 레퍼런스](docs/ko/reference/tools.md)
- [메트릭 레퍼런스](docs/ko/reference/metrics.md)
- [Slack 통합](docs/ko/integrations/slack/ops-runbook.md)
- [엔지니어링 가이드](docs/ko/engineering/README.md)
- [릴리즈 노트](docs/ko/releases/README.md)

## 프로덕션 보안 주의사항

- JWT 인증은 필수입니다. 모든 환경에서 `ARC_REACTOR_AUTH_JWT_SECRET`을 설정하세요.
- `ARC_REACTOR_AUTH_JWT_SECRET`은 환경 변수로 제공하세요 (최소 32바이트). 시크릿을 코드에 커밋하지 마세요.
- `arc.reactor.mcp.security.allowed-server-names`로 MCP 서버 노출을 제한하세요.
- 고위험 쓰기 작업에는 Tool 정책과 승인 게이트를 사용하세요.
- PostgreSQL과 함께 Flyway를 활성화하여 거버넌스 데이터가 재시작 후에도 유지되도록 하세요.
- 보안 정책: [`SECURITY.md`](SECURITY.md)

## 알려진 제약사항

- MCP SDK `0.17.2`는 Streamable HTTP 전송을 지원하지 않습니다. SSE 또는 STDIO를 사용하세요.
- 기본 런타임 preflight에서 PostgreSQL datasource를 요구합니다 (`arc.reactor.postgres.required=true`).
  로컬 비프로덕션 실험에서만 `ARC_REACTOR_POSTGRES_REQUIRED=false`를 사용하세요.
- 외부 통합 테스트는 명시적으로 활성화해야 합니다: `./gradlew test -PincludeIntegration -PincludeExternalIntegration`.

## 빌드 및 테스트 명령어

```bash
./gradlew test                                       # 전체 테스트 실행
./gradlew test --tests "com.arc.reactor.agent.*"    # 패키지 필터
./gradlew compileKotlin compileTestKotlin           # 컴파일 확인 (목표: 경고 0개)
./gradlew :arc-core:test -Pdb=true                 # PostgreSQL/PGVector/Flyway 의존성 포함
./gradlew test -PincludeIntegration                # @Tag("integration") 테스트 포함
./gradlew :arc-app:bootRun                         # 로컬 실행
```

## 기여하기

1. 레포지터리를 Fork하고 기능 브랜치를 생성하세요: `git checkout -b feature/my-feature`
2. 변경사항을 만들고, 테스트를 작성하거나 업데이트한 후 `./gradlew test`가 통과하는지 확인하세요
3. [Conventional Commits](https://www.conventionalcommits.org/) 형식으로 커밋 메시지를 작성하세요
4. Pull Request를 열어주세요 — CI가 통과해야 머지됩니다

전체 가이드: [`CONTRIBUTING.md`](CONTRIBUTING.md) | [`SUPPORT.md`](SUPPORT.md) | [`CHANGELOG.md`](CHANGELOG.md)

## 라이선스

Apache License 2.0. [`LICENSE`](LICENSE) 파일을 참조하세요.
