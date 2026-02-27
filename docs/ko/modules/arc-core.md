# arc-core

## 개요

`arc-core`는 Arc Reactor 프레임워크의 핵심 모듈입니다. ReAct (Reasoning + Acting) 에이전트 실행기, 5단계 Guard 파이프라인, Hook 라이프사이클 시스템, Tool 추상화 계층, Spring Boot 자동 구성(auto-configuration), 그리고 메모리, RAG, 멀티 에이전트 오케스트레이션, Prompt Lab, 복원력(resilience) 서브시스템이 포함되어 있습니다.

에이전트 런타임 자체가 필요한 경우 `arc-core`를 사용하세요. 이 모듈은 HTTP 레이어 없이도 독립적으로 동작하도록 설계되어 있으며, `arc-web`은 그 위에 REST/SSE 인터페이스를 추가합니다.

**요청 처리 흐름:**

```
Guard → Hook(BeforeAgentStart) → ReAct Loop (LLM ↔ Tool)* → Hook(AfterAgentComplete) → 응답
```

---

## 핵심 컴포넌트

| 클래스 | 역할 | 패키지 |
|---|---|---|
| `SpringAiAgentExecutor` | 핵심 ReAct 루프 — LLM 호출, Tool 디스패치, Guard/Hook 실행 관리 | `agent.impl` |
| `AgentExecutor` | 공개 인터페이스: `execute()`, `executeStream()` | `agent` |
| `AgentCommand` | 입력 DTO: 프롬프트, 모드, 모델, 메타데이터, 미디어 | `agent.model` |
| `AgentResult` | 출력 DTO: content, errorCode, toolsUsed, tokenUsage | `agent.model` |
| `AgentProperties` | 모든 `arc.reactor.*` 설정 프로퍼티 | `agent.config` |
| `ArcReactorAutoConfiguration` | Spring Boot 자동 구성 진입점 | `autoconfigure` |
| `RequestGuard` / `GuardStage` | 5단계 fail-close 가드 파이프라인 | `guard` |
| `AgentHook` / `BeforeAgentStartHook` / `AfterAgentCompleteHook` | 라이프사이클 확장 지점 (기본 fail-open) | `hook` |
| `ToolCallback` | 프레임워크 독립적인 Tool 인터페이스 | `tool` |
| `LocalTool` | Spring `@Tool` 어노테이션 기반 Tool 클래스 (스키마 자동 생성) | `tool` |
| `ConversationManager` | 세션 히스토리 로드/저장, 선택적 계층적 메모리 | `memory` |
| `MemoryStore` | 세션 영속성 인터페이스 (인메모리 또는 JDBC) | `memory` |
| `McpManager` | 동적 MCP 서버 라이프사이클 관리 | `mcp` |
| `RagPipeline` | 검색 증강 생성(RAG) — 쿼리 → 검색 → 재순위 | `rag` |
| `IntentClassifier` | 규칙 기반 + LLM 의도 분류 | `intent` |
| `CircuitBreaker` | LLM/MCP 장애 보호 | `resilience` |
| `OutputGuardPipeline` | 실행 후 응답 검증 (PII, 정규식, 동적 규칙) | `guard.output` |
| `ExperimentOrchestrator` | Prompt Lab — A/B 실험 실행기 | `promptlab` |
| `SupervisorOrchestrator` | 멀티 에이전트 supervisor 패턴 | `agent.multi` |
| `DynamicSchedulerService` | cron 기반 동적 MCP Tool 실행 | `scheduler` |

---

## 설정

모든 프로퍼티는 `arc.reactor` 접두사 아래에 바인딩됩니다. 기본값은 실제 `AgentProperties.kt` 및 `AgentPolicyAndFeatureProperties.kt` 소스 코드에서 직접 가져왔습니다.

### LLM (`arc.reactor.llm`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `default-provider` | `gemini` | 기본 LLM 공급자 이름 |
| `temperature` | `0.3` | 샘플링 온도 |
| `max-output-tokens` | `4096` | 응답당 최대 토큰 수 |
| `max-context-window-tokens` | `128000` | 메시지 트리밍을 위한 컨텍스트 윈도우 크기 |
| `max-conversation-turns` | `10` | 요청당 유지할 히스토리 턴 수 |
| `google-search-retrieval-enabled` | `false` | Gemini 검색 기반 그라운딩 (선택적 활성화) |

### Guard (`arc.reactor.guard`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `true` | 마스터 스위치 |
| `rate-limit-per-minute` | `10` | 사용자당 분당 최대 요청 수 |
| `rate-limit-per-hour` | `100` | 사용자당 시간당 최대 요청 수 |
| `injection-detection-enabled` | `true` | 프롬프트 인젝션 감지 |
| `unicode-normalization-enabled` | `true` | NFKC 정규화 + 제로폭 문자 제거 |
| `max-zero-width-ratio` | `0.1` | 제로폭 문자 거부 임계값 |
| `classification-enabled` | `false` | 규칙 기반 콘텐츠 분류 |
| `classification-llm-enabled` | `false` | LLM 기반 분류 (classification-enabled 필요) |
| `canary-token-enabled` | `false` | 시스템 프롬프트 유출 감지 |
| `tool-output-sanitization-enabled` | `false` | LLM 전달 전 Tool 출력 정제 |
| `audit-enabled` | `true` | Guard 감사 로그 |
| `topic-drift-enabled` | `false` | Crescendo 공격 방어 |

### 동시성 (`arc.reactor.concurrency`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `max-concurrent-requests` | `20` | 세마포어 허가 수 |
| `request-timeout-ms` | `30000` | 전체 요청 타임아웃 |
| `tool-call-timeout-ms` | `15000` | Tool 호출당 타임아웃 |

### 재시도 (`arc.reactor.retry`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `max-attempts` | `3` | 최대 재시도 횟수 |
| `initial-delay-ms` | `1000` | 첫 재시도 대기 시간 |
| `multiplier` | `2.0` | 지수 백오프 승수 |
| `max-delay-ms` | `10000` | 재시도 대기 시간 상한 |

### Tool 선택 (`arc.reactor.tool-selection`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `strategy` | `all` | `all`, `keyword`, 또는 `semantic` |
| `similarity-threshold` | `0.3` | 시맨틱 선택의 코사인 유사도 하한 |
| `max-results` | `10` | 시맨틱 선택이 반환하는 최대 Tool 수 |

### 한도 (`arc.reactor`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `max-tool-calls` | `10` | 요청당 최대 ReAct 루프 반복 횟수 |
| `max-tools-per-request` | `20` | 요청당 LLM에 노출되는 최대 Tool 수 |

### 경계 (`arc.reactor.boundaries`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `input-min-chars` | `1` | 최소 입력 길이 |
| `input-max-chars` | `5000` | 최대 입력 길이 |
| `system-prompt-max-chars` | `0` (비활성) | 최대 시스템 프롬프트 길이 |
| `output-min-chars` | `0` (비활성) | 최소 응답 길이 |
| `output-max-chars` | `0` (비활성) | 최대 응답 길이 |
| `output-min-violation-mode` | `WARN` | `WARN`, `RETRY_ONCE`, 또는 `FAIL` |

### RAG (`arc.reactor.rag`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 마스터 스위치 |
| `similarity-threshold` | `0.7` | 벡터 검색 임계값 |
| `top-k` | `10` | 검색할 결과 수 |
| `rerank-enabled` | `true` | 검색 후 재순위 |
| `query-transformer` | `passthrough` | `passthrough` 또는 `hyde` |
| `max-context-tokens` | `4000` | 주입할 컨텍스트의 최대 토큰 수 |

### 메모리 요약 (`arc.reactor.memory.summary`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 계층적 메모리 요약 |
| `trigger-message-count` | `20` | 요약이 시작되는 메시지 수 |
| `recent-message-count` | `10` | 원문 그대로 유지할 최근 메시지 수 |
| `llm-model` | `null` (기본 공급자) | 요약에 사용할 LLM |
| `max-narrative-tokens` | `500` | 서사 요약의 최대 토큰 수 |

### Circuit Breaker (`arc.reactor.circuit-breaker`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 선택적 활성화 |
| `failure-threshold` | `5` | 회로 개방 전 연속 실패 횟수 |
| `reset-timeout-ms` | `30000` | Open → Half-Open 전환 대기 시간 |
| `half-open-max-calls` | `1` | Half-Open 상태에서 허용하는 시험 호출 수 |

### 캐시 (`arc.reactor.cache`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 응답 캐싱 (선택적 활성화) |
| `max-size` | `1000` | 최대 캐시 항목 수 |
| `ttl-minutes` | `60` | 캐시 항목 유효 시간(분) |
| `cacheable-temperature` | `0.0` | 이 온도 이하에서만 캐싱 |

### 폴백 (`arc.reactor.fallback`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모델 폴백 체인 (선택적 활성화) |
| `models` | `[]` | 우선순위 순서의 폴백 공급자 목록 |

### Output Guard (`arc.reactor.output-guard`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 실행 후 응답 검증 |
| `pii-masking-enabled` | `true` | 내장 PII 마스킹 단계 |
| `dynamic-rules-enabled` | `true` | 관리자가 관리하는 정규식 규칙 |
| `dynamic-rules-refresh-ms` | `3000` | 규칙 캐시 갱신 주기 |

### Intent (`arc.reactor.intent`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 의도 분류 (선택적 활성화) |
| `confidence-threshold` | `0.6` | 프로파일 적용을 위한 최소 신뢰도 |
| `rule-confidence-threshold` | `0.8` | LLM 폴백 생략 기준 임계값 |
| `max-examples-per-intent` | `3` | 의도당 few-shot 예시 수 |
| `blocked-intents` | `[]` | 거부할 의도 이름 목록 |

### 승인 / HITL (`arc.reactor.approval`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | Human-in-the-Loop 승인 (선택적 활성화) |
| `timeout-ms` | `300000` | 승인 타임아웃 (5분) |
| `tool-names` | `[]` | 승인이 필요한 Tool 이름 목록 |

### Tool 정책 (`arc.reactor.tool-policy`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | Tool 정책 적용 |
| `write-tool-names` | `[]` | 부작용이 있는 Tool 이름 목록 |
| `deny-write-channels` | `[slack]` | 쓰기 Tool이 차단되는 채널 |
| `deny-write-message` | (기본 문자열) | Tool 거부 시 반환 메시지 |

### 멀티모달 (`arc.reactor.multimodal`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `true` | 파일 업로드 및 미디어 URL 지원 |
| `max-file-size-bytes` | `10485760` | 파일당 최대 크기 (10MB) |
| `max-files-per-request` | `5` | 멀티파트 요청당 최대 파일 수 |

### Prompt Lab (`arc.reactor.prompt-lab`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | Prompt Lab 기능 활성화 |
| `max-concurrent-experiments` | `3` | 병렬 실험 한도 |
| `max-queries-per-experiment` | `100` | 실험당 최대 테스트 쿼리 수 |
| `min-negative-feedback` | `5` | 자동 파이프라인 트리거 피드백 임계값 |
| `experiment-timeout-ms` | `600000` | 실험 실행 타임아웃 |

### 스케줄러 (`arc.reactor.scheduler`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 동적 cron 스케줄러 |
| `thread-pool-size` | `5` | 스케줄러 스레드 풀 크기 |

### MCP (`arc.reactor.mcp`)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `connection-timeout-ms` | `30000` | MCP 연결 타임아웃 |
| `security.max-tool-output-length` | `50000` | Tool 출력 최대 문자 수 |
| `reconnection.enabled` | `true` | 실패한 MCP 서버 자동 재연결 |
| `reconnection.max-attempts` | `5` | 재연결 최대 시도 횟수 |
| `reconnection.initial-delay-ms` | `5000` | 첫 재연결 대기 시간 |
| `reconnection.multiplier` | `2.0` | 백오프 승수 |
| `reconnection.max-delay-ms` | `60000` | 최대 재연결 대기 시간 |

---

## 확장 지점

### ToolCallback — 커스텀 Tool

직접 `ToolCallback`을 구현하면 스키마를 완전히 제어할 수 있습니다.

```kotlin
@Component
class OrderStatusTool(
    private val orderService: OrderService
) : ToolCallback {
    override val name = "get_order_status"
    override val description = "주문의 현재 상태를 반환합니다"
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "orderId": { "type": "string", "description": "주문 ID" }
          },
          "required": ["orderId"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        val orderId = arguments["orderId"] as? String
            ?: return "Error: orderId가 필요합니다"
        return orderService.getStatus(orderId) ?: "Error: 주문을 찾을 수 없습니다"
    }
}
```

핵심 규칙:
- 실패 시 `"Error: ..."` 문자열을 반환하세요. `call()` 내부에서 예외를 던지지 마세요.
- `override val timeoutMs: Long? = 5000`으로 이 Tool의 개별 타임아웃을 설정할 수 있습니다.

### LocalTool — Spring @Tool 어노테이션

메서드 시그니처에서 JSON 스키마를 자동 생성하려면 `LocalTool`을 사용하세요.

```kotlin
@Component
class WeatherTool(
    private val weatherApi: WeatherApiClient
) : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "도시의 현재 날씨를 조회합니다")
    fun getWeather(
        @ToolParam(description = "도시 이름") city: String
    ): String {
        return weatherApi.getCurrent(city) ?: "날씨 정보를 가져올 수 없습니다"
    }
}
```

### GuardStage — 커스텀 Guard 단계

내장 단계는 1–5번을 사용합니다. 커스텀 단계는 10번 이상을 사용하세요.

```kotlin
@Component
class BusinessHoursGuard : GuardStage {
    override val stageName = "BusinessHours"
    override val order = 15

    override suspend fun check(command: GuardCommand): GuardResult {
        val hour = java.time.LocalTime.now().hour
        if (hour < 9 || hour >= 18) {
            return GuardResult.Rejected(
                reason = "서비스는 오전 9시~오후 6시에만 이용 가능합니다",
                category = RejectionCategory.UNAUTHORIZED,
                stage = stageName
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

Guard는 항상 **fail-close**입니다. 어떤 단계에서 오류가 발생하면 요청이 차단됩니다.

### Hook — 라이프사이클 확장

Hook은 기본적으로 **fail-open**입니다(오류는 로그만 남기고 실행이 계속됩니다). 중요한 Hook에는 `failOnError = true`를 설정하세요.

```kotlin
@Component
class AuditHook(
    private val auditRepository: AuditRepository
) : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            auditRepository.save(
                AuditRecord(
                    runId = context.runId,
                    userId = context.userId,
                    success = response.success,
                    toolsUsed = response.toolsUsed,
                    durationMs = context.durationMs()
                )
            )
        } catch (e: CancellationException) {
            throw e  // CancellationException은 반드시 재던짐
        } catch (e: Exception) {
            logger.error(e) { "감사 로그 저장 실패" }
            // fail-open: 오류가 흡수되고 실행이 계속됨
        }
    }
}
```

Hook 순서 권장 범위:
- `1–99`: 중요/초기 Hook (인증, 보안)
- `100–199`: 표준 Hook (로깅, 감사)
- `200+`: 지연 Hook (정리, 알림)

사용 가능한 Hook 타입:

| 인터페이스 | 호출 시점 | 거부 가능 여부 |
|---|---|---|
| `BeforeAgentStartHook` | LLM 호출 전 | 예 (`HookResult.Reject`) |
| `BeforeToolCallHook` | 각 Tool 실행 전 | 예 (`HookResult.Reject`) |
| `AfterToolCallHook` | 각 Tool 실행 후 | 아니오 |
| `AfterAgentCompleteHook` | 에이전트 완료 후 (성공/실패 모두) | 아니오 |

### ErrorMessageResolver — 커스텀 오류 메시지

오류 메시지를 재정의합니다 (예: 다국어 지원).

```kotlin
@Bean
fun errorMessageResolver() = ErrorMessageResolver { code, originalMessage ->
    when (code) {
        AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다. 다시 시도해주세요."
        AgentErrorCode.GUARD_REJECTED -> "요청을 처리할 수 없습니다."
        else -> code.defaultMessage
    }
}
```

### 빈 재정의

`ArcReactorAutoConfiguration`이 등록하는 모든 빈은 `@ConditionalOnMissingBean`을 사용합니다. 직접 빈을 선언하면 기본 구현을 대체할 수 있습니다.

```kotlin
@Bean
fun memoryStore(): MemoryStore = MyCustomMemoryStore()

@Bean
fun requestGuard(stages: List<GuardStage>): RequestGuard = MyCustomGuard(stages)
```

---

## 코드 예시

### 기본 에이전트 실행

```kotlin
@Service
class ChatService(private val agentExecutor: AgentExecutor) {

    suspend fun chat(userId: String, sessionId: String, message: String): String {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = "당신은 도움이 되는 AI 어시스턴트입니다.",
                userPrompt = message,
                userId = userId,
                metadata = mapOf("sessionId" to sessionId)
            )
        )
        return if (result.success) result.content ?: "" else "오류: ${result.errorMessage}"
    }
}
```

### 스트리밍 에이전트 실행

```kotlin
@Service
class StreamingChatService(private val agentExecutor: AgentExecutor) {

    fun stream(userId: String, message: String): Flow<String> {
        val command = AgentCommand(
            systemPrompt = "당신은 도움이 되는 AI 어시스턴트입니다.",
            userPrompt = message,
            userId = userId
        )
        return agentExecutor.executeStream(command)
    }
}
```

### 구조화된 JSON 출력

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "텍스트에서 데이터를 추출하세요.",
        userPrompt = "홍길동, 30세, 서울 거주.",
        responseFormat = ResponseFormat.JSON,
        responseSchema = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" },
                "city": { "type": "string" }
              }
            }
        """.trimIndent()
    )
)
```

LLM이 유효하지 않은 JSON을 반환하면, 실행기는 자동으로 한 번 복구 호출을 시도하고 그래도 실패하면 `INVALID_RESPONSE`를 반환합니다.

---

## 자주 발생하는 실수

**CancellationException은 반드시 재던져야 합니다.** `Exception`을 광범위하게 잡는 모든 `suspend fun`은 먼저 `CancellationException`을 재던져야 합니다. 그렇지 않으면 Kotlin 구조적 동시성이 깨집니다.

```kotlin
// 올바른 방법
try {
    doWork()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.error(e) { "작업 실패" }
}
```

**ToolCallback은 오류를 문자열로 반환해야 하며, 예외를 던지면 안 됩니다.** Tool에서 발생한 예외는 `TOOL_ERROR`로 전파되어 ReAct 루프를 방해할 수 있습니다.

**maxToolCalls 적용.** 루프가 `maxToolCalls`에 도달하면, 실행기는 `activeTools = emptyList()`를 설정합니다. 이 시점에 로그만 남기고 Tool을 제거하지 않으면 LLM이 무한히 Tool을 호출하게 됩니다.

**AssistantMessage 생성자는 protected입니다.** 항상 빌더를 사용하세요.

```kotlin
AssistantMessage.builder().content("텍스트").toolCalls(calls).build()
```

**`application.yml`에 공급자 API 키를 빈 기본값으로 선언하지 마세요.** 환경 변수만 사용하세요: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`. 빈 기본값은 로그에 노출되고 실제 값을 빈 문자열로 덮어씁니다.

**코루틴 내에서 `.forEach {}`는 non-suspend 람다를 생성합니다.** suspend 함수를 반복 호출할 때는 `for` 루프를 사용하세요.

**MCP 서버는 REST API를 통해서만 등록됩니다.** `application.yml`에 MCP 서버 URL을 추가하거나 MCP 설정 클래스를 생성하지 마세요. `POST /api/mcp/servers`를 사용하세요.

**Guard는 fail-close이고, Hook은 fail-open입니다.** 보안 로직은 Hook이 아닌 Guard에 구현해야 합니다.
