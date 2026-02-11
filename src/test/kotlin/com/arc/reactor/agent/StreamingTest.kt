package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
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
import reactor.core.publisher.Flux

/**
 * TDD tests for Streaming support (Tier 1-5).
 *
 * Tests the AgentExecutor.executeStream() method which returns
 * a Kotlin Flow<String> from Spring AI's streaming ChatResponse API.
 */
class StreamingTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class BasicStreaming {

        @Test
        fun `should return flow of string chunks`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("Hello"),
                AgentTestFixture.textChunk(" "),
                AgentTestFixture.textChunk("World")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertEquals(listOf("Hello", " ", "World"), chunks)
        }

        @Test
        fun `should apply system prompt in streaming mode`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("Response")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hello")
            ).toList()

            io.mockk.verify { fixture.requestSpec.system("You are helpful.") }
        }

        @Test
        fun `should use STREAMING mode by default for executeStream`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("chunk")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
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
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.empty()

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertTrue(chunks.isEmpty()) { "Empty stream should produce no chunks but got: $chunks" }
        }
    }

    @Nested
    inner class StreamingWithGuard {

        @Test
        fun `should run guard before streaming`() = runBlocking {
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT

            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("OK")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
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
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1")
            ).toList()

            // Should emit single error chunk
            assertEquals(1, chunks.size)
            assertTrue(chunks[0].contains("Blocked")) { "Error chunk should contain rejection reason, got: ${chunks[0]}" }
        }
    }

    @Nested
    inner class StreamingWithHooks {

        @Test
        fun `should run before hook in streaming mode`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Continue

            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("Response")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
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
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertEquals(1, chunks.size)
            assertTrue(chunks[0].contains("Not allowed")) { "Error chunk should contain rejection reason, got: ${chunks[0]}" }
        }
    }

    @Nested
    inner class StreamingErrorHandling {

        @Test
        fun `should handle stream error gracefully`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.error(RuntimeException("Stream failed"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Should emit error as last chunk
            assertTrue(chunks.isNotEmpty()) { "Stream error should produce at least one error chunk" }
            assertTrue(
                chunks.last().contains("error", ignoreCase = true) || chunks.last().contains("failed", ignoreCase = true),
                "Error chunk should contain 'error' or 'failed', got: ${chunks.last()}"
            )
        }

        @Test
        fun `should emit typed error marker on stream failure`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.error(RuntimeException("LLM unavailable"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val errorMarkers = chunks.mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorMarkers.isNotEmpty()) {
                "Should emit at least one typed error marker, got chunks: $chunks"
            }
        }

        @Test
        fun `guard rejection should emit typed error marker`() = runBlocking {
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Rate limited",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1")
            ).toList()

            assertEquals(1, chunks.size) { "Should emit exactly one error chunk" }
            val parsed = StreamEventMarker.parse(chunks[0])
            assertNotNull(parsed) { "Guard rejection should be a typed error marker, got: ${chunks[0]}" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("Rate limited", parsed.second) { "Error payload should be rejection reason" }
        }

        @Test
        fun `hook rejection should emit typed error marker`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("Budget exceeded")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            assertEquals(1, chunks.size) { "Should emit exactly one error chunk" }
            val parsed = StreamEventMarker.parse(chunks[0])
            assertNotNull(parsed) { "Hook rejection should be a typed error marker, got: ${chunks[0]}" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("Budget exceeded", parsed.second) { "Error payload should be rejection reason" }
        }
    }
}
