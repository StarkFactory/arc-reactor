package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec
import reactor.core.publisher.Flux

/**
 * TDD tests for Streaming support (Tier 1-5).
 *
 * Tests the AgentExecutor.executeStream() method which returns
 * a Kotlin Flow<String> from Spring AI's Flux<String> streaming API.
 */
class StreamingTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var streamResponseSpec: StreamResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        streamResponseSpec = mockk()
        properties = AgentProperties(
            llm = LlmProperties(),
            guard = GuardProperties(),
            rag = RagProperties(),
            concurrency = ConcurrencyProperties()
        )

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
        every { requestSpec.stream() } returns streamResponseSpec
    }

    @Nested
    inner class BasicStreaming {

        @Test
        fun `should return flow of string chunks`() = runBlocking {
            every { streamResponseSpec.content() } returns Flux.just("Hello", " ", "World")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertEquals(listOf("Hello", " ", "World"), chunks)
        }

        @Test
        fun `should apply system prompt in streaming mode`() = runBlocking {
            every { streamResponseSpec.content() } returns Flux.just("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hello")
            ).toList()

            io.mockk.verify { requestSpec.system("You are helpful.") }
        }

        @Test
        fun `should use STREAMING mode by default for executeStream`() = runBlocking {
            every { streamResponseSpec.content() } returns Flux.just("chunk")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            // executeStream should work regardless of the mode in command
            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = AgentMode.STREAMING
                )
            ).toList()

            assertEquals(listOf("chunk"), chunks)
        }

        @Test
        fun `should handle empty stream`() = runBlocking {
            every { streamResponseSpec.content() } returns Flux.empty()

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertTrue(chunks.isEmpty())
        }
    }

    @Nested
    inner class StreamingWithGuard {

        @Test
        fun `should run guard before streaming`() = runBlocking {
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT

            every { streamResponseSpec.content() } returns Flux.just("OK")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                guard = guard
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1")
            ).toList()

            assertEquals(listOf("OK"), chunks)
            coVerify { guard.guard(any()) }
        }

        @Test
        fun `should emit error when guard rejects`() = runBlocking {
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Blocked",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                guard = guard
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1")
            ).toList()

            // Should emit single error chunk
            assertEquals(1, chunks.size)
            assertTrue(chunks[0].contains("Blocked"))
        }
    }

    @Nested
    inner class StreamingWithHooks {

        @Test
        fun `should run before hook in streaming mode`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue

            every { streamResponseSpec.content() } returns Flux.just("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            coVerify { hookExecutor.executeBeforeAgentStart(any()) }
        }

        @Test
        fun `should reject when before hook rejects in streaming`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("Not allowed")

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertEquals(1, chunks.size)
            assertTrue(chunks[0].contains("Not allowed"))
        }
    }

    @Nested
    inner class StreamingErrorHandling {

        @Test
        fun `should handle stream error gracefully`() = runBlocking {
            every { streamResponseSpec.content() } returns Flux.error(RuntimeException("Stream failed"))

            val executor = SpringAiAgentExecutor(
                chatClient = chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Should emit error as last chunk
            assertTrue(chunks.isNotEmpty())
            assertTrue(chunks.last().contains("error", ignoreCase = true) ||
                       chunks.last().contains("failed", ignoreCase = true))
        }
    }
}
