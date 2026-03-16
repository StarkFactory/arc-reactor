package com.arc.reactor.intent

import com.arc.reactor.intent.impl.CompositeIntentClassifier
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CompositeIntentClassifier에 대한 테스트.
 *
 * 복합 인텐트 분류기의 동작을 검증합니다.
 */
class CompositeIntentClassifierTest {

    private lateinit var ruleClassifier: IntentClassifier
    private lateinit var llmClassifier: IntentClassifier
    private lateinit var composite: CompositeIntentClassifier

    @BeforeEach
    fun setUp() {
        ruleClassifier = mockk()
        llmClassifier = mockk()
        composite = CompositeIntentClassifier(
            ruleClassifier = ruleClassifier,
            llmClassifier = llmClassifier,
            ruleConfidenceThreshold = 0.8
        )
    }

    @Nested
    inner class RuleFirstStrategy {

        @Test
        fun `confidence above threshold일 때 rule result를 사용한다`() = runTest {
            val ruleResult = IntentResult(
                primary = ClassifiedIntent("greeting", 0.9),
                classifiedBy = "rule"
            )
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult

            val result = composite.classify("hello")

            assertEquals("greeting", result.primary!!.intentName) { "Should use rule result" }
            assertEquals("rule", result.classifiedBy) { "Should report rule as classifier" }
            coVerify(exactly = 0) { llmClassifier.classify(any(), any()) }
        }

        @Test
        fun `LLM when rule confidence below threshold로 폴백한다`() = runTest {
            val ruleResult = IntentResult(
                primary = ClassifiedIntent("greeting", 0.5),
                classifiedBy = "rule"
            )
            val llmResult = IntentResult(
                primary = ClassifiedIntent("order", 0.85),
                classifiedBy = "llm"
            )
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult
            coEvery { llmClassifier.classify(any(), any()) } returns llmResult

            val result = composite.classify("주문 관련 인사")

            assertEquals("order", result.primary!!.intentName) {
                "Should use LLM result when rule confidence is below threshold"
            }
            assertEquals("llm", result.classifiedBy) { "Should report llm as classifier" }
        }

        @Test
        fun `LLM when rule returns unknown로 폴백한다`() = runTest {
            val ruleResult = IntentResult.unknown(classifiedBy = "rule")
            val llmResult = IntentResult(
                primary = ClassifiedIntent("refund", 0.88),
                classifiedBy = "llm"
            )
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult
            coEvery { llmClassifier.classify(any(), any()) } returns llmResult

            val result = composite.classify("돈 돌려주세요")

            assertEquals("refund", result.primary!!.intentName) { "Should use LLM result" }
        }
    }

    @Nested
    inner class ErrorResilience {

        @Test
        fun `LLM fails일 때 rule result를 반환한다`() = runTest {
            val ruleResult = IntentResult(
                primary = ClassifiedIntent("greeting", 0.5),
                classifiedBy = "rule"
            )
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult
            coEvery { llmClassifier.classify(any(), any()) } throws RuntimeException("LLM down")

            val result = composite.classify("hello")

            assertEquals("greeting", result.primary!!.intentName) {
                "Should fall back to rule result when LLM fails"
            }
            assertEquals("rule", result.classifiedBy) { "Should report rule as classifier" }
        }

        @Test
        fun `LLM fails and rule found nothing일 때 unknown rule result를 반환한다`() = runTest {
            val ruleResult = IntentResult.unknown(classifiedBy = "rule")
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult
            coEvery { llmClassifier.classify(any(), any()) } throws RuntimeException("LLM down")

            val result = composite.classify("random input")

            assertTrue(result.isUnknown) { "Should return unknown when both classifiers fail" }
        }
    }

    @Nested
    inner class ThresholdConfiguration {

        @Test
        fun `exact은(는) threshold value triggers LLM fallback`() = runTest {
            val exactThreshold = CompositeIntentClassifier(
                ruleClassifier = ruleClassifier,
                llmClassifier = llmClassifier,
                ruleConfidenceThreshold = 0.8
            )

            val ruleResult = IntentResult(
                primary = ClassifiedIntent("greeting", 0.8),
                classifiedBy = "rule"
            )
            val llmResult = IntentResult(
                primary = ClassifiedIntent("greeting", 0.95),
                classifiedBy = "llm"
            )
            coEvery { ruleClassifier.classify(any(), any()) } returns ruleResult
            coEvery { llmClassifier.classify(any(), any()) } returns llmResult

            val result = exactThreshold.classify("hello")

            assertEquals("rule", result.classifiedBy) {
                "Confidence >= threshold should use rule (not fall through to LLM)"
            }
        }
    }
}
