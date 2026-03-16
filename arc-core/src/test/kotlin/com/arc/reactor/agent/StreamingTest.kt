package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

/** Filters out internal stream markers (done, tool_start, etc.) to get only text content chunks. */
private fun List<String>.contentOnly(): List<String> = filterNot { StreamEventMarker.isMarker(it) }

/** Filters to only error markers. */
private fun List<String>.errorMarkersOnly(): List<String> = filter { StreamEventMarker.parse(it)?.first == "error" }

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

            assertEquals(listOf("Hello", " ", "World"), chunks.contentOnly(), "Content chunks should match")
        }

        @Test
        fun `should apply system prompt in streaming mode`() = runBlocking {
            val systemPromptSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemPromptSlot)) } returns fixture.requestSpec
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

            val capturedPrompt = systemPromptSlot.captured
            assertTrue(capturedPrompt.contains("You are helpful.")) {
                "Streaming mode should preserve the base system prompt. Prompt was: $capturedPrompt"
            }
            assertTrue(capturedPrompt.contains("[Grounding Rules]")) {
                "Streaming mode should include grounding rules. Prompt was: $capturedPrompt"
            }
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

            assertEquals(listOf("chunk"), chunks.contentOnly(), "Content chunks should match")
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

            assertTrue(chunks.contentOnly().isEmpty()) { "Empty stream should produce no content chunks but got: ${chunks.contentOnly()}" }
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

            assertEquals(listOf("OK"), chunks.contentOnly(), "Content chunks should match")
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

            // Should emit single error chunk (plus done marker)
            val errors = chunks.errorMarkersOnly()
            assertEquals(1, errors.size, "Should emit exactly one error marker")
            assertTrue(errors[0].contains("Blocked")) { "Error chunk should contain rejection reason, got: ${errors[0]}" }
        }

        @Test
        fun `should record GUARD_REJECTED in streaming metrics when guard rejects`() = runBlocking {
            val guard = mockk<RequestGuard>()
            val metrics = mockk<AgentMetrics>(relaxed = true)
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Blocked",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard,
                agentMetrics = metrics
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1")
            ).toList()

            io.mockk.verify(exactly = 1) {
                metrics.recordStreamingExecution(match {
                    !it.success &&
                        it.errorCode == AgentErrorCode.GUARD_REJECTED &&
                        it.errorMessage == "Blocked"
                })
            }
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

            val errors = chunks.errorMarkersOnly()
            assertEquals(1, errors.size, "Should emit exactly one error marker")
            assertTrue(errors[0].contains("Not allowed")) { "Error chunk should contain rejection reason, got: ${errors[0]}" }
        }

        @Test
        fun `should record HOOK_REJECTED in streaming metrics when before hook rejects`() = runBlocking {
            val hookExecutor = mockk<HookExecutor>(relaxed = true)
            val metrics = mockk<AgentMetrics>(relaxed = true)
            coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("Not allowed")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                hookExecutor = hookExecutor,
                agentMetrics = metrics
            )

            executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            io.mockk.verify(exactly = 1) {
                metrics.recordStreamingExecution(match {
                    !it.success &&
                        it.errorCode == AgentErrorCode.HOOK_REJECTED &&
                        it.errorMessage == "Not allowed"
                })
            }
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

            // Should emit at least one error marker
            val errors = chunks.errorMarkersOnly()
            assertTrue(errors.isNotEmpty()) { "Stream error should produce at least one error marker" }
            val errorPayload = StreamEventMarker.parse(errors.first())!!.second
            assertTrue(
                errorPayload.contains("error", ignoreCase = true) || errorPayload.contains("failed", ignoreCase = true),
                "Error payload should contain 'error' or 'failed', got: $errorPayload"
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

            val errors = chunks.errorMarkersOnly()
            assertEquals(1, errors.size) { "Should emit exactly one error marker" }
            val parsed = StreamEventMarker.parse(errors[0])
            assertNotNull(parsed) { "Guard rejection should be a typed error marker, got: ${errors[0]}" }
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

            val errors = chunks.errorMarkersOnly()
            assertEquals(1, errors.size) { "Should emit exactly one error marker" }
            val parsed = StreamEventMarker.parse(errors[0])
            assertNotNull(parsed) { "Hook rejection should be a typed error marker, got: ${errors[0]}" }
            assertEquals("error", parsed!!.first) { "Event type should be error" }
            assertEquals("Budget exceeded", parsed.second) { "Error payload should be rejection reason" }
        }
    }
}
