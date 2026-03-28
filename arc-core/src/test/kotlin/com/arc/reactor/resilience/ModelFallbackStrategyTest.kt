package com.arc.reactor.resilience

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.resilience.impl.ModelFallbackStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * [ModelFallbackStrategy] 단위 테스트.
 *
 * null ChatResponse, 공백 콘텐츠, 다중 모델 순회, 메트릭 검증을 중점적으로 다룹니다.
 * 기본 성공/실패 시나리오는 [FallbackStrategyTest]에서 이미 다루므로 여기서는
 * 경계 케이스와 미검증 경로에 집중합니다.
 */
class ModelFallbackStrategyTest {

    private val command = AgentCommand(
        systemPrompt = "시스템 프롬프트",
        userPrompt = "사용자 입력"
    )

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun buildProvider(vararg pairs: Pair<String, () -> ChatResponse?>): ChatModelProvider {
        val provider = mockk<ChatModelProvider>()
        for ((model, responseFactory) in pairs) {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { provider.getChatClient(model) } returns chatClient
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
            every { requestSpec.call() } returns callSpec
            every { callSpec.chatResponse() } answers { responseFactory() }
        }
        return provider
    }

    private fun successResponse(content: String): ChatResponse =
        ChatResponse(listOf(Generation(AssistantMessage(content))))

    private fun nullTextResponse(): ChatResponse {
        val generation = mockk<Generation>()
        val assistantMsg = mockk<AssistantMessage>()
        every { assistantMsg.text } returns null
        every { generation.output } returns assistantMsg
        return ChatResponse(listOf(generation))
    }

    // ─── 중첩 테스트 그룹 ─────────────────────────────────────────────────────

    @Nested
    inner class NullChatResponse {

        @Test
        fun `chatResponse가 null이면 해당 모델은 실패로 처리된다`() = runTest {
            val provider = buildProvider("model-a" to { null })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "ChatResponse가 null이면 폴백 소진으로 null을 반환해야 한다" }
        }

        @Test
        fun `null 응답 모델 다음 모델이 성공하면 결과를 반환한다`() = runTest {
            val provider = buildProvider(
                "model-null" to { null },
                "model-ok" to { successResponse("복구됨") }
            )
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-null", "model-ok"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNotNull(result) { "두 번째 모델이 성공하면 결과를 반환해야 한다" }
            assertTrue(result!!.success) { "결과는 성공이어야 한다" }
            assertEquals("복구됨", result.content) { "두 번째 모델의 콘텐츠가 반환되어야 한다" }
        }
    }

    @Nested
    inner class WhitespaceContent {

        @Test
        fun `공백만 있는 응답은 실패로 처리된다`() = runTest {
            val provider = buildProvider("model-a" to { successResponse("   ") })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "공백만 있는 응답은 실패로 처리되어 null을 반환해야 한다" }
        }

        @Test
        fun `탭과 줄바꿈만 있는 응답도 실패로 처리된다`() = runTest {
            val provider = buildProvider("model-a" to { successResponse("\t\n\r") })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "탭/줄바꿈만 있는 응답은 실패로 처리되어야 한다" }
        }

        @Test
        fun `공백 응답 모델 이후 정상 모델이 성공하면 결과를 반환한다`() = runTest {
            val provider = buildProvider(
                "model-blank" to { successResponse("  ") },
                "model-ok" to { successResponse("정상 응답") }
            )
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-blank", "model-ok"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNotNull(result) { "두 번째 모델에서 결과를 반환해야 한다" }
            assertEquals("정상 응답", result!!.content) { "두 번째 모델의 콘텐츠가 반환되어야 한다" }
        }
    }

    @Nested
    inner class NullTextInGeneration {

        @Test
        fun `Generation의 text가 null이면 실패로 처리된다`() = runTest {
            val provider = buildProvider("model-a" to { nullTextResponse() })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "Generation text가 null이면 폴백 소진으로 null을 반환해야 한다" }
        }

        @Test
        fun `results 목록이 비어있는 ChatResponse는 실패로 처리된다`() = runTest {
            val emptyResponse: ChatResponse = ChatResponse(emptyList())
            val provider = buildProvider("model-a" to { emptyResponse })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "빈 결과 목록의 응답은 실패로 처리되어야 한다" }
        }
    }

    @Nested
    inner class ThreeModelChain {

        @Test
        fun `세 번째 모델만 성공하면 세 번째 모델의 결과를 반환한다`() = runTest {
            val provider = buildProvider(
                "model-1" to { null },
                "model-2" to { successResponse("") },
                "model-3" to { successResponse("세 번째 성공") }
            )
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-1", "model-2", "model-3"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNotNull(result) { "세 번째 모델에서 결과를 반환해야 한다" }
            assertTrue(result!!.success) { "결과는 성공이어야 한다" }
            assertEquals("세 번째 성공", result.content) { "세 번째 모델의 콘텐츠가 반환되어야 한다" }
        }

        @Test
        fun `세 모델 모두 실패하면 null을 반환한다`() = runTest {
            val provider = buildProvider(
                "model-1" to { throw RuntimeException("model-1 오류") },
                "model-2" to { null },
                "model-3" to { successResponse("  ") }
            )
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-1", "model-2", "model-3"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNull(result) { "모든 모델이 실패하면 null을 반환해야 한다" }
        }
    }

    @Nested
    inner class MetricsForEdgeCases {

        private val recordedModels = mutableListOf<String>()
        private val recordedSuccesses = mutableListOf<Boolean>()

        private val trackingMetrics = object : AgentMetrics {
            override fun recordExecution(result: AgentResult) {}
            override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
            override fun recordGuardRejection(stage: String, reason: String) {}
            override fun recordFallbackAttempt(model: String, success: Boolean) {
                recordedModels.add(model)
                recordedSuccesses.add(success)
            }
        }

        @Test
        fun `null ChatResponse는 실패 메트릭을 기록한다`() = runTest {
            val provider = buildProvider("model-null" to { null })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-null"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary fail"))

            assertEquals(1, recordedModels.size) { "메트릭이 1건 기록되어야 한다" }
            assertEquals("model-null", recordedModels[0]) { "모델 이름이 일치해야 한다" }
            assertFalse(recordedSuccesses[0]) { "null 응답은 실패로 기록되어야 한다" }
        }

        @Test
        fun `공백 응답은 실패 메트릭을 기록한다`() = runTest {
            val provider = buildProvider("model-blank" to { successResponse("  ") })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-blank"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary fail"))

            assertEquals(1, recordedModels.size) { "메트릭이 1건 기록되어야 한다" }
            assertFalse(recordedSuccesses[0]) { "공백 응답은 실패로 기록되어야 한다" }
        }

        @Test
        fun `예외, null 응답, 성공 순서로 메트릭이 올바르게 기록된다`() = runTest {
            val provider = buildProvider(
                "model-throw" to { throw RuntimeException("예외 발생") },
                "model-null" to { null },
                "model-success" to { successResponse("성공") }
            )
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-throw", "model-null", "model-success"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary fail"))

            assertEquals(3, recordedModels.size) { "세 모델 모두 메트릭이 기록되어야 한다" }
            assertFalse(recordedSuccesses[0]) { "첫 번째 모델(예외)은 실패로 기록되어야 한다" }
            assertFalse(recordedSuccesses[1]) { "두 번째 모델(null)은 실패로 기록되어야 한다" }
            assertTrue(recordedSuccesses[2]) { "세 번째 모델(성공)은 성공으로 기록되어야 한다" }
            assertEquals("model-throw", recordedModels[0]) { "첫 번째 모델 이름이 일치해야 한다" }
            assertEquals("model-null", recordedModels[1]) { "두 번째 모델 이름이 일치해야 한다" }
            assertEquals("model-success", recordedModels[2]) { "세 번째 모델 이름이 일치해야 한다" }
        }
    }

    @Nested
    inner class AgentResultShape {

        @Test
        fun `성공한 폴백 결과는 success=true이고 content가 비어있지 않다`() = runTest {
            val provider = buildProvider("model-ok" to { successResponse("폴백 응답입니다") })
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-ok"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNotNull(result) { "결과가 null이 아니어야 한다" }
            assertTrue(result!!.success) { "success 플래그가 true여야 한다" }
            assertFalse(result.content.isNullOrBlank()) { "content가 비어있으면 안 된다" }
            assertEquals("폴백 응답입니다", result.content) { "content가 정확히 일치해야 한다" }
        }
    }

    @Nested
    inner class ExceptionDuringProviderLookup {

        @Test
        fun `getChatClient 자체가 예외를 던지면 실패로 처리되고 다음 모델로 넘어간다`() = runTest {
            val provider = mockk<ChatModelProvider>()
            every { provider.getChatClient("bad-provider") } throws RuntimeException("Provider 초기화 실패")

            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { provider.getChatClient("good-provider") } returns chatClient
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
            every { requestSpec.call() } returns callSpec
            every { callSpec.chatResponse() } returns successResponse("백업 응답")

            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("bad-provider", "good-provider"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary fail"))

            assertNotNull(result) { "getChatClient 예외 후 다음 모델에서 결과를 반환해야 한다" }
            assertEquals("백업 응답", result!!.content) { "두 번째 모델의 콘텐츠가 반환되어야 한다" }

            verify(exactly = 1) { provider.getChatClient("bad-provider") }
            verify(exactly = 1) { provider.getChatClient("good-provider") }
        }
    }
}
