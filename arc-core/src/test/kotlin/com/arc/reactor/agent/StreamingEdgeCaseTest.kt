package com.arc.reactor.agent

import com.arc.reactor.agent.AgentTestFixture.Companion.textChunk
import com.arc.reactor.agent.AgentTestFixture.Companion.toolCallChunk
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
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
 * 스트리밍 엣지 케이스 테스트.
 *
 * 대상: 응답 형식 거부, 메모리 엣지 케이스,
 * 스트리밍 중 도구 타임아웃, 동시성 적용.
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
        fun `스트리밍이 JSON 응답 형식을 거부해야 한다`() = runBlocking {
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

            // 오류는 타입이 지정된 에러 마커로 방출되어야 합니다
            val hasErrorMarker = chunks.any { StreamEventMarker.parse(it)?.first == "error" }
            assertTrue(hasErrorMarker) {
                "Should emit typed error marker for JSON format, got: $chunks"
            }
            val errorPayload = chunks.mapNotNull { StreamEventMarker.parse(it) }
                .first { it.first == "error" }.second
            assertTrue(errorPayload.contains("not supported in streaming")) {
                "Error should mention streaming incompatibility, got: $errorPayload"
            }
        }

        @Test
        fun `스트리밍이 YAML 응답 형식을 거부해야 한다`() = runBlocking {
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

            // 오류는 타입이 지정된 에러 마커로 방출되어야 합니다
            val hasErrorMarker = chunks.any { StreamEventMarker.parse(it)?.first == "error" }
            assertTrue(hasErrorMarker) {
                "Should emit typed error marker for YAML format, got: $chunks"
            }
            val errorPayload = chunks.mapNotNull { StreamEventMarker.parse(it) }
                .first { it.first == "error" }.second
            assertTrue(errorPayload.contains("not supported in streaming")) {
                "Error should mention streaming incompatibility, got: $errorPayload"
            }
        }
    }

    @Nested
    inner class MemoryEdgeCases {

        @Test
        fun `스트리밍이 성공 시 메모리를 저장해야 한다`() = runBlocking {
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
        fun `스트리밍이 sessionId 없이 작동해야 한다`() = runBlocking {
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
                    // 메타데이터에 sessionId 없음
                )
            ).toList()

            assertTrue(chunks.isNotEmpty()) { "Stream should complete without sessionId" }
            assertTrue(chunks.joinToString("").contains("Response")) {
                "Stream should contain expected content"
            }
        }

        @Test
        fun `스트리밍이 memoryStore 저장 실패를 우아하게 처리해야 한다`() = runBlocking {
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

            // 예외를 던지면 안 됩니다
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
        fun `스트리밍이 도구 타임아웃을 우아하게 처리해야 한다`() = runBlocking {
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "slow-tool", """{}""")

            every { fixture.streamResponseSpec.chatResponse() } returnsMany listOf(
                Flux.just(toolCallChunk(listOf(toolCall))),
                Flux.just(textChunk("Final answer after timeout"))
            )

            val slowTool = AgentTestFixture.delayingToolCallback(
                name = "slow-tool",
                delayMs = 300,  // 긴 실제 시간 대기를 피하면서 타임아웃 이상으로 유지
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
                        toolCallTimeoutMs = 50  // 매우 짧은 타임아웃
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
            // 도구는 타임아웃되어야 하지만 스트림은 계속되어야 합니다
            assertTrue(output.isNotEmpty()) {
                "Stream should produce output even when tool times out"
            }
        }
    }

    @Nested
    inner class ConcurrencyEnforcement {

        @Test
        fun `스트리밍이 동시성 세마포어를 적용해야 한다`() = runBlocking {
            val concurrentCount = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)

            every { fixture.streamResponseSpec.chatResponse() } answers {
                val current = concurrentCount.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                // 잠시 후 반환합니다
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
                        maxConcurrentRequests = 1,  // 한 번에 1개만
                        requestTimeoutMs = 30000,
                        toolCallTimeoutMs = 15000
                    )
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
            )

            // 2개의 스트리밍 요청을 병렬로 시작합니다
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

            // 세마포어=1이면 최대 동시 실행 수는 1이어야 합니다
            assertTrue(maxConcurrent.get() <= 1) {
                "Max concurrent streams should be <=1 with semaphore=1, got: ${maxConcurrent.get()}"
            }
        }

        @Test
        fun `스트리밍이 요청 타임아웃을 적용해야 한다`() = runBlocking {
            // 오래 걸리는 스트림
            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("start"))
                    .concatWith(Flux.never())  // 완료되지 않음

            val props = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = it.rag,
                    concurrency = ConcurrencyProperties(
                        maxConcurrentRequests = 20,
                        requestTimeoutMs = 200,  // 매우 짧은 타임아웃
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

            // 타임아웃 발생 후 에러 마커를 방출해야 합니다
            val hasContent = chunks.any { !StreamEventMarker.isMarker(it) && it.contains("start") }
            val hasErrorMarker = chunks.any { StreamEventMarker.parse(it)?.first == "error" }
            assertTrue(hasContent || hasErrorMarker) {
                "Stream should either emit partial content or typed error marker, got: $chunks"
            }
        }
    }
}
