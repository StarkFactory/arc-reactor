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

/**
 * 스트리밍 지원 (Tier 1-5)에 대한 TDD 테스트.
 *
 * Spring AI의 스트리밍 ChatResponse API에서
 * Kotlin Flow<String>을 반환하는 AgentExecutor.executeStream() 메서드를 테스트합니다.
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
        fun `return flow of string chunks해야 한다`() = runBlocking {
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
        fun `apply system prompt in streaming mode해야 한다`() = runBlocking {
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
        fun `executeStream에 대해 use STREAMING mode by default해야 한다`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("chunk")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // executeStream은(는) work regardless of the mode in command해야 합니다
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
        fun `handle empty stream해야 한다`() = runBlocking {
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
        fun `streaming 전에 run guard해야 한다`() = runBlocking {
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
        fun `guard rejects일 때 emit error해야 한다`() = runBlocking {
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

            // emit single error chunk해야 합니다
            assertEquals(1, chunks.size)
            assertTrue(chunks[0].contains("Blocked")) { "Error chunk should contain rejection reason, got: ${chunks[0]}" }
        }

        @Test
        fun `guard rejects일 때 record GUARD_REJECTED in streaming metrics해야 한다`() = runBlocking {
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
        fun `hook in streaming mode 전에 run해야 한다`() = runBlocking {
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
        fun `before hook rejects in streaming일 때 reject해야 한다`() = runBlocking {
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

        @Test
        fun `before hook rejects일 때 record HOOK_REJECTED in streaming metrics해야 한다`() = runBlocking {
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
        fun `handle stream error gracefully해야 한다`() = runBlocking {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.error(RuntimeException("Stream failed"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // emit error as last chunk해야 합니다
            assertTrue(chunks.isNotEmpty()) { "Stream error should produce at least one error chunk" }
            assertTrue(
                chunks.last().contains("error", ignoreCase = true) || chunks.last().contains("failed", ignoreCase = true),
                "Error chunk should contain 'error' or 'failed', got: ${chunks.last()}"
            )
        }

        @Test
        fun `emit typed error marker on stream failure해야 한다`() = runBlocking {
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
        fun `guard rejection은(는) emit typed error marker해야 한다`() = runBlocking {
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
        fun `hook rejection은(는) emit typed error marker해야 한다`() = runBlocking {
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
