package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StructuralEvaluatorTest {

    private lateinit var evaluator: StructuralEvaluator

    @BeforeEach
    fun setup() {
        evaluator = StructuralEvaluator()
    }

    private val defaultQuery = TestQuery(query = "What is AI?")

    @Nested
    inner class ValidJson {

        @Test
        fun `should pass with valid answer type`() = runTest {
            val response = """{"type": "answer", "message": "AI is..."}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Valid answer JSON should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0 for valid JSON" }
            assertEquals(EvaluationTier.STRUCTURAL, result.tier) {
                "Tier should be STRUCTURAL"
            }
        }

        @Test
        fun `should pass with valid error type`() = runTest {
            val response = """{"type": "error", "message": "Something went wrong"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Valid error JSON should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0" }
        }

        @Test
        fun `should pass with valid action type`() = runTest {
            val response = """{"type": "action", "message": "Created item"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Valid action JSON should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0" }
        }

        @Test
        fun `should pass with briefing type and summary field`() = runTest {
            val response = """{"type": "briefing", "summary": "Here is the summary"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Briefing with summary should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0" }
        }

        @Test
        fun `should pass with valid clarification type`() = runTest {
            val response = """{"type": "clarification", "message": "Could you specify?"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Valid clarification should pass" }
        }

        @Test
        fun `should pass with valid search type`() = runTest {
            val response = """{"type": "search", "message": "Found 3 results"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Valid search should pass" }
        }
    }

    @Nested
    inner class InvalidJson {

        @Test
        fun `should fail when type is missing`() = runTest {
            val response = """{"message": "Hello"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertFalse(result.passed) { "JSON without type should fail" }
            assertEquals(0.3, result.score) { "Score should be 0.3 for missing fields" }
        }

        @Test
        fun `should fail when type is invalid`() = runTest {
            val response = """{"type": "unknown", "message": "Hello"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertFalse(result.passed) { "JSON with invalid type should fail" }
            assertEquals(0.3, result.score) { "Score should be 0.3" }
        }

        @Test
        fun `should fail when message is missing for answer type`() = runTest {
            val response = """{"type": "answer"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertFalse(result.passed) { "Answer without message should fail" }
            assertEquals(0.3, result.score) { "Score should be 0.3" }
        }

        @Test
        fun `should fail when summary is missing for briefing type`() = runTest {
            val response = """{"type": "briefing", "message": "Not summary"}"""

            val result = evaluator.evaluate(response, defaultQuery)

            assertFalse(result.passed) { "Briefing without summary should fail" }
            assertEquals(0.3, result.score) { "Score should be 0.3" }
        }
    }

    @Nested
    inner class PlainText {

        @Test
        fun `should return compatibility score for plain text`() = runTest {
            val response = "This is a plain text response"

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Plain text should pass (compatibility)" }
            assertEquals(0.5, result.score) {
                "Score should be 0.5 for plain text"
            }
        }

        @Test
        fun `should return compatibility score for malformed JSON`() = runTest {
            val response = "{invalid json"

            val result = evaluator.evaluate(response, defaultQuery)

            assertTrue(result.passed) { "Malformed JSON should be treated as plain text" }
            assertEquals(0.5, result.score) { "Score should be 0.5" }
        }
    }
}
