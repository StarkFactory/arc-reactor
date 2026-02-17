package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.StreamEventMarker
import io.mockk.every
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class StreamingBoundaryTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    private fun propertiesWithBoundaries(
        outputMinChars: Int = 0,
        outputMaxChars: Int = 0,
        mode: OutputMinViolationMode = OutputMinViolationMode.WARN
    ): AgentProperties = AgentTestFixture.defaultProperties().copy(
        boundaries = BoundaryProperties(
            outputMinChars = outputMinChars,
            outputMaxChars = outputMaxChars,
            outputMinViolationMode = mode
        )
    )

    @Nested
    inner class OutputMinViolation {

        @Test
        fun `short streamed output emits error event`() = runTest {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("Hi")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 50, mode = OutputMinViolationMode.WARN
                )
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val errorEvents = chunks.filter { StreamEventMarker.isMarker(it) }
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }

            assertTrue(errorEvents.isNotEmpty()) {
                "Should emit error event for output min violation in streaming, got chunks: $chunks"
            }
            assertTrue(errorEvents.any { it.second.contains("Boundary violation [output_too_short]") }) {
                "Error event should use standardized boundary message, got: $errorEvents"
            }
        }

        @Test
        fun `RETRY_ONCE falls back to WARN in streaming`() = runTest {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("Hi")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 50, mode = OutputMinViolationMode.RETRY_ONCE
                )
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            // Should contain the original text (not retried) plus an error event
            assertTrue(chunks.any { it == "Hi" }) {
                "Should contain original streamed content"
            }
            val errorEvents = chunks.filter { StreamEventMarker.isMarker(it) }
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }
            assertTrue(errorEvents.isNotEmpty()) {
                "RETRY_ONCE should fall back to WARN with error event in streaming"
            }
        }
    }

    @Nested
    inner class OutputMaxViolation {

        @Test
        fun `long streamed output emits error event`() = runTest {
            every { fixture.streamResponseSpec.chatResponse() } returns Flux.just(
                AgentTestFixture.textChunk("a".repeat(200))
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(outputMaxChars = 50)
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            ).toList()

            val errorEvents = chunks.filter { StreamEventMarker.isMarker(it) }
                .mapNotNull { StreamEventMarker.parse(it) }
                .filter { it.first == "error" }

            assertTrue(errorEvents.isNotEmpty()) {
                "Should emit error event for output max violation in streaming"
            }
            assertTrue(errorEvents.any { it.second.contains("Boundary violation [output_too_long]") }) {
                "Error event should use standardized boundary message, got: $errorEvents"
            }
        }
    }
}
