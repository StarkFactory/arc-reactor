package com.arc.reactor.intent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.intent.impl.CompositeIntentClassifier
import com.arc.reactor.intent.impl.LlmIntentClassifier
import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import io.mockk.every
import io.mockk.mockk
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
 * Integration test — verifies the full intent pipeline with real components.
 *
 * Components wired together (no mocks except LLM):
 * - InMemoryIntentRegistry (real)
 * - RuleBasedIntentClassifier (real)
 * - LlmIntentClassifier (LLM mocked — no API key needed)
 * - CompositeIntentClassifier (real)
 * - IntentResolver (real)
 *
 * Tests the complete flow: register intents -> classify input -> resolve profile -> apply to AgentCommand.
 */
class IntentIntegrationTest {

    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var resolver: IntentResolver

    // LLM mock — the only mock in the pipeline
    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var callResponseSpec: CallResponseSpec

    @BeforeEach
    fun setUp() {
        registry = InMemoryIntentRegistry()

        // LLM mock setup
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        callResponseSpec = mockk()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec

        val ruleClassifier = RuleBasedIntentClassifier(registry)
        val llmClassifier = LlmIntentClassifier(
            chatClient = chatClient,
            registry = registry,
            maxExamplesPerIntent = 3,
            maxConversationTurns = 2
        )
        val composite = CompositeIntentClassifier(
            ruleClassifier = ruleClassifier,
            llmClassifier = llmClassifier,
            ruleConfidenceThreshold = 0.8
        )

        resolver = IntentResolver(
            classifier = composite,
            registry = registry,
            confidenceThreshold = 0.6
        )

        registerIntents()
    }

    @Nested
    inner class PureRulePath {

        @Test
        fun `single-keyword intent resolves via rule only — LLM never called`() = runTest {
            // "start_command" has 1 keyword "/start" -> input "/start" -> 1/1 = 1.0 confidence
            // 1.0 >= 0.8 threshold -> rule accepted, LLM skipped
            val resolved = resolver.resolve("/start")

            assertNotNull(resolved) { "Should resolve via rule" }
            assertEquals("start_command", resolved!!.intentName) { "Should match start_command" }
            assertEquals("rule", resolved.result.classifiedBy) { "Should be classified by rule, not LLM" }
            assertEquals(0, resolved.result.tokenCost) { "Rule-based should have zero token cost" }
        }

        @Test
        fun `rule-resolved profile is applied to AgentCommand`() = runTest {
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "/start",
                model = "openai",
                temperature = 0.7,
                maxToolCalls = 10
            )

            val resolved = resolver.resolve(command.userPrompt)!!
            val applied = resolver.applyProfile(command, resolved)

            assertEquals("gemini", applied.model) { "Model should be overridden by profile" }
            assertEquals(0, applied.maxToolCalls) { "MaxToolCalls should be overridden" }
            assertEquals(0.7, applied.temperature) { "Temperature should be preserved (profile null)" }
            assertEquals("original", applied.systemPrompt) { "SystemPrompt should be preserved (profile null)" }
            assertEquals("/start", applied.userPrompt) { "UserPrompt should never change" }
        }

        @Test
        fun `all-keywords match gives highest rule confidence`() = runTest {
            // "환불 반품" matches both refund keywords -> 2/2 = 1.0 confidence -> rule accepted
            val resolved = resolver.resolve("환불 반품 처리")

            assertNotNull(resolved) { "Should resolve refund via rule" }
            assertEquals("refund", resolved!!.intentName) { "Should match refund" }
            assertEquals("rule", resolved.result.classifiedBy) { "Should be classified by rule" }
            assertEquals(1.0, resolved.result.primary!!.confidence, 0.01) { "Full match should give 1.0 confidence" }
        }
    }

    @Nested
    inner class RuleToLlmFallback {

        @Test
        fun `partial keyword match falls through to LLM`() = runTest {
            // "안녕하세요" matches "안녕" (1/3 greeting keywords) -> 0.33 < 0.8 -> falls to LLM
            mockLlmResponse("""{"intents":[{"name":"greeting","confidence":0.95}]}""")

            val resolved = resolver.resolve("안녕하세요!")

            assertNotNull(resolved) { "Should resolve via LLM fallback" }
            assertEquals("greeting", resolved!!.intentName) { "LLM should confirm greeting intent" }
            assertEquals("llm", resolved.result.classifiedBy) { "Should be classified by LLM (rule was low confidence)" }
        }

        @Test
        fun `LLM fallback applies correct profile`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"greeting","confidence":0.92}]}""")

            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "안녕하세요!",
                model = "openai",
                temperature = 0.9
            )

            val resolved = resolver.resolve(command.userPrompt)!!
            val applied = resolver.applyProfile(command, resolved)

            assertEquals("gemini", applied.model) { "Greeting profile should override model" }
            assertEquals(0, applied.maxToolCalls) { "Greeting profile should set maxToolCalls=0" }
            assertEquals(0.9, applied.temperature) { "Temperature preserved (profile has null)" }
        }
    }

    @Nested
    inner class PureLlmPath {

        @Test
        fun `no keyword match at all falls through to LLM`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.88}]}""")

            val resolved = resolver.resolve("지난번에 산 거 돈 돌려받을 수 있나요?")

            assertNotNull(resolved) { "Should resolve via LLM" }
            assertEquals("refund", resolved!!.intentName) { "LLM should classify as refund" }
            assertEquals("llm", resolved.result.classifiedBy) { "Should be classified by LLM" }
        }

        @Test
        fun `LLM refund intent applies system prompt override`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.92}]}""")

            val command = AgentCommand(systemPrompt = "default", userPrompt = "돈 돌려줘")
            val resolved = resolver.resolve(command.userPrompt)!!
            val applied = resolver.applyProfile(command, resolved)

            assertEquals("You are a refund specialist. Follow the refund policy strictly.", applied.systemPrompt) {
                "Refund profile should override system prompt"
            }
            assertEquals(5, applied.maxToolCalls) { "Refund profile should set maxToolCalls=5" }
        }
    }

    @Nested
    inner class MultiIntentPath {

        @Test
        fun `multi-intent LLM response merges tools from both intents`() = runTest {
            mockLlmResponse(
                """{"intents":[{"name":"refund","confidence":0.85},{"name":"order_inquiry","confidence":0.72}]}"""
            )

            val resolved = resolver.resolve("환불하고 주문 상태도 확인해주세요")

            assertNotNull(resolved) { "Should resolve multi-intent" }
            assertEquals("refund", resolved!!.intentName) { "Primary should be refund (highest confidence)" }

            val tools = resolved.profile.allowedTools!!
            assertTrue(tools.contains("checkOrder")) { "Should include refund's checkOrder" }
            assertTrue(tools.contains("processRefund")) { "Should include refund's processRefund" }
            assertTrue(tools.contains("getOrderStatus")) { "Should include order_inquiry's getOrderStatus (merged)" }
        }

        @Test
        fun `secondary intent below threshold is not merged`() = runTest {
            mockLlmResponse(
                """{"intents":[{"name":"refund","confidence":0.85},{"name":"order_inquiry","confidence":0.4}]}"""
            )

            val resolved = resolver.resolve("환불 위주로")

            assertNotNull(resolved) { "Should resolve primary" }
            val tools = resolved!!.profile.allowedTools!!
            assertTrue(tools.contains("processRefund")) { "Should include refund tools" }
            assertFalse(tools.contains("getOrderStatus")) { "Should NOT include order tools (0.4 < 0.6 threshold)" }
        }
    }

    @Nested
    inner class ErrorResilience {

        @Test
        fun `LLM failure with low rule confidence returns null`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            // "주문" matches 1/3 order keywords -> 0.33 -> falls to LLM -> LLM fails -> falls back to rule (0.33)
            // 0.33 < 0.6 resolver threshold -> null
            val resolved = resolver.resolve("주문 관련 문의입니다")
            assertNull(resolved) { "Low-confidence rule fallback should not meet resolver threshold" }
        }

        @Test
        fun `LLM failure with high rule confidence still resolves`() = runTest {
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            // "/start" matches 1/1 -> 1.0 -> rule accepted directly (never reaches LLM)
            val resolved = resolver.resolve("/start")
            assertNotNull(resolved) { "High-confidence rule should resolve regardless of LLM state" }
            assertEquals("start_command", resolved!!.intentName) { "Should match start_command" }
        }

        @Test
        fun `unknown LLM response returns null`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"unknown","confidence":0.5}]}""")

            val resolved = resolver.resolve("asdfghjkl")
            assertNull(resolved) { "Unknown intent should return null" }
        }

        @Test
        fun `low confidence LLM result returns null`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.4}]}""")

            val resolved = resolver.resolve("maybe refund?")
            assertNull(resolved) { "Below threshold (0.4 < 0.6) should return null" }
        }

        @Test
        fun `disabled intent is invisible to entire pipeline`() = runTest {
            registry.save(
                IntentDefinition(
                    name = "disabled_intent",
                    description = "Should not match",
                    keywords = listOf("disabled_test_keyword"),
                    enabled = false
                )
            )
            mockLlmResponse("""{"intents":[{"name":"unknown","confidence":0.5}]}""")

            val resolved = resolver.resolve("disabled_test_keyword")
            assertNull(resolved) { "Disabled intent should not be matched" }
        }

        @Test
        fun `empty registry returns null without error`() = runTest {
            val emptyRegistry = InMemoryIntentRegistry()
            val emptyResolver = IntentResolver(
                classifier = CompositeIntentClassifier(
                    ruleClassifier = RuleBasedIntentClassifier(emptyRegistry),
                    llmClassifier = LlmIntentClassifier(chatClient = chatClient, registry = emptyRegistry),
                    ruleConfidenceThreshold = 0.8
                ),
                registry = emptyRegistry,
                confidenceThreshold = 0.6
            )

            val resolved = emptyResolver.resolve("hello")
            assertNull(resolved) { "Empty registry should return null gracefully" }
        }
    }

    @Nested
    inner class ConversationContext {

        @Test
        fun `classification context flows through the pipeline`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.9}]}""")

            val context = ClassificationContext(
                userId = "user-123",
                conversationHistory = listOf(
                    Message(role = MessageRole.USER, content = "주문 #1234 환불해주세요"),
                    Message(role = MessageRole.ASSISTANT, content = "환불 처리 도와드리겠습니다")
                )
            )

            val resolved = resolver.resolve("네 진행해주세요", context)
            assertNotNull(resolved) { "Should resolve with conversation context" }
            assertEquals("refund", resolved!!.intentName) { "LLM should classify based on context" }
        }
    }

    @Nested
    inner class FullPipelineEndToEnd {

        @Test
        fun `complete flow — rule path — input to final AgentCommand`() = runTest {
            val original = AgentCommand(
                systemPrompt = "You are a helpful assistant",
                userPrompt = "/start",
                model = "openai",
                temperature = 0.7,
                maxToolCalls = 10,
                userId = "user-456",
                metadata = mapOf("sessionId" to "s1")
            )

            val resolved = resolver.resolve(original.userPrompt)
            assertNotNull(resolved) { "Should resolve start_command" }

            val final = resolver.applyProfile(original, resolved!!)

            // Profile overrides
            assertEquals("gemini", final.model) { "Model overridden to gemini" }
            assertEquals(0, final.maxToolCalls) { "MaxToolCalls overridden to 0" }

            // Preserved values
            assertEquals(0.7, final.temperature) { "Temperature preserved" }
            assertEquals("/start", final.userPrompt) { "UserPrompt never changes" }
            assertEquals("user-456", final.userId) { "UserId preserved" }

            // Metadata merged
            assertEquals("s1", final.metadata["sessionId"]) { "Original metadata preserved" }
            assertEquals("start_command", final.metadata["intentName"]) { "Intent name added" }
            assertEquals("rule", final.metadata["intentClassifiedBy"]) { "Classifier type added" }
            assertTrue(final.metadata.containsKey("intentConfidence")) { "Confidence added" }
            assertEquals(0, final.metadata["intentTokenCost"]) { "Token cost = 0 for rule" }
        }

        @Test
        fun `complete flow — LLM path — input to final AgentCommand`() = runTest {
            mockLlmResponse("""{"intents":[{"name":"refund","confidence":0.91}]}""")

            val original = AgentCommand(
                systemPrompt = "default prompt",
                userPrompt = "지난 주문 환불 가능한가요?",
                model = "openai",
                temperature = 0.5,
                maxToolCalls = 10,
                metadata = mapOf("channel" to "web")
            )

            val resolved = resolver.resolve(original.userPrompt)!!
            val final = resolver.applyProfile(original, resolved)

            // Refund profile overrides
            assertEquals("You are a refund specialist. Follow the refund policy strictly.", final.systemPrompt) {
                "SystemPrompt overridden by refund profile"
            }
            assertEquals(5, final.maxToolCalls) { "MaxToolCalls overridden to 5" }

            // Preserved values
            assertEquals("openai", final.model) { "Model preserved (refund profile has null model)" }
            assertEquals(0.5, final.temperature) { "Temperature preserved" }

            // Metadata
            assertEquals("web", final.metadata["channel"]) { "Original metadata preserved" }
            assertEquals("refund", final.metadata["intentName"]) { "Intent name added" }
            assertEquals("llm", final.metadata["intentClassifiedBy"]) { "LLM classifier type added" }
            assertTrue((final.metadata["intentTokenCost"] as Int) > 0) { "LLM path should have token cost > 0" }
        }
    }

    @Nested
    inner class EnhancedRuleFeatures {

        @Test
        fun `synonym match resolves via rule path`() = runTest {
            registry.save(IntentDefinition(
                name = "refund_enhanced",
                description = "Refund with synonyms",
                keywords = listOf("환불"),
                synonyms = mapOf("환불" to listOf("리펀드", "돌려줘")),
                profile = IntentProfile(maxToolCalls = 5)
            ))

            val resolved = resolver.resolve("리펀드 해주세요")
            assertNotNull(resolved) { "Synonym should match via rule" }
            assertEquals("refund_enhanced", resolved!!.intentName) { "Should match refund_enhanced" }
            assertEquals("rule", resolved.result.classifiedBy) { "Should be rule-classified" }
        }

        @Test
        fun `negative keyword causes rule failure and LLM fallback`() = runTest {
            registry.save(IntentDefinition(
                name = "refund_neg",
                description = "Refund with negative keywords",
                keywords = listOf("환불"),
                negativeKeywords = listOf("환불 정책"),
                profile = IntentProfile(maxToolCalls = 5)
            ))

            // "환불 정책" matches negative keyword -> rule excludes refund_neg
            // LLM mock returns refund_neg anyway (LLM doesn't know about negative keywords)
            mockLlmResponse("""{"intents":[{"name":"refund_neg","confidence":0.9}]}""")

            val resolved = resolver.resolve("환불 정책 알려주세요")
            assertNotNull(resolved) { "LLM fallback should resolve" }
            assertEquals("refund_neg", resolved!!.intentName) { "LLM classifies as refund_neg" }
            assertEquals("llm", resolved.result.classifiedBy) { "Should fallback to LLM" }
        }

        @Test
        fun `weighted keyword changes winner intent`() = runTest {
            registry.save(IntentDefinition(
                name = "weighted_refund",
                description = "Refund with weighted keywords",
                keywords = listOf("주문", "환불"),
                keywordWeights = mapOf("환불" to 5.0),
                profile = IntentProfile(maxToolCalls = 5)
            ))
            // "주문" is also in order_inquiry (3 keywords), but weighted_refund has 환불 at 5.0
            // Input "주문 환불" -> weighted_refund: (1+5)/(1+5) = 1.0, order: 1/3 = 0.33
            val resolved = resolver.resolve("주문 환불 해주세요")
            assertNotNull(resolved) { "Should resolve" }
            assertEquals("weighted_refund", resolved!!.intentName) {
                "Weighted refund should win over order_inquiry"
            }
        }
    }

    private fun registerIntents() {
        // Single keyword -> 1/1 = 1.0 confidence -> always rule-accepted
        registry.save(
            IntentDefinition(
                name = "start_command",
                description = "Bot start command",
                keywords = listOf("/start"),
                profile = IntentProfile(model = "gemini", maxToolCalls = 0)
            )
        )
        registry.save(
            IntentDefinition(
                name = "greeting",
                description = "Simple greetings and small talk",
                examples = listOf("안녕하세요", "Hi there", "Hello"),
                keywords = listOf("안녕", "hello", "hi"),
                profile = IntentProfile(model = "gemini", maxToolCalls = 0)
            )
        )
        registry.save(
            IntentDefinition(
                name = "order_inquiry",
                description = "Order lookup, status check, order modification",
                examples = listOf("주문 상태 확인", "주문 조회"),
                keywords = listOf("주문", "주문 조회", "주문 상태"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "getOrderStatus"),
                    maxToolCalls = 3
                )
            )
        )
        registry.save(
            IntentDefinition(
                name = "refund",
                description = "Refund requests, return processing",
                examples = listOf("환불 신청", "반품하고 싶어요"),
                keywords = listOf("환불", "반품"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "processRefund"),
                    systemPrompt = "You are a refund specialist. Follow the refund policy strictly.",
                    maxToolCalls = 5
                )
            )
        )
    }

    private fun mockLlmResponse(content: String) {
        val chatResponse = ChatResponse(listOf(Generation(AssistantMessage(content))))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }
}
