package com.arc.reactor.intent

import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import com.arc.reactor.intent.model.IntentDefinition
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 규칙 기반 IntentClassifier에 대한 테스트.
 *
 * 키워드 기반 인텐트 분류를 검증합니다.
 */
class RuleBasedIntentClassifierTest {

    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var classifier: RuleBasedIntentClassifier

    @BeforeEach
    fun setUp() {
        registry = InMemoryIntentRegistry()
        classifier = RuleBasedIntentClassifier(registry)
    }

    @Nested
    inner class KeywordMatching {

        @Test
        fun `matches은(는) single keyword`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("안녕", "hello", "hi")
            ))

            val result = classifier.classify("안녕하세요!")
            assertFalse(result.isUnknown) { "Expected a match for '안녕하세요'" }
            assertEquals("greeting", result.primary!!.intentName) { "Expected greeting intent" }
            assertEquals("rule", result.classifiedBy) { "Should be classified by rule" }
            assertEquals(0, result.tokenCost) { "Rule-based should have zero token cost" }
        }

        @Test
        fun `matches은(는) case-insensitively`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello")
            ))

            val result = classifier.classify("HELLO there!")
            assertFalse(result.isUnknown) { "Expected case-insensitive match for 'HELLO'" }
            assertEquals("greeting", result.primary!!.intentName) { "Expected greeting intent" }
        }

        @Test
        fun `no keywords match일 때 unknown를 반환한다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("안녕", "hello")
            ))

            val result = classifier.classify("주문 상태 확인해주세요")
            assertTrue(result.isUnknown) { "Expected unknown for non-matching input" }
            assertEquals("rule", result.classifiedBy) { "Should still report 'rule' as classifier" }
        }

        @Test
        fun `no intents have keywords일 때 unknown를 반환한다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = emptyList()
            ))

            val result = classifier.classify("hello")
            assertTrue(result.isUnknown) { "Expected unknown when no keywords are defined" }
        }
    }

    @Nested
    inner class MultipleIntents {

        @Test
        fun `multiple intents match일 때 selects highest confidence`() = runTest {
            registry.save(IntentDefinition(
                name = "order",
                description = "Orders",
                keywords = listOf("주문", "주문 조회", "주문 상태")
            ))
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refunds",
                keywords = listOf("환불", "반품")
            ))

            val result = classifier.classify("주문 조회해주세요")
            assertFalse(result.isUnknown) { "Expected a match" }
            assertEquals("order", result.primary!!.intentName) {
                "Expected order intent for '주문 조회'"
            }
        }

        @Test
        fun `multi-intent input에 대해 secondary intents를 반환한다`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refunds",
                keywords = listOf("환불")
            ))
            registry.save(IntentDefinition(
                name = "shipping",
                description = "Shipping",
                keywords = listOf("배송")
            ))

            val result = classifier.classify("환불하고 배송 상태도 알려줘")
            assertFalse(result.isUnknown) { "Expected a match for multi-intent input" }
            assertTrue(result.secondary.isNotEmpty()) {
                "Expected secondary intents for multi-intent input"
            }
            val allIntentNames = listOf(result.primary!!.intentName) + result.secondary.map { it.intentName }
            assertTrue(allIntentNames.contains("refund")) { "Should contain refund intent" }
            assertTrue(allIntentNames.contains("shipping")) { "Should contain shipping intent" }
        }
    }

    @Nested
    inner class ConfidenceScoring {

        @Test
        fun `confidence은(는) reflects keyword match ratio`() = runTest {
            registry.save(IntentDefinition(
                name = "order",
                description = "Orders",
                keywords = listOf("주문", "조회", "상태", "확인")
            ))

            // Matches 2 out of 4 keywords
            val result = classifier.classify("주문 조회해주세요")
            assertFalse(result.isUnknown) { "Expected a match" }
            assertEquals(0.5, result.primary!!.confidence, 0.01) {
                "Confidence should be 0.5 when 2/4 keywords match"
            }
        }

        @Test
        fun `전체 match gives confidence 1_0`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello")
            ))

            val result = classifier.classify("hello world")
            assertEquals(1.0, result.primary!!.confidence, 0.01) {
                "Full match (1/1 keywords) should give confidence 1.0"
            }
        }
    }

    @Nested
    inner class DisabledIntents {

        @Test
        fun `match disabled intents하지 않는다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello"),
                enabled = false
            ))

            val result = classifier.classify("hello")
            assertTrue(result.isUnknown) { "Disabled intent should not be matched" }
        }
    }

    @Nested
    inner class PerformanceCharacteristics {

        @Test
        fun `classification은(는) zero token cost를 가진다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello")
            ))

            val result = classifier.classify("hello")
            assertEquals(0, result.tokenCost) { "Rule-based classification should have zero token cost" }
        }

        @Test
        fun `latency은(는) measured이다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                keywords = listOf("hello")
            ))

            val result = classifier.classify("hello")
            assertTrue(result.latencyMs >= 0) { "Latency should be non-negative" }
        }
    }
}
