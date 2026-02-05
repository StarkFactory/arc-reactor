# Arc Reactor

**경량 AI Agent Core Framework**

Jarvis에서 추출한 AI Agent 핵심 프레임워크. Spring AI 기반.

## 특징

- **ReAct Pattern**: Thought → Action → Observation 자율 실행 루프
- **5단계 Guard**: Rate Limit → Input Validation → Injection Detection → Classification → Permission
- **동적 Tool**: Local Tool + MCP (Model Context Protocol) 지원
- **Hook System**: 4개 라이프사이클 확장점
- **RAG Pipeline**: Query Transform → Retrieve → Rerank → Context Build
- **Memory**: Multi-turn 대화 컨텍스트 관리

## 설치

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.arc:arc-reactor:0.1.0")

    // LLM Provider (택 1) - Spring AI 1.1.x 새로운 네이밍
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    // 또는
    implementation("org.springframework.ai:spring-ai-starter-model-vertex-ai-gemini")
    // 또는
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
}
```

## 사용법

### 1. Tool 구현

```kotlin
@Component
class MyTools : LocalTool {
    override val category = DefaultToolCategory.SEARCH

    @Tool(description = "고객 정보를 검색합니다")
    fun searchCustomer(@ToolParam("고객ID") id: String): CustomerInfo {
        return customerRepository.findById(id)
    }

    @Tool(description = "이메일을 발송합니다")
    fun sendEmail(
        @ToolParam("받는사람") to: String,
        @ToolParam("제목") subject: String,
        @ToolParam("본문") body: String
    ): SendResult {
        return emailService.send(to, subject, body)
    }
}
```

### 2. Agent 실행

```kotlin
@Service
class MyAgentService(
    private val agentExecutor: AgentExecutor
) {
    suspend fun execute(request: String): AgentResult {
        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = "당신은 고객 서비스 전문가입니다...",
                userPrompt = request,
                mode = AgentMode.REACT
            )
        )
    }
}
```

### 3. Hook 확장

```kotlin
@Component
class AuditHook : AfterAgentCompleteHook {
    override val order = 100

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        logger.info { "Agent completed: ${response.summary()}" }
        auditService.log(context, response)
    }
}
```

### 4. Guard 커스터마이징

```kotlin
@Component
class MyClassificationStage : ClassificationStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // LLM 기반 분류 또는 규칙 기반 분류
        if (isOffTopic(command.text)) {
            return GuardResult.Rejected(
                reason = "업무 외 요청입니다",
                category = RejectionCategory.OFF_TOPIC
            )
        }
        return GuardResult.Allowed.DEFAULT
    }
}
```

## 설정

```yaml
arc:
  reactor:
    llm:
      temperature: 0.3
      max-output-tokens: 4096
      timeout-ms: 60000

    guard:
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      max-input-length: 10000
      injection-detection-enabled: true

    max-tools-per-request: 20
    max-tool-calls: 10
```

## 아키텍처

```
arc-reactor/
├── agent/          # Agent Core (ReAct Loop)
│   ├── AgentExecutor.kt
│   └── impl/SpringAiAgentExecutor.kt
├── tool/           # Tool System
│   ├── LocalTool.kt
│   ├── ToolSelector.kt
│   └── ToolCategory.kt
├── hook/           # Lifecycle Hooks
│   ├── Hook.kt (4 interfaces)
│   └── HookExecutor.kt
├── guard/          # 5-Stage Guardrail
│   ├── Guard.kt (5 interfaces)
│   └── impl/GuardPipeline.kt
├── rag/            # RAG Pipeline
│   ├── RagPipeline.kt
│   └── impl/DefaultRagPipeline.kt
├── memory/         # Conversation Memory
│   └── ConversationMemory.kt
├── mcp/            # MCP Support
│   └── McpManager.kt
└── autoconfigure/  # Spring Boot Auto Config
```

## 의존성

- Spring Boot 3.5.9
- Spring AI 1.1.2
- Kotlin 2.3.0
- Kotlin Coroutines 1.10.2

## 라이선스

Apache License 2.0
