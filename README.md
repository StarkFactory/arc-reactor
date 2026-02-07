# Arc Reactor

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple.svg)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.2-orange.svg)](https://spring.io/projects/spring-ai)

**Fork해서 바로 쓰는 AI Agent 오픈소스 프로젝트**

Arc Reactor는 Spring AI 기반의 AI Agent 프로젝트입니다. Guard, Hook, Memory, RAG, MCP, ReAct 루프 등 프로덕션에 필요한 패턴이 이미 구조화되어 있습니다. Fork하고, 도구를 붙이고, 배포하세요.

> **이것은 라이브러리나 프레임워크가 아닙니다.** `implementation(...)` 으로 가져다 쓰는 게 아니라, Fork해서 내 프로젝트로 만드는 구조입니다.

## 시작하기

### 1. Fork & Clone

```bash
# GitHub에서 Fork 후
git clone https://github.com/<your-username>/arc-reactor.git
cd arc-reactor
```

### 2. LLM Provider 설정

`application.yml`에 사용할 LLM API 키를 설정합니다:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
    # 또는 Anthropic, Google Gemini, Vertex AI 등
```

`build.gradle.kts`에서 사용할 provider의 주석을 해제합니다:

```kotlin
// 기본: Google Gemini (이미 활성화됨)
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

// 필요한 것만 활성화
// compileOnly("org.springframework.ai:spring-ai-starter-model-openai")
// compileOnly("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### 3. 도구 만들기

`tool/` 패키지에 비즈니스 로직을 도구로 추가합니다:

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

1. `ArcReactorApplication.kt`의 `main()` 옆 ▶ 버튼 클릭
2. **Run/Debug Configuration → Environment variables**에 입력:
   ```
   GEMINI_API_KEY=AIzaSy_your_actual_key
   ```
3. Run

> PostgreSQL도 사용하려면: `GEMINI_API_KEY=your_key;SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor;SPRING_DATASOURCE_USERNAME=arc;SPRING_DATASOURCE_PASSWORD=arc`

#### CLI에서 실행

```bash
export GEMINI_API_KEY=your-api-key
./gradlew bootRun
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
  -d '{"message": "3 + 5는 얼마야?", "userId": "user-1"}'

# 스트리밍
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "안녕하세요", "userId": "user-1"}'
```

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
| **멀티에이전트** | Sequential / Parallel / Supervisor | DSL 빌더 API |

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

> 자세한 설명은 [멀티에이전트 가이드](docs/ko/multi-agent.md)를 참고하세요.

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
| `tool/` | **도구 추가** — `LocalTool` + `@Tool` 어노테이션으로 비즈니스 로직 연결 |
| `application.yml` | **설정 변경** — LLM provider, Guard 임계값, RAG on/off 등 |
| `guard/impl/` | **커스텀 Guard** — 비즈니스 규칙에 맞는 분류/권한 단계 구현 |
| `hook/` | **커스텀 Hook** — 감사 로그, 빌링, 알림 등 `@Component`로 추가 |
| `controller/` | **API 수정** — 인증 추가, 엔드포인트 변경 등 |

### 건드릴 필요 없는 곳 (이미 구조화됨)

| 파일/패키지 | 역할 |
|-------------|------|
| `agent/impl/SpringAiAgentExecutor.kt` | ReAct 루프, 재시도, 컨텍스트 관리 — 그대로 사용 |
| `guard/impl/GuardPipeline.kt` | Guard 파이프라인 오케스트레이션 — 그대로 사용 |
| `hook/HookExecutor.kt` | Hook 실행 엔진 — 그대로 사용 |
| `memory/` | 대화 기록 관리 — InMemory/JDBC 자동 선택 |
| `rag/impl/` | RAG 파이프라인 — 설정으로 제어 |
| `autoconfigure/` | Spring Boot 자동 설정 — 그대로 사용 |

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

`build.gradle.kts`에서 주석 해제 + `application.yml`에 DB 설정 추가만 하면 됩니다. 코드 변경 없이 `DataSource` 빈이 감지되면 자동으로 `JdbcMemoryStore`로 전환됩니다.

### MCP 서버 연결

```kotlin
@Service
class McpSetup(private val mcpManager: McpManager) {
    @PostConstruct
    fun setup() {
        mcpManager.register(McpServer(
            name = "filesystem",
            transportType = McpTransportType.STDIO,
            config = mapOf(
                "command" to "npx",
                "args" to listOf("-y", "@modelcontextprotocol/server-filesystem", "/data")
            )
        ))
        runBlocking { mcpManager.connect("filesystem") }
    }
}
```

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
      max-input-length: 10000
      injection-detection-enabled: true

    rag:
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    concurrency:
      max-concurrent-requests: 20
      request-timeout-ms: 30000
```

## 프로젝트 구조

```
src/main/kotlin/com/arc/reactor/
├── agent/                          # 에이전트 코어
│   ├── AgentExecutor.kt              → 인터페이스
│   ├── config/AgentProperties.kt     → 설정 (arc.reactor.*)
│   ├── model/AgentModels.kt          → AgentCommand, AgentResult
│   ├── impl/SpringAiAgentExecutor.kt → ReAct 루프 구현체
│   └── multi/                        → 멀티에이전트 (Sequential/Parallel/Supervisor)
│
├── guard/                          # 5단계 Guard
│   ├── Guard.kt                      → GuardStage 인터페이스들
│   ├── model/GuardModels.kt          → GuardCommand, GuardResult
│   └── impl/                         → 기본 구현체들
│
├── hook/                           # 라이프사이클 Hook
│   ├── Hook.kt                       → 4개 Hook 인터페이스
│   ├── HookExecutor.kt               → Hook 실행 엔진
│   └── model/HookModels.kt           → HookContext, HookResult
│
├── tool/                           # 도구 시스템 ← 여기에 도구 추가
│   ├── ToolCallback.kt               → 도구 추상화
│   ├── ToolSelector.kt               → 도구 선택 전략
│   ├── LocalTool.kt                  → @Tool 어노테이션 기반 도구
│   └── example/                      → 예시 (CalculatorTool, DateTimeTool)
│
├── memory/                         # 대화 메모리
│   ├── ConversationMemory.kt         → 인터페이스
│   ├── ConversationManager.kt        → 대화 히스토리 생명주기 관리
│   ├── MemoryStore.kt                → InMemory 구현
│   └── JdbcMemoryStore.kt            → PostgreSQL 구현
│
├── rag/                            # RAG 파이프라인
│   ├── RagPipeline.kt                → 4단계 인터페이스
│   └── impl/                         → 기본 구현체들
│
├── mcp/                            # MCP 프로토콜
│   ├── McpManager.kt                 → MCP 서버 관리
│   └── model/McpModels.kt            → McpServer, McpStatus
│
├── autoconfigure/                  # Spring Boot 자동 설정
│   └── ArcReactorAutoConfiguration.kt
│
├── controller/                     # REST API ← 필요시 수정
│   └── ChatController.kt
│
└── config/
    └── ChatClientConfig.kt
```

## 문서

- [아키텍처 가이드](docs/ko/architecture.md) — 내부 구조와 에러 처리 체계
- [ReAct 루프 내부 구현](docs/ko/react-loop.md) — 핵심 실행 엔진, 도구 병렬 실행, 컨텍스트 트리밍, 재시도
- [Guard & Hook 시스템](docs/ko/guard-hook.md) — 5단계 보안 파이프라인, 4가지 생명주기 확장점
- [메모리 & RAG 파이프라인](docs/ko/memory-rag.md) — 대화 기록 관리, 4단계 검색 증강 생성
- [도구(Tool) 가이드](docs/ko/tools.md) — 3가지 도구 유형, 등록 방법, MCP 연결
- [MCP 통합 가이드](docs/ko/mcp.md) — McpManager, STDIO/SSE 트랜스포트, 도구 동적 로드
- [설정 레퍼런스](docs/ko/configuration.md) — 전체 YAML 설정, 자동 구성, 프로덕션 예시
- [데이터 모델 & API](docs/ko/api-models.md) — AgentCommand/Result, 에러 처리, 메트릭, REST API
- [멀티에이전트 가이드](docs/ko/multi-agent.md) — Sequential / Parallel / Supervisor 패턴
- [Supervisor 패턴 Deep Dive](docs/ko/supervisor-pattern.md) — WorkerAgentTool 원리, 실제 사용법
- [배포 가이드](docs/ko/deployment.md) — Docker, 환경 변수, 프로덕션 체크리스트

## 요구사항

- Java 21+
- Spring Boot 3.5+
- Spring AI 1.1+
- Kotlin 2.3+

## 라이선스

Apache License 2.0 - [LICENSE](./LICENSE) 참조

## Acknowledgments

- [Spring AI](https://spring.io/projects/spring-ai) 기반
- [Model Context Protocol](https://modelcontextprotocol.io) 통합
