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

/**
 * 스트리밍 경계 조건에 대한 테스트.
 *
 * 스트리밍 모드에서의 경계 상황을 검증합니다.
 */
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
        fun `짧은 스트리밍 출력이 에러 이벤트를 방출해야 한다`() = runTest {
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
        fun `스트리밍에서 RETRY_ONCE가 WARN으로 폴백해야 한다`() = runTest {
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

            // 원본 텍스트(재시도 없음)와 에러 이벤트를 포함해야 합니다
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
        fun `긴 스트리밍 출력이 에러 이벤트를 방출해야 한다`() = runTest {
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
