package com.arc.reactor.agent

import com.arc.reactor.agent.AgentTestFixture.Companion.textChunk
import com.arc.reactor.agent.AgentTestFixture.Companion.toolCallChunk
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.memory.MemoryStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streaming edge case tests.
 *
 * Covers: response format rejection, memory edge cases,
 * tool timeout during streaming, and concurrency enforcement.
 */
class StreamingEdgeCaseTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class ResponseFormatRejection {

        @Test
        fun `streaming should reject JSON response format`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("should not stream"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Give JSON",
                    responseFormat = ResponseFormat.JSON
                )
            ).toList()

            val output = chunks.joinToString("")
            assertTrue(output.contains("[error]")) {
                "Should emit error marker for JSON format"
            }
            assertTrue(output.contains("not supported in streaming")) {
                "Error should mention streaming incompatibility, got: $output"
            }
        }

        @Test
        fun `streaming should reject YAML response format`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("should not stream"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Give YAML",
                    responseFormat = ResponseFormat.YAML
                )
            ).toList()

            val output = chunks.joinToString("")
            assertTrue(output.contains("[error]")) {
                "Should emit error marker for YAML format"
            }
            assertTrue(output.contains("not supported in streaming")) {
                "Error should mention streaming incompatibility, got: $output"
            }
        }
    }

    @Nested
    inner class MemoryEdgeCases {

        @Test
        fun `streaming should save memory on success`() = runBlocking {
            val memoryStore = InMemoryMemoryStore()

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Hello!"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore
            )

            executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hi",
                    metadata = mapOf("sessionId" to "stream-session")
                )
            ).toList()

            val memory = memoryStore.get("stream-session")
            assertNotNull(memory) { "Memory should be saved for session" }
            assertTrue(memory!!.getHistory().isNotEmpty()) { "History should have entries" }
        }

        @Test
        fun `streaming should work without sessionId`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "No session"
                    // No sessionId in metadata
                )
            ).toList()

            assertTrue(chunks.isNotEmpty()) { "Stream should complete without sessionId" }
            assertTrue(chunks.joinToString("").contains("Response")) {
                "Stream should contain expected content"
            }
        }

        @Test
        fun `streaming should handle memoryStore save failure gracefully`() = runBlocking {
            val failingStore = mockk<MemoryStore>(relaxed = true)
            every { failingStore.addMessage(any(), any(), any(), any()) } throws
                RuntimeException("DB write failed")

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response despite store failure"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = failingStore
            )

            // Should not throw
            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Test",
                    metadata = mapOf("sessionId" to "fail-session")
                )
            ).toList()

            assertTrue(chunks.isNotEmpty()) {
                "Stream should complete even when memory save fails"
            }
        }
    }

    @Nested
    inner class ToolTimeoutDuringStreaming {

        @Test
        fun `streaming should handle tool timeout gracefully`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "slow-tool", """{}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Final answer after timeout"))
            )

            val slowTool = AgentTestFixture.delayingToolCallback(
                name = "slow-tool",
                delayMs = 5000, // 5 seconds, exceeds timeout
                result = "should not return"
            )

            val props = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = it.rag,
                    concurrency = ConcurrencyProperties(
                        maxConcurrentRequests = 20,
                        requestTimeoutMs = 30000,
                        toolCallTimeoutMs = 100 // Very short timeout
                    )
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                toolCallbacks = listOf(slowTool)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Use tool")
            ).toList()

            val output = chunks.joinToString("")
            // Tool should timeout but stream should continue
            assertTrue(output.isNotEmpty()) {
                "Stream should produce output even when tool times out"
            }
        }
    }

    @Nested
    inner class ConcurrencyEnforcement {

        @Test
        fun `streaming should enforce concurrency semaphore`() = runBlocking {
            val concurrentCount = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)

            every { fixture.streamResponseSpec.chatResponse() } answers {
                val current = concurrentCount.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                // Return after a brief moment
                Flux.just(textChunk("Response")).doFinally {
                    concurrentCount.decrementAndGet()
                }
            }

            val props = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = it.rag,
                    concurrency = ConcurrencyProperties(
                        maxConcurrentRequests = 1, // Only 1 at a time
                        requestTimeoutMs = 30000,
                        toolCallTimeoutMs = 15000
                    )
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
            )

            // Launch 2 streaming requests in parallel
            coroutineScope {
                val jobs = (1..2).map {
                    async {
                        executor.executeStream(
                            AgentCommand(systemPrompt = "Test", userPrompt = "Request $it")
                        ).toList()
                    }
                }
                jobs.awaitAll()
            }

            // With semaphore=1, max concurrent should be 1
            assertTrue(maxConcurrent.get() <= 1) {
                "Max concurrent streams should be <=1 with semaphore=1, got: ${maxConcurrent.get()}"
            }
        }

        @Test
        fun `streaming should enforce request timeout`() = runBlocking {
            // Stream that takes a long time
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("start"))
                    .concatWith(Flux.never()) // Never completes

            val props = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = it.rag,
                    concurrency = ConcurrencyProperties(
                        maxConcurrentRequests = 20,
                        requestTimeoutMs = 200, // Very short timeout
                        toolCallTimeoutMs = 15000
                    )
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Slow request")
            ).toList()

            val output = chunks.joinToString("")
            // Should timeout and emit error
            assertTrue(output.contains("start") || output.contains("[error]") || output.contains("timed out")) {
                "Stream should either emit partial content or timeout error, got: $output"
            }
        }
    }
}
