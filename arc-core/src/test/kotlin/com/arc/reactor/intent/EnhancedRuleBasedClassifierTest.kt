package com.arc.reactor.intent

import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for enhanced rule-based classification features:
 * synonyms, keyword weights, and negative keywords.
 */
class EnhancedRuleBasedClassifierTest {

    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var classifier: RuleBasedIntentClassifier

    @BeforeEach
    fun setUp() {
        registry = InMemoryIntentRegistry()
        classifier = RuleBasedIntentClassifier(registry)
    }

    @Nested
    inner class SynonymMatching {

        @Test
        fun `synonym matches as if it were the original keyword`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불"),
                synonyms = mapOf("환불" to listOf("리펀드", "반품", "돌려줘"))
            ))

            val result = classifier.classify("리펀드 해주세요")
            assertFalse(result.isUnknown) { "Synonym '리펀드' should match '환불'" }
            assertEquals("refund", result.primary!!.intentName) { "Should match refund intent" }
            assertEquals(1.0, result.primary!!.confidence, 0.01) { "1/1 keyword matched" }
        }

        @Test
        fun `synonym and original keyword count as one match`() = runTest {
            // "환불" and its synonym "돌려줘" both appear — should still count as 1 match
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불", "취소"),
                synonyms = mapOf("환불" to listOf("돌려줘"))
            ))

            val result = classifier.classify("환불 돌려줘")
            assertFalse(result.isUnknown) { "Should match" }
            // Only "환불" keyword matched (via original), "취소" didn't match -> 1/2 = 0.5
            assertEquals(0.5, result.primary!!.confidence, 0.01) {
                "Should be 0.5 — original+synonym = 1 keyword, 취소 unmatched"
            }
        }

        @Test
        fun `multiple synonym groups match independently`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불", "취소"),
                synonyms = mapOf(
                    "환불" to listOf("리펀드"),
                    "취소" to listOf("캔슬")
                )
            ))

            val result = classifier.classify("리펀드하고 캔슬해주세요")
            assertFalse(result.isUnknown) { "Both synonym groups should match" }
            assertEquals(1.0, result.primary!!.confidence, 0.01) {
                "Both keywords matched via synonyms -> 2/2 = 1.0"
            }
        }
    }

    @Nested
    inner class WeightedKeywords {

        @Test
        fun `weighted keyword increases confidence contribution`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불", "문의"),
                keywordWeights = mapOf("환불" to 3.0)
                // "환불" weight=3.0, "문의" weight=1.0 (default)
            ))

            // Only "환불" matches -> matchedWeight=3.0, totalWeight=4.0 -> confidence=0.75
            val result = classifier.classify("환불 요청합니다")
            assertFalse(result.isUnknown) { "Should match" }
            assertEquals(0.75, result.primary!!.confidence, 0.01) {
                "Weighted confidence: 3.0/4.0 = 0.75"
            }
        }

        @Test
        fun `unspecified weight defaults to 1_0`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello", "hi")
                // No keywordWeights -> both default to 1.0
            ))

            val result = classifier.classify("hello there")
            assertFalse(result.isUnknown) { "Should match" }
            assertEquals(0.5, result.primary!!.confidence, 0.01) {
                "Default weights: 1/2 = 0.5"
            }
        }

        @Test
        fun `weight changes winner between intents`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("주문", "환불"),
                keywordWeights = mapOf("환불" to 5.0)
                // "주문" w=1.0, "환불" w=5.0 -> totalWeight=6.0
            ))
            registry.save(IntentDefinition(
                name = "order",
                description = "Orders",
                keywords = listOf("주문", "조회", "상태")
                // all w=1.0 -> totalWeight=3.0
            ))

            // Input "주문 환불" -> refund: 주문(1)+환불(5) = 6/6=1.0, order: 주문(1) = 1/3=0.33
            val result = classifier.classify("주문 환불")
            assertEquals("refund", result.primary!!.intentName) {
                "Refund should win due to weighted '환불'"
            }
            assertEquals(1.0, result.primary!!.confidence, 0.01) { "Full match" }
        }
    }

    @Nested
    inner class NegativeKeywords {

        @Test
        fun `negative keyword excludes intent`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불"),
                negativeKeywords = listOf("환불 정책")
            ))

            val result = classifier.classify("환불 정책 알려주세요")
            assertTrue(result.isUnknown) { "Negative keyword '환불 정책' should exclude refund" }
        }

        @Test
        fun `negative keyword only affects its own intent`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불"),
                negativeKeywords = listOf("환불 정책")
            ))
            registry.save(IntentDefinition(
                name = "faq",
                description = "FAQ",
                keywords = listOf("정책", "안내")
            ))

            val result = classifier.classify("환불 정책 안내")
            assertFalse(result.isUnknown) { "FAQ should still match" }
            assertEquals("faq", result.primary!!.intentName) {
                "FAQ should match since negative keyword only excludes refund"
            }
        }

        @Test
        fun `no negative keyword match allows normal scoring`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refund requests",
                keywords = listOf("환불"),
                negativeKeywords = listOf("환불 정책")
            ))

            val result = classifier.classify("환불 신청합니다")
            assertFalse(result.isUnknown) { "No negative match -> normal scoring" }
            assertEquals("refund", result.primary!!.intentName) { "Should match refund" }
        }
    }

    @Nested
    inner class BackwardsCompat {

        @Test
        fun `existing IntentDefinition without new fields works identically`() = runTest {
            // No synonyms, no weights, no negativeKeywords
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello", "hi")
            ))

            val result = classifier.classify("hello world")
            assertFalse(result.isUnknown) { "Should match" }
            assertEquals("greeting", result.primary!!.intentName) { "Should match greeting" }
            assertEquals(0.5, result.primary!!.confidence, 0.01) { "1/2 = 0.5" }
            assertEquals("rule", result.classifiedBy) { "Should be rule" }
            assertEquals(0, result.tokenCost) { "Zero token cost" }
        }

        @Test
        fun `empty synonyms map has no effect`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello"),
                synonyms = emptyMap()
            ))

            val result = classifier.classify("hello")
            assertEquals(1.0, result.primary!!.confidence, 0.01) { "Full match" }
        }

        @Test
        fun `empty keywordWeights defaults all to 1_0`() = runTest {
            registry.save(IntentDefinition(
                name = "order",
                description = "Orders",
                keywords = listOf("주문", "조회", "상태"),
                keywordWeights = emptyMap()
            ))

            // 2 out of 3 match
            val result = classifier.classify("주문 조회")
            assertEquals(2.0 / 3.0, result.primary!!.confidence, 0.01) {
                "Default weights -> 2/3"
            }
        }
    }
}
