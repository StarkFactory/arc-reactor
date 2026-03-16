package com.arc.reactor.intent

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.intent.impl.LlmIntentClassifier
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentDefinition
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * LLM 기반 IntentClassifier에 대한 테스트.
 *
 * LLM을 활용한 인텐트 분류를 검증합니다.
 */
class LlmIntentClassifierTest {

    private lateinit var chatClient: ChatClient
    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var classifier: LlmIntentClassifier

    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var callResponseSpec: CallResponseSpec

    @BeforeEach
    fun setUp() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        callResponseSpec = mockk()
        registry = InMemoryIntentRegistry()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec

        classifier = LlmIntentClassifier(
            chatClient = chatClient,
            registry = registry,
            maxExamplesPerIntent = 3,
            maxConversationTurns = 2
        )
    }

    @Nested
    inner class SuccessfulClassification {

        @Test
        fun `intent from valid LLM response를 분류한다`() = runTest {
            registerIntents()
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.92}]}""")

            val result = classifier.classify("환불해주세요")
            assertFalse(result.isUnknown) { "Expected classified intent" }
            assertNotNull(result.primary) { "Primary intent should not be null" }
            assertEquals("refund", result.primary?.intentName) { "Expected refund intent" }
            assertEquals(0.92, result.primary?.confidence ?: 0.0, 0.01) { "Confidence should match" }
            assertEquals("llm", result.classifiedBy) { "Should be classified by llm" }
        }

        @Test
        fun `multi-intent response를 처리한다`() = runTest {
            registerIntents()
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.85},{"name":"shipping","confidence":0.72}]}""")

            val result = classifier.classify("환불하고 배송 상태도 알려줘")
            assertEquals("refund", result.primary!!.intentName) { "Primary should be refund" }
            assertEquals(1, result.secondary.size) { "Should have 1 secondary intent" }
            assertEquals("shipping", result.secondary[0].intentName) { "Secondary should be shipping" }
        }

        @Test
        fun `code fences from LLM response를 제거한다`() = runTest {
            registerIntents()
            mockLlmResponse("```json\n{\"intents\":[{\"name\":\"order\",\"confidence\":0.9}]}\n```")

            val result = classifier.classify("주문 확인")
            assertFalse(result.isUnknown) { "Should parse response with code fences" }
            assertEquals("order", result.primary!!.intentName) { "Expected order intent" }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `no intents are registered일 때 unknown를 반환한다`() = runTest {
            val result = classifier.classify("test")
            assertTrue(result.isUnknown) { "Should return unknown when no intents registered" }
            assertEquals("llm", result.classifiedBy) { "ClassifiedBy should still be 'llm'" }
        }

        @Test
        fun `unknown on malformed LLM response를 반환한다`() = runTest {
            registerIntents()
            mockLlmResponse("This is not JSON at all")

            val result = classifier.classify("test")
            assertTrue(result.isUnknown) { "Should return unknown for malformed response" }
        }

        @Test
        fun `LLM returns unknown intent일 때 unknown를 반환한다`() = runTest {
            registerIntents()
            mockLlmResponse("""{"intents":[{"name":"unknown","confidence":0.5}]}""")

            val result = classifier.classify("random text")
            assertTrue(result.isUnknown) { "Should return unknown when LLM says 'unknown'" }
        }

        @Test
        fun `LLM returns fenced unknown intent일 때 unknown를 반환한다`() = runTest {
            registerIntents()
            mockLlmResponse("```json\n{\"intents\":[{\"name\":\"unknown\",\"confidence\":0.9}]}\n```")

            val result = classifier.classify("random text")
            assertTrue(result.isUnknown) { "Should treat fenced unknown JSON as a valid unknown result" }
        }

        @Test
        fun `unknown on LLM exception를 반환한다`() = runTest {
            registerIntents()
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val result = classifier.classify("test")
            assertTrue(result.isUnknown) { "Should return unknown on LLM failure" }
        }
    }

    @Nested
    inner class PromptConstruction {

        @Test
        fun `prompt은(는) includes intent descriptions and examples`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Handle refund requests",
                examples = listOf("환불해주세요", "반품하고 싶어요", "돈 돌려줘", "extra example"),
                keywords = emptyList()
            ))
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.9}]}""")

            classifier.classify("환불")

            verify { requestSpec.user(match<String> {
                it.contains("refund") &&
                    it.contains("Handle refund requests") &&
                    it.contains("환불해주세요") &&
                    !it.contains("extra example") // maxExamplesPerIntent = 3
            }) }
        }

        @Test
        fun `provided일 때 prompt includes conversation history`() = runTest {
            registerIntents()
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.9}]}""")

            val context = ClassificationContext(
                conversationHistory = listOf(
                    Message(role = MessageRole.USER, content = "첫 번째 메시지 — should be truncated"),
                    Message(role = MessageRole.ASSISTANT, content = "첫 번째 응답"),
                    Message(role = MessageRole.USER, content = "주문 확인해주세요"),
                    Message(role = MessageRole.ASSISTANT, content = "주문 #1234입니다"),
                    Message(role = MessageRole.USER, content = "그거 환불해줘")
                )
            )

            classifier.classify("그래 해줘", context)

            // maxConversationTurns=2 -> takeLast(4) -> last 4 messages included
            verify { requestSpec.user(match<String> {
                it.contains("Recent conversation") &&
                    it.contains("주문 확인") &&
                    !it.contains("첫 번째 메시지") // first message truncated by takeLast(4)
            }) }
        }

        @Test
        fun `needed by llm classifier일 때 prompt lazily loads conversation history only`() = runTest {
            registerIntents()
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.9}]}""")
            val loadCount = AtomicInteger(0)

            val context = ClassificationContext(
                conversationHistoryLoader = {
                    loadCount.incrementAndGet()
                    listOf(
                        Message(role = MessageRole.USER, content = "주문 확인해주세요"),
                        Message(role = MessageRole.ASSISTANT, content = "주문 #1234입니다"),
                        Message(role = MessageRole.USER, content = "그거 환불해줘")
                    )
                }
            )

            classifier.classify("그래 해줘", context)

            assertEquals(1, loadCount.get()) { "Lazy history loader should be invoked exactly once" }
            verify { requestSpec.user(match<String> { it.contains("Recent conversation") && it.contains("그거 환불해줘") }) }
        }
    }

    @Nested
    inner class ResponseParsing {

        @Test
        fun `parseResponse은(는) handles valid JSON`() {
            val parsed = classifier.parseResponse("""{"intents":[{"name":"order","confidence":0.88}]}""")
            assertNotNull(parsed) { "Should parse valid JSON" }
            assertEquals("order", parsed!!.intents[0].intentName) { "Should extract intent name" }
        }

        @Test
        fun `parseResponse은(는) filters out zero-confidence intents`() {
            val parsed = classifier.parseResponse(
                """{"intents":[{"name":"order","confidence":0.88},{"name":"refund","confidence":0.0}]}"""
            )
            assertNotNull(parsed) { "Should parse valid JSON" }
            assertEquals(1, parsed!!.intents.size) { "Should filter out zero-confidence intents" }
        }

        @Test
        fun `parseResponse은(는) clamps confidence to 0-1 range`() {
            val parsed = classifier.parseResponse("""{"intents":[{"name":"order","confidence":1.5}]}""")
            assertNotNull(parsed) { "Should parse even with out-of-range confidence" }
            assertEquals(1.0, parsed!!.intents[0].confidence, 0.01) { "Confidence should be clamped to 1.0" }
        }

        @Test
        fun `parseResponse은(는) returns null for empty intents list`() {
            val parsed = classifier.parseResponse("""{"intents":[]}""")
            assertNotNull(parsed) { "Should parse empty intent payloads without treating them as malformed JSON" }
            assertTrue(parsed!!.intents.isEmpty()) { "Empty payload should produce no classified intents" }
        }

        @Test
        fun `parseResponse은(는) preserves unknown-only payload as parsed result`() {
            val parsed = classifier.parseResponse("""{"intents":[{"name":"unknown","confidence":0.9}]}""")
            assertNotNull(parsed) { "Unknown-only payload should still count as valid JSON" }
            assertTrue(parsed!!.intents.isEmpty()) { "Unknown intents should not be promoted into classified intents" }
        }
    }

    private fun registerIntents() {
        registry.save(IntentDefinition(name = "order", description = "Order inquiries"))
        registry.save(IntentDefinition(name = "refund", description = "Refund requests"))
        registry.save(IntentDefinition(name = "shipping", description = "Shipping tracking"))
    }

    private fun mockLlmResponse(content: String) {
        val chatResponse = ChatResponse(listOf(Generation(AssistantMessage(content))))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
