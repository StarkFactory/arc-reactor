# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

[English](README.md)

**Fork해서 바로 쓰는 AI Agent 오픈소스 프로젝트**

Arc Reactor는 Spring AI 기반의 AI Agent 프로젝트입니다. Guard, Hook, Memory, RAG, MCP, ReAct 루프 등 프로덕션에 필요한 패턴이 이미 구조화되어 있습니다. Fork하고, 도구를 붙이고, 배포하세요.

> **이것은 라이브러리나 프레임워크가 아닙니다.** `implementation(...)` 으로 가져다 쓰는 게 아니라, Fork해서 내 프로젝트로 만드는 구조입니다.

## Fork 운영 책임 경계

- Arc Reactor는 Apache-2.0 라이선스로 **있는 그대로(AS IS)** 제공됩니다 (`LICENSE`).
- 업스트림 메인테이너 책임 범위는 이 업스트림 저장소 자체에 한정됩니다.
- 각 기업/팀의 포크 운영자는 자신의 배포/운영에 대해 전적인 책임을 집니다:
  보안 하드닝, 시크릿 관리, 접근통제, 컴플라이언스, 사고 대응, 운영 변경 관리.
- 포크/커스텀 배포에서 발생한 장애, 침해, 데이터 손실, 컴플라이언스 위반에 대해
  업스트림 메인테이너는 책임을 지지 않습니다.
- 포크 운영에 대한 SLA, 보증, 배상은 제공되지 않습니다.

## 시작하기

### 1. Fork & Clone

```bash
# GitHub에서 Fork 후
git clone https://github.com/<your-username>/arc-reactor.git
cd arc-reactor
```

### 2. LLM Provider 설정

환경 변수로 provider API 키를 설정합니다:

```bash
# 기본 provider (arc-core에 기본 활성화): Google Gemini
export GEMINI_API_KEY=your-api-key

# 선택 provider (의존성을 implementation으로 전환한 경우)
# export SPRING_AI_OPENAI_API_KEY=your-api-key
# export SPRING_AI_ANTHROPIC_API_KEY=your-api-key
```

provider 의존성은 `arc-core/build.gradle.kts`에서 관리합니다.

- 기본: `spring-ai-starter-model-google-genai` 활성화
- OpenAI/Anthropic: 기본 `compileOnly`
- provider를 전환하면 `arc-core/build.gradle.kts`에서 대상 의존성을 `implementation(...)`으로 변경

#### 최소 실행 설정 (fork 친화)

처음 로컬 실행은 필수 값 1개만 있으면 됩니다:

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

그 외 기능은 전부 opt-in(`auth`, `rag`, `cors`, `circuit-breaker`)이라서 필요할 때 단계적으로 켜면 됩니다.
`docs/ko/getting-started/configuration-quickstart.md`부터 보고, 필요 시 `configuration.md`로 확장하세요.

### 3. 도구 만들기

`arc-core/src/main/kotlin/com/arc/reactor/tool/`에 비즈니스 로직 도구를 추가합니다:

```kotlin
@Component
class OrderTool : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "주문 상태를 조회합니다")
    fun getOrderStatus(@ToolParam("주문 번호") orderId: String): String {
        return orderRepository.findById(orderId)?.status ?: "주문을 찾을 수 없습니다"
    }
}
```

### 4. 실행

#### IntelliJ에서 실행

1. **Gradle Tool Window**에서 `arc-app > Tasks > application > bootRun` 실행
2. **Run/Debug Configuration → Environment variables**에 입력:
   ```
   GEMINI_API_KEY=AIzaSy_your_actual_key
   ```
3. Run

> PostgreSQL도 사용하려면: `GEMINI_API_KEY=your_key;SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor;SPRING_DATASOURCE_USERNAME=arc;SPRING_DATASOURCE_PASSWORD=arc`

#### CLI에서 실행

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

#### Docker Compose로 실행

```bash
cp .env.example .env
# .env 파일에서 GEMINI_API_KEY를 실제 키로 수정

docker-compose up -d          # 백엔드 + PostgreSQL 시작
docker-compose up app          # PostgreSQL 없이 백엔드만 시작
docker-compose down            # 중지
```

#### API 테스트

```bash
# 일반 응답
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "3 + 5는 얼마야?"}'

# 스트리밍
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "안녕하세요"}'

# 사용 가능 모델 조회
curl http://localhost:8080/api/models

# 세션 목록 조회
curl http://localhost:8080/api/sessions
```

> 인증 활성화 시 모든 요청에 `Authorization: Bearer <token>` 헤더가 필요합니다. [인증](#인증-opt-in) 섹션을 참고하세요.

## 프로젝트가 제공하는 것

| 기능 | 설명 | 커스터마이징 |
|------|------|-------------|
| **ReAct 루프** | Think -> Act -> Observe 자율 실행 | `maxToolCalls`로 루프 제한 |
| **Guard (5단계)** | Rate Limit -> 입력검증 -> 인젝션탐지 -> 분류 -> 권한 | 각 단계 교체/추가 가능 |
| **Hook (4 라이프사이클)** | 에이전트 시작/종료, 도구 호출 전/후 | `@Component`로 Hook 추가 |
| **Memory** | 세션별 대화 기록 (InMemory / PostgreSQL) | `MemoryStore` 구현으로 교체 |
| **RAG** | Query변환 -> 검색 -> 재순위 -> 컨텍스트빌드 | 4단계 각각 교체 가능 |
| **MCP** | Model Context Protocol (STDIO/SSE) | 외부 MCP 서버 연결 |
| **컨텍스트 윈도우 관리** | 토큰 기반 메시지 트리밍 | 토큰 예산 설정 |
| **LLM 재시도** | 지수 백오프 + 지터 | 재시도 조건/횟수 설정 |
| **병렬 도구 실행** | 코루틴 기반 동시 실행 | 자동 (설정 불필요) |
| **Structured Output** | JSON 응답 모드 | `responseFormat = JSON` |
| **응답 캐싱** | Caffeine 기반 응답 캐시 (opt-in) | `cache.enabled`, 온도 기반 적격성 |
| **서킷 브레이커** | Kotlin 네이티브 서킷 브레이커 (opt-in) | `circuit-breaker.enabled`, 실패 임계값 |
| **우아한 성능 저하** | 실패 시 순차적 모델 폴백 (opt-in) | `fallback.enabled`, 폴백 모델 목록 |
| **응답 필터** | 후처리 파이프라인 (예: 최대 길이) | `ResponseFilter` `@Component`로 추가 |
| **관측성 메트릭** | 파이프라인 전체 9개 메트릭 포인트 | `AgentMetrics`를 Micrometer 등으로 교체 |
| **멀티에이전트** | Sequential / Parallel / Supervisor | DSL 빌더 API |
| **인증** | JWT 인증 + WebFilter (opt-in) | `AuthProvider` / `UserStore` 교체 |
| **페르소나 관리** | 시스템 프롬프트 템플릿 CRUD API | `PersonaStore` 교체 |
| **세션 관리** | 세션 조회/삭제 REST API | 자동 활성화 |
| **Web UI** | React 채팅 인터페이스 ([arc-reactor-web](https://github.com/eqprog/arc-reactor-web)) | Fork해서 커스터마이즈 |

## 멀티에이전트

여러 전문 에이전트가 협력하는 3가지 패턴을 지원합니다:

```kotlin
// Sequential: A의 출력 → B의 입력 → C의 입력
val result = MultiAgent.sequential()
    .node("researcher") { systemPrompt = "자료를 조사하라" }
    .node("writer") { systemPrompt = "조사 결과로 글을 작성하라" }
    .execute(command, agentFactory)

// Parallel: 동시 실행 후 결과 병합
val result = MultiAgent.parallel()
    .node("security") { systemPrompt = "보안 분석" }
    .node("style") { systemPrompt = "스타일 검사" }
    .execute(command, agentFactory)

// Supervisor: 매니저가 워커에게 작업 위임
val result = MultiAgent.supervisor()
    .node("order") { systemPrompt = "주문 처리"; description = "주문 조회/변경" }
    .node("refund") { systemPrompt = "환불 처리"; description = "환불 신청/확인" }
    .execute(command, agentFactory)
```

> 자세한 설명은 [멀티에이전트 가이드](docs/ko/architecture/multi-agent.md)를 참고하세요.

## 아키텍처

```
User Request
     │
     ▼
┌─────────────────────────────────────────────────┐
│  GUARD PIPELINE                                 │
│  RateLimit → InputValid → InjDetect → Classify → Permission │
└─────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────┐
│  HOOK: BeforeAgentStart                         │
└─────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────┐
│  AGENT EXECUTOR (ReAct Loop)                    │
│  1. Memory 로드 + 컨텍스트 윈도우 트리밍       │
│  2. 도구 선택 (Local + MCP)                     │
│  3. LLM 호출 (재시도 포함)                      │
│  4. 도구 병렬 실행 (Hook 포함)                  │
│  5. 응답 반환 또는 루프 계속                    │
└─────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────┐
│  HOOK: AfterAgentComplete                       │
└─────────────────────────────────────────────────┘
     │
     ▼
  Response
```

## 어디를 수정하면 되나요?

Fork 후 수정이 필요한 부분과 건드릴 필요 없는 부분을 구분했습니다:

### 수정할 곳 (내 비즈니스 로직)

| 파일/패키지 | 할 일 |
|-------------|-------|
| `arc-core/src/main/kotlin/com/arc/reactor/tool/` | **도구 추가** — `LocalTool` + `@Tool` 어노테이션으로 비즈니스 로직 연결 |
| `arc-core/src/main/resources/application.yml` (또는 외부 env/config) | **설정 변경** — LLM provider, Guard 임계값, RAG on/off 등 |
| `arc-core/src/main/kotlin/com/arc/reactor/guard/impl/` | **커스텀 Guard** — 비즈니스 규칙에 맞는 분류/권한 단계 구현 |
| `arc-core/src/main/kotlin/com/arc/reactor/hook/` | **커스텀 Hook** — 감사 로그, 빌링, 알림 등 `@Component`로 추가 |
| `arc-web/src/main/kotlin/com/arc/reactor/controller/` | **API 수정** — 인증 추가, 엔드포인트 변경 등 |

### 건드릴 필요 없는 곳 (이미 구조화됨)

| 파일/패키지 | 역할 |
|-------------|------|
| `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt` | ReAct 루프, 재시도, 컨텍스트 관리 — 그대로 사용 |
| `arc-core/src/main/kotlin/com/arc/reactor/guard/impl/GuardPipeline.kt` | Guard 파이프라인 오케스트레이션 — 그대로 사용 |
| `arc-core/src/main/kotlin/com/arc/reactor/hook/HookExecutor.kt` | Hook 실행 엔진 — 그대로 사용 |
| `arc-core/src/main/kotlin/com/arc/reactor/memory/` | 대화 기록 관리 — InMemory/JDBC 자동 선택 |
| `arc-core/src/main/kotlin/com/arc/reactor/rag/impl/` | RAG 파이프라인 — 설정으로 제어 |
| `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/` | Spring Boot 자동 설정 — 그대로 사용 |

## 커스터마이징 예시

### Guard 단계 추가

```kotlin
@Component
class BusinessHoursGuard : GuardStage {
    override val stageName = "business-hours"
    override val order = 35  // InjectionDetection(30) 이후

    override suspend fun check(command: GuardCommand): GuardResult {
        val hour = LocalTime.now().hour
        if (hour < 9 || hour >= 18) {
            return GuardResult.Rejected(
                reason = "업무 시간(09-18시)에만 이용 가능합니다",
                category = RejectionCategory.UNAUTHORIZED,
                stage = stageName
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

### Hook 추가 (감사 로그)

```kotlin
@Component
class AuditHook : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info("User=${context.userId} prompt='${context.userPrompt}' " +
            "tools=${context.toolsUsed} success=${response.success}")
    }
}
```

### 에러 메시지 한국어화

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, _ ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
        AgentErrorCode.GUARD_REJECTED -> "요청이 거부되었습니다."
        else -> code.defaultMessage
    }
}
```

### PostgreSQL Memory 활성화

빌드/실행 시 `-Pdb=true`를 사용하고 datasource 설정(env 또는 `application.yml`)을 제공하면 됩니다.
코드 변경 없이 `DataSource` 빈이 감지되면 `JdbcMemoryStore`로 자동 전환됩니다.

### MCP 서버 연결

REST API로 MCP 서버를 등록합니다:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "description": "로컬 파일시스템 도구",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "autoConnect": true
  }'
```

> **참고:** MCP SDK 0.17.2는 Streamable HTTP 전송을 지원하지 않습니다. 원격 서버는 SSE를 사용하세요. 자세한 내용은 [MCP 통합 가이드](docs/ko/architecture/mcp.md)를 참고하세요.

## 인증 (Opt-in)

Arc Reactor에는 JWT 인증 시스템이 내장되어 있습니다. **기본적으로 비활성화** — 사용자별 세션 격리가 필요할 때만 활성화하세요.

```yaml
arc:
  reactor:
    auth:
      enabled: true                    # JWT 인증 활성화
      jwt-secret: ${JWT_SECRET}        # HMAC 서명 시크릿 (필수)
      jwt-expiration-ms: 86400000      # 토큰 유효기간 (기본: 24시간)
```

활성화 시:
- `POST /api/auth/register` — 회원가입
- `POST /api/auth/login` — JWT 토큰 발급
- `GET /api/auth/me` — 현재 사용자 프로필
- 다른 모든 엔드포인트에 `Authorization: Bearer <token>` 필요
- 세션이 사용자별로 자동 격리됨

커스텀 인증 (LDAP, SSO 등)을 사용하려면 `AuthProvider` 인터페이스를 구현하세요:

```kotlin
@Bean
fun authProvider(): AuthProvider = MyLdapAuthProvider()
```

## API 버전 계약

- 요청 헤더(선택): `X-Arc-Api-Version` (기본값: `v1`)
- 미지원 버전 요청 -> 표준 `ErrorResponse`와 함께 `400 Bad Request`
- 응답 헤더:
  - `X-Arc-Api-Version` (현재 버전)
  - `X-Arc-Api-Supported-Versions` (지원 버전 목록, 콤마 구분)
- 설정:
  - `arc.reactor.api-version.enabled=true` (기본값)
  - `arc.reactor.api-version.current=v1` (기본값)
  - `arc.reactor.api-version.supported=v1` (기본값)

## 설정 레퍼런스

```yaml
arc:
  reactor:
    max-tools-per-request: 20    # 요청당 최대 도구 수
    max-tool-calls: 10           # ReAct 루프 최대 도구 호출 횟수

    llm:
      temperature: 0.3
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000

    retry:
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000

    guard:
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      injection-detection-enabled: true

    boundaries:
      input-min-chars: 1
      input-max-chars: 5000

    rag:
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000

    cache:                           # 응답 캐싱 (opt-in)
      enabled: false
      max-size: 1000
      ttl-minutes: 60
      cacheable-temperature: 0.0     # 온도가 이 값 이하일 때만 캐시

    circuit-breaker:                 # 서킷 브레이커 (opt-in)
      enabled: false
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1

    fallback:                        # 우아한 성능 저하 (opt-in)
      enabled: false
      models: []                     # 예: [openai, anthropic]

    auth:
      enabled: false                 # JWT 인증 (opt-in)
      jwt-secret: ""                 # HMAC 시크릿 (활성화 시 필수)
      jwt-expiration-ms: 86400000    # 토큰 유효기간 (24시간)
```

## 프로젝트 구조

Arc Reactor는 멀티모듈 Gradle 프로젝트입니다:

- `arc-app/`: 실행 조립 모듈 (`:arc-app:bootRun`, `:arc-app:bootJar`)
- `arc-core/`: 에이전트 엔진/라이브러리 (guard, hook, tool, memory, RAG, MCP, 정책)
- `arc-web/`: REST API 컨트롤러 및 웹 통합
- `arc-slack/`: Slack 게이트웨이
- `arc-discord/`: Discord 게이트웨이
- `arc-line/`: LINE 게이트웨이
- `arc-error-report/`: 에러 리포팅 확장

핵심 구현 진입점:

- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorAutoConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt`

## 문서

- **[문서 홈](docs/ko/README.md)** — 패키지형 문서 인덱스
- [모듈 레이아웃 가이드](docs/ko/architecture/module-layout.md) — 현재 Gradle 모듈과 런타임 조립 구조
- [테스트/성능 가이드](docs/ko/engineering/testing-and-performance.md) — 로컬 피드백 루프 최적화
- [Slack 운영 런북](docs/ko/integrations/slack/ops-runbook.md) — 메트릭/부하테스트/백프레셔 운영
- [아키텍처 가이드](docs/ko/architecture/architecture.md) — 내부 구조와 에러 처리 체계
- [ReAct 루프 내부 구현](docs/ko/architecture/react-loop.md) — 핵심 실행 엔진, 도구 병렬 실행, 컨텍스트 트리밍, 재시도
- [Guard & Hook 시스템](docs/ko/architecture/guard-hook.md) — 5단계 보안 파이프라인, 4가지 생명주기 확장점
- [메모리 & RAG 파이프라인](docs/ko/architecture/memory-rag.md) — 대화 기록 관리, 4단계 검색 증강 생성
- [도구(Tool) 가이드](docs/ko/reference/tools.md) — 3가지 도구 유형, 등록 방법, MCP 연결
- [MCP 통합 가이드](docs/ko/architecture/mcp.md) — McpManager, STDIO/SSE 트랜스포트, 도구 동적 로드
- [설정 Quickstart](docs/ko/getting-started/configuration-quickstart.md) — 첫 실행용 최소 설정
- [설정 레퍼런스](docs/ko/getting-started/configuration.md) — 전체 YAML 설정, 자동 구성, 프로덕션 예시
- [관측성 & 메트릭](docs/ko/reference/metrics.md) — AgentMetrics 인터페이스, Micrometer 통합, 메트릭 포인트
- [복원력 가이드](docs/ko/architecture/resilience.md) — 서킷 브레이커, 재시도, 우아한 성능 저하
- [응답 처리](docs/ko/architecture/response-processing.md) — 응답 필터, 캐싱, 구조화 출력
- [데이터 모델 & API](docs/ko/reference/api-models.md) — AgentCommand/Result, 에러 처리, 메트릭, REST API
- [멀티에이전트 가이드](docs/ko/architecture/multi-agent.md) — Sequential / Parallel / Supervisor 패턴
- [Supervisor 패턴 Deep Dive](docs/ko/architecture/supervisor-pattern.md) — WorkerAgentTool 원리, 실제 사용법
- [배포 가이드](docs/ko/getting-started/deployment.md) — Docker, 환경 변수, 프로덕션 체크리스트
- [인증 가이드](docs/ko/governance/authentication.md) — JWT 인증, AuthProvider 커스터마이징, 세션 격리
- [세션 & 페르소나 가이드](docs/ko/architecture/session-management.md) — 세션 API, 페르소나 관리, 데이터 아키텍처
- [프롬프트 버저닝 가이드](docs/ko/governance/prompt-versioning.md) — 시스템 프롬프트 버전 관리, 배포, 롤백
- [기능 인벤토리](docs/ko/reference/feature-inventory.md) — 전체 기능 매트릭스, 데이터 아키텍처, DB 스키마

## 요구사항

- Java 21+
- Spring Boot 3.5+
- Spring AI 1.1+
- Kotlin 2.3+

## 라이선스

Apache License 2.0 - [LICENSE](LICENSE) 참조

## Acknowledgments

- [Spring AI](https://spring.io/projects/spring-ai) 기반
- [Model Context Protocol](https://modelcontextprotocol.io) 통합
