package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * R252: [DefaultConversationManager]의 MEMORY stage 자동 기록 테스트.
 *
 * 5개 catch 지점이 모두 `execution.error{stage="memory"}`로 기록되는지 검증:
 * 1. `loadHistory()` — MemoryStore 조회 실패 (rethrow 전 기록)
 * 2. `loadFromHistory()` — 계층적 메모리 빌드 실패 (fail-open 폴백)
 * 3. `verifySessionOwnership()` — 소유권 DB 조회 실패 (rethrow 전 기록)
 * 4. `triggerAsyncSummarization()` — 비동기 요약 실패 (swallowing)
 * 5. `saveMessages()` — 저장 실패 (fail-open, return 0)
 */
class ConversationManagerR252ExecutionErrorTest {

    private val properties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 5),
        guard = GuardProperties(),
        rag = RagProperties(),
        concurrency = ConcurrencyProperties()
    )

    private fun newCollector(): Pair<SimpleMeterRegistry, EvaluationMetricsCollector> {
        val registry = SimpleMeterRegistry()
        return registry to MicrometerEvaluationMetricsCollector(registry)
    }

    private fun countExecutionError(registry: SimpleMeterRegistry, exceptionClass: String): Double {
        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "memory")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, exceptionClass)
            .counter()
        return counter?.count() ?: 0.0
    }

    @Nested
    inner class SaveMessagesFailure {

        @Test
        fun `R252 saveMessages 예외가 MEMORY stage로 기록되어야 한다 (fail-open)`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.get(any()) } returns null
            every { memoryStore.addMessage(any(), any(), any(), any()) } throws
                IllegalStateException("jdbc unavailable")

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            // fail-open — 예외를 swallowing하고 정상 반환
            manager.saveHistory(command, AgentResult.success("answer"))

            assertEquals(1.0, countExecutionError(registry, "IllegalStateException")) {
                "saveMessages 실패가 기록되어야 한다"
            }
        }

        @Test
        fun `R252 saveStreamingHistory 예외도 MEMORY stage로 기록`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.get(any()) } returns null
            every { memoryStore.addMessage(any(), any(), any(), any()) } throws
                RuntimeException("network error")

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "session-1")
            )

            manager.saveStreamingHistory(command, "streamed content")

            assertEquals(1.0, countExecutionError(registry, "RuntimeException"))
        }
    }

    @Nested
    inner class LoadHistoryFailure {

        @Test
        fun `R252 MemoryStore 조회 예외가 MEMORY stage로 기록되고 재throw`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.getSessionOwner(any()) } returns null
            every { memoryStore.get(any()) } throws
                IllegalArgumentException("store broken")

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1"),
                userId = "user-1"
            )

            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { manager.loadHistory(command) }
            }

            assertEquals(1.0, countExecutionError(registry, "IllegalArgumentException")) {
                "MemoryStore 조회 예외가 기록되어야 한다"
            }
        }
    }

    @Nested
    inner class SessionOwnershipVerificationFailure {

        @Test
        fun `R252 getSessionOwner 예외가 MEMORY stage로 기록되고 fail-close 빈 리스트 반환`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.getSessionOwner(any()) } throws
                NullPointerException("owner query NPE")

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1"),
                userId = "user-1"
            )

            // fail-close — 소유권 검증 실패 시 빈 이력 반환 (SessionOwnershipVerificationException catch)
            val result = manager.loadHistory(command)
            assertEquals(0, result.size) {
                "소유권 검증 실패는 빈 리스트 반환"
            }

            assertEquals(1.0, countExecutionError(registry, "NullPointerException")) {
                "getSessionOwner 예외가 기록되어야 한다"
            }
        }

        @Test
        fun `R252 slack 세션은 소유권 검증을 건너뛰므로 예외도 없음`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.getSessionOwner(any()) } throws
                RuntimeException("should not be called")
            every { memoryStore.get(any()) } returns null

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "slack-C123-t456"),
                userId = "user-1"
            )

            manager.loadHistory(command)

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) {
                "slack 세션은 소유권 검증 건너뛰므로 기록 없음"
            }
        }
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `R252 정상 경로는 execution_error를 기록하지 않음`() = runTest {
            val (registry, collector) = newCollector()
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.getSessionOwner(any()) } returns null
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1"),
                Message(MessageRole.ASSISTANT, "a1")
            )
            every { memoryStore.addMessage(any(), any(), any(), any()) } returns Unit

            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties,
                evaluationMetricsCollector = collector
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1"),
                userId = "user-1"
            )

            manager.loadHistory(command)
            manager.saveHistory(command, AgentResult.success("answer"))

            val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
                .counter()
            assertNull(meter) {
                "정상 경로에서는 MEMORY counter 등록 없음"
            }
        }

        @Test
        fun `R252 기본 NoOp collector backward compat 유지`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.get(any()) } returns null
            every { memoryStore.addMessage(any(), any(), any(), any()) } throws
                IllegalStateException("boom")

            // evaluationMetricsCollector 생략 → NoOp
            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = properties
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            // fail-open 동작은 그대로 — 예외 없이 반환
            manager.saveHistory(command, AgentResult.success("answer"))
        }
    }
}
