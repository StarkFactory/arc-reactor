package com.arc.reactor.resilience

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.resilience.impl.ModelFallbackStrategy
import com.arc.reactor.resilience.impl.NoOpFallbackStrategy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class FallbackStrategyTest {

    private val command = AgentCommand(
        systemPrompt = "You are helpful",
        userPrompt = "Hello"
    )

    @Nested
    inner class NoOpFallbackStrategyTest {

        @Test
        fun `always returns null`() = runTest {
            val strategy = NoOpFallbackStrategy()

            val result = strategy.execute(command, RuntimeException("fail"))

            assertNull(result) { "NoOp strategy should always return null" }
        }
    }

    @Nested
    inner class ModelFallbackStrategyTest {

        @Test
        fun `first model success returns result`() = runTest {
            val provider = mockChatModelProvider("openai" to "Fallback response")
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("openai"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary failed"))

            assertNotNull(result) { "Should return fallback result" }
            assertTrue(result!!.success) { "Fallback result should be success" }
            assertEquals("Fallback response", result.content) { "Content should match fallback" }
        }

        @Test
        fun `first model fails second succeeds`() = runTest {
            val provider = mockk<ChatModelProvider>()
            mockFailingModel(provider, "bad-model")
            mockSuccessModel(provider, "good-model", "Recovered")

            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("bad-model", "good-model"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary failed"))

            assertNotNull(result) { "Should return fallback result from second model" }
            assertTrue(result!!.success) { "Fallback result should be success" }
            assertEquals("Recovered", result.content) { "Content should come from good-model" }
        }

        @Test
        fun `all models fail returns null`() = runTest {
            val provider = mockk<ChatModelProvider>()
            mockFailingModel(provider, "model-a")
            mockFailingModel(provider, "model-b")

            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a", "model-b"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary failed"))

            assertNull(result) { "Should return null when all fallback models fail" }
        }

        @Test
        fun `empty model list returns null`() = runTest {
            val provider = mockk<ChatModelProvider>()
            val strategy = ModelFallbackStrategy(
                fallbackModels = emptyList(),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary failed"))

            assertNull(result) { "Should return null for empty model list" }
        }

        @Test
        fun `blank response is treated as failure`() = runTest {
            val provider = mockChatModelProvider("openai" to "")
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("openai"),
                chatModelProvider = provider
            )

            val result = strategy.execute(command, RuntimeException("primary failed"))

            assertNull(result) { "Blank response should be treated as failure" }
        }
    }

    @Nested
    inner class MetricsRecording {

        private val recordedModels = mutableListOf<String>()
        private val recordedSuccesses = mutableListOf<Boolean>()
        private val trackingMetrics = object : com.arc.reactor.agent.metrics.AgentMetrics {
            override fun recordExecution(result: AgentResult) {}
            override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {}
            override fun recordGuardRejection(stage: String, reason: String) {}
            override fun recordFallbackAttempt(model: String, success: Boolean) {
                recordedModels.add(model)
                recordedSuccesses.add(success)
            }
        }

        @Test
        fun `should record success on first model`() = runTest {
            val provider = mockChatModelProvider("openai" to "Success")
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("openai"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary failed"))

            assertEquals(1, recordedModels.size) { "Should have 1 fallback record" }
            assertEquals("openai", recordedModels[0]) { "Model name should match" }
            assertTrue(recordedSuccesses[0]) { "Should record as success" }
        }

        @Test
        fun `should record failure then success across models`() = runTest {
            val provider = mockk<ChatModelProvider>()
            mockFailingModel(provider, "bad-model")
            mockSuccessModel(provider, "good-model", "Recovered")

            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("bad-model", "good-model"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary failed"))

            assertEquals(2, recordedModels.size) { "Should have 2 fallback records" }
            assertFalse(recordedSuccesses[0]) { "First model should be recorded as failure" }
            assertEquals("bad-model", recordedModels[0]) { "First model name should match" }
            assertTrue(recordedSuccesses[1]) { "Second model should be recorded as success" }
            assertEquals("good-model", recordedModels[1]) { "Second model name should match" }
        }

        @Test
        fun `should record all failures when all models fail`() = runTest {
            val provider = mockk<ChatModelProvider>()
            mockFailingModel(provider, "model-a")
            mockFailingModel(provider, "model-b")

            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("model-a", "model-b"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary failed"))

            assertEquals(2, recordedModels.size) { "Should have 2 fallback records" }
            assertTrue(recordedSuccesses.all { !it }) { "All records should be failures" }
        }

        @Test
        fun `should record failure for blank response`() = runTest {
            val provider = mockChatModelProvider("openai" to "")
            val strategy = ModelFallbackStrategy(
                fallbackModels = listOf("openai"),
                chatModelProvider = provider,
                agentMetrics = trackingMetrics
            )

            strategy.execute(command, RuntimeException("primary failed"))

            assertEquals(1, recordedModels.size) { "Should have 1 fallback record" }
            assertFalse(recordedSuccesses[0]) { "Blank response should be recorded as failure" }
        }
    }

    private fun mockChatModelProvider(vararg modelResponses: Pair<String, String>): ChatModelProvider {
        val provider = mockk<ChatModelProvider>()
        for ((model, content) in modelResponses) {
            mockSuccessModel(provider, model, content)
        }
        return provider
    }

    private fun mockSuccessModel(provider: ChatModelProvider, model: String, content: String) {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()

        every { provider.getChatClient(model) } returns chatClient
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec

        val assistantMsg = AssistantMessage(content)
        val chatResponse = ChatResponse(listOf(Generation(assistantMsg)))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }

    private fun mockFailingModel(provider: ChatModelProvider, model: String) {
        every { provider.getChatClient(model) } throws RuntimeException("Model $model unavailable")
    }
}
