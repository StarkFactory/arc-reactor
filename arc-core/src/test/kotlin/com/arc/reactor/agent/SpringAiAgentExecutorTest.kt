package com.arc.reactor.agent

import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.rag.RagPipeline
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * SpringAiAgentExecutor의 핵심 기능에 대한 테스트.
 *
 * 기본 실행, 가드/훅/메모리 통합, 오류 처리,
 * 취소 처리, 도구 설정을 검증합니다.
 */
class SpringAiAgentExecutorTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class BasicExecution {

        @Test
        fun `간단한 명령을 성공적으로 실행해야 한다`() = runTest {
            // 준비
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Hello! How can I help you?")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertSuccess()
            assertEquals("Hello! How can I help you?", result.content)
            assertTrue(result.durationMs >= 0) { "Expected non-negative durationMs, got: ${result.durationMs}" }
        }

        @Test
        fun `응답에서 토큰 사용량을 추출해야 한다`() = runTest {
            // 준비
            val usage = mockk<Usage>()
            every { usage.promptTokens } returns 100
            every { usage.completionTokens } returns 50
            every { usage.totalTokens } returns 150

            val metadata = mockk<ChatResponseMetadata>()
            every { metadata.usage } returns usage
            every { metadata.model } returns "test-model"

            val assistantMsg = AssistantMessage("Response")
            val generation = mockk<Generation>()
            every { generation.output } returns assistantMsg

            val chatResponse = mockk<ChatResponse>()
            every { chatResponse.metadata } returns metadata
            every { chatResponse.results } returns listOf(generation)

            every { fixture.callResponseSpec.content() } returns "Response"
            every { fixture.callResponseSpec.chatResponse() } returns chatResponse

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertSuccess()
            val tokenUsage = requireNotNull(result.tokenUsage)
            assertEquals(100, tokenUsage.promptTokens)
            assertEquals(50, tokenUsage.completionTokens)
            assertEquals(150, tokenUsage.totalTokens)
        }
    }

    @Nested
    inner class GuardIntegration {

        @Test
        fun `가드가 실패하면 거부해야 한다`() = runTest {
            // 준비
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Rate limit exceeded",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!",
                    userId = "user-123"
                )
            )

            // 검증
            result.assertFailure()
            assertEquals("Rate limit exceeded", result.errorMessage)
            result.assertErrorCode(AgentErrorCode.RATE_LIMITED)
        }

        @Test
        fun `userId가 null이면 anonymous userId로 가드를 실행해야 한다`() = runTest {
            // 준비
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!",
                    userId = null  // userId 없음 — 여전히 "anonymous"로 가드를 통과해야 합니다
                )
            )

            // 검증
            result.assertSuccess()
            coVerify(exactly = 1) { guard.guard(match { it.userId == "anonymous" }) }
        }
    }

    @Nested
    inner class HookIntegration {

        @Test
        fun `훅을 올바른 순서로 실행해야 한다`() = runTest {
            // 준비
            val executionOrder = mutableListOf<String>()

            val beforeHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add("before")
                    return HookResult.Continue
                }
            }

            val afterHook = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executionOrder.add("after")
                }
            }

            val hookExecutor = HookExecutor(
                beforeStartHooks = listOf(beforeHook),
                afterCompleteHooks = listOf(afterHook)
            )

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            assertEquals(listOf("before", "after"), executionOrder)
        }

        @Test
        fun `beforeAgentStart 훅이 거부하면 거부해야 한다`() = runTest {
            // 준비
            val rejectHook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    return HookResult.Reject("Not allowed")
                }
            }

            val hookExecutor = HookExecutor(beforeStartHooks = listOf(rejectHook))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertFailure()
            assertEquals("Not allowed", result.errorMessage)
            result.assertErrorCode(AgentErrorCode.HOOK_REJECTED)
        }

        @Test
        fun `afterAgentComplete 훅이 예외를 던져도 성공 결과를 보존해야 한다`() = runTest {
            // 준비
            val throwingAfterHook = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    throw RuntimeException("Hook explosion!")
                }
            }

            val hookExecutor = HookExecutor(afterCompleteHooks = listOf(throwingAfterHook))

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Successful response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증 - 훅이 예외를 던져도 결과는 성공이어야 합니다
            assertTrue(result.success, "Hook exception should not mask successful result")
            assertEquals("Successful response", result.content)
        }
    }

    @Nested
    inner class MemoryIntegration {

        @Test
        fun `대화를 메모리에 저장해야 한다`() = runTest {
            // 준비
            val memoryStore = InMemoryMemoryStore()
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Hello there!")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi!",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // 검증
            val memory = memoryStore.get("session-123")
            assertNotNull(memory) { "Memory for session-123 should exist after execute, got null" }
            assertEquals(2, memory!!.getHistory().size)
            assertEquals("Hi!", memory.getHistory()[0].content)
            assertEquals("Hello there!", memory.getHistory()[1].content)
        }

        @Test
        fun `메모리에서 대화 기록을 로드해야 한다`() = runTest {
            // 준비
            val memoryStore = InMemoryMemoryStore()
            memoryStore.addMessage("session-123", "user", "Previous question")
            memoryStore.addMessage("session-123", "assistant", "Previous answer")

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("New response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Follow up question",
                    metadata = mapOf("sessionId" to "session-123")
                )
            )

            // 검증
            coVerify { fixture.requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) }
        }

        @Test
        fun `String이 아닌 sessionId 타입으로도 메모리를 저장해야 한다`() = runTest {
            // 준비
            val memoryStore = InMemoryMemoryStore()
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            // 실행 - sessionId가 String이 아닌 Integer입니다
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi!",
                    metadata = mapOf("sessionId" to 12345)
                )
            )

            // 검증 - ?.toString()을 통해 저장되어야 합니다
            val memory = memoryStore.get("12345")
            assertNotNull(memory, "Memory should be saved even with non-String sessionId")
            assertEquals(2, memory!!.getHistory().size)
        }
    }

    @Nested
    inner class ErrorHandling {
        // 오류 변환 동작은 재시도/백오프가 필요하지 않으므로; 테스트를 빠르게 유지하기 위해 재시도를 비활성화합니다.
        private val noRetryProperties = properties.copy(
            retry = properties.retry.copy(
                maxAttempts = 1,
                initialDelayMs = 1,
                maxDelayMs = 1
            )
        )

        @Test
        fun `LLM 예외를 우아하게 처리해야 한다`() = runTest {
            // 준비
            every { fixture.requestSpec.call() } throws RuntimeException("LLM service unavailable")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertFailure()
            assertNotNull(result.errorMessage) { "LLM exception should produce an error message" }
        }

        @Test
        fun `속도 제한 오류를 변환해야 한다`() = runTest {
            // 준비
            every { fixture.requestSpec.call() } throws RuntimeException("Rate limit exceeded")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.RATE_LIMITED)
            assertTrue(result.errorMessage!!.contains("Rate limit exceeded")) {
                "Expected error to contain 'Rate limit exceeded', got: ${result.errorMessage}"
            }
        }

        @Test
        fun `타임아웃 오류를 변환해야 한다`() = runTest {
            // 준비
            every { fixture.requestSpec.call() } throws RuntimeException("Connection timeout")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.TIMEOUT)
            assertTrue(result.errorMessage!!.contains("Request timed out")) {
                "Expected error to contain 'Request timed out', got: ${result.errorMessage}"
            }
        }

        @Test
        fun `커스텀 오류 메시지 리졸버를 사용해야 한다`() = runTest {
            // 준비
            every { fixture.requestSpec.call() } throws RuntimeException("Rate limit exceeded")

            val koreanResolver = ErrorMessageResolver { code, _ ->
                when (code) {
                    AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다."
                    AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
                    else -> code.defaultMessage
                }
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = noRetryProperties,
                errorMessageResolver = koreanResolver
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증
            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.RATE_LIMITED)
            assertTrue(result.errorMessage!!.contains("요청 한도")) {
                "Expected Korean rate limit message containing '요청 한도', got: ${result.errorMessage}"
            }
        }
    }

    @Nested
    inner class CancellationHandling {

        @Test
        fun `RAG 조회에서 취소 예외를 다시 던져야 한다`() {
            // 준비
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } throws CancellationException("cancelled")
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("unused")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties.copy(rag = properties.rag.copy(enabled = true)),
                ragPipeline = ragPipeline
            )

            // 실행 + 검증
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    executor.execute(
                        AgentCommand(
                            systemPrompt = "You are helpful.",
                            userPrompt = "사내 문서에서 찾아줘",
                            metadata = mapOf("ragRequired" to true)
                        )
                    )
                }
            }
        }
    }

    @Nested
    inner class ToolConfiguration {

        @Test
        fun `가용 시 MCP 도구를 포함해야 한다`() = runTest {
            // 준비
            val mcpTool = object : com.arc.reactor.tool.ToolCallback {
                override val name = "mcp-tool"
                override val description = "MCP Tool"
                override suspend fun call(arguments: Map<String, Any?>) = "result"
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                mcpToolCallbacks = { listOf(mcpTool) }
            )

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증 - MCP ToolCallback은 ArcToolCallbackAdapter로 래핑되어야 합니다
            // .toolCallbacks()를 통해 전달되어야 합니다 (.tools()는 @Tool 어노테이션을 기대하므로 사용하지 않음)
            coVerify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `maxToolsPerRequest 제한을 준수해야 한다`() = runTest {
            // 준비
            val manyTools = (1..30).map { i ->
                object : com.arc.reactor.tool.ToolCallback {
                    override val name = "tool-$i"
                    override val description = "Tool $i"
                    override suspend fun call(arguments: Map<String, Any?>) = "result"
                }
            }
            val limitedProperties = properties.copy(maxToolsPerRequest = 5)

            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = limitedProperties,
                mcpToolCallbacks = { manyTools }
            )

            // 실행
            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!"
                )
            )

            // 검증 - toolCallbacks는 제한된 도구로 호출되어야 합니다 (ToolCallback 기반)
            coVerify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `잘못된 컨텍스트 윈도우 설정을 거부해야 한다`() {
            // maxContextWindowTokens < maxOutputTokens이면 예외가 발생해야 합니다
            assertThrows(IllegalArgumentException::class.java) {
                SpringAiAgentExecutor(
                    chatClient = fixture.chatClient,
                    properties = properties.copy(
                        llm = LlmProperties(
                            maxContextWindowTokens = 1000,
                            maxOutputTokens = 2000
                        )
                    )
                )
            }
        }
    }
}
