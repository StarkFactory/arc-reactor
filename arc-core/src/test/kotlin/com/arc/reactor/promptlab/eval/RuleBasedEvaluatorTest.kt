package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RuleBasedEvaluatorTest {

    private lateinit var evaluator: RuleBasedEvaluator

    @BeforeEach
    fun setup() {
        evaluator = RuleBasedEvaluator()
    }

    @Nested
    inner class ShortAnswerDetection {

        @Test
        fun `should pass when search answer is long enough`() = runTest {
            val query = TestQuery(query = "Search for AI", intent = "search")
            val longMessage = "A".repeat(51)
            val response = """{"type": "answer", "message": "$longMessage"}"""

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Search answer >50 chars should pass" }
            assertEquals(EvaluationTier.RULES, result.tier) { "Tier should be RULES" }
        }

        @Test
        fun `should fail when search answer is too short`() = runTest {
            val query = TestQuery(query = "Find me info", intent = "find")
            val response = """{"type": "answer", "message": "Short"}"""

            val result = evaluator.evaluate(response, query)

            assertFalse(result.passed) { "Search answer <=50 chars should fail" }
        }

        @Test
        fun `should not apply short answer rule for non-search intent`() = runTest {
            val query = TestQuery(query = "Create a file", intent = "create")
            val response = """{"type": "answer", "message": "OK"}"""

            val result = evaluator.evaluate(response, query)

            // create intent triggers action confirmation rule, not short answer
            assertEquals(EvaluationTier.RULES, result.tier) { "Tier should be RULES" }
        }
    }

    @Nested
    inner class ActionConfirmation {

        @Test
        fun `should pass when mutation has confirmation wording`() = runTest {
            val query = TestQuery(query = "Create user", intent = "create")
            val response = """
                {"type": "action", "message": "User created successfully", "success": true}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Mutation with confirmation should pass" }
        }

        @Test
        fun `should fail when mutation lacks confirmation wording`() = runTest {
            val query = TestQuery(query = "Delete item", intent = "delete")
            val response = """
                {"type": "action", "message": "OK", "success": true}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertFalse(result.passed) { "Mutation without confirmation should fail" }
        }

        @Test
        fun `should not apply when success is false`() = runTest {
            val query = TestQuery(query = "Update record", intent = "update")
            val response = """
                {"type": "action", "message": "Failed", "success": false}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            // success=false means this rule is not applicable
            assertTrue(result.passed) { "No applicable rules should mean pass" }
        }
    }

    @Nested
    inner class ErrorQuality {

        @Test
        fun `should pass when error has suggestions`() = runTest {
            val query = TestQuery(query = "Do something")
            val response = """
                {"type": "error", "message": "Failed", "suggestions": ["Try again"]}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Error with suggestions should pass" }
        }

        @Test
        fun `should pass when error message is long enough`() = runTest {
            val query = TestQuery(query = "Do something")
            val response = """
                {"type": "error", "message": "Something went wrong, please try again later"}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Error with long message should pass" }
        }

        @Test
        fun `should fail when error is short and has no suggestions`() = runTest {
            val query = TestQuery(query = "Do something")
            val response = """{"type": "error", "message": "Fail"}"""

            val result = evaluator.evaluate(response, query)

            assertFalse(result.passed) { "Short error without suggestions should fail" }
        }
    }

    @Nested
    inner class ClarificationOnly {

        @Test
        fun `should pass when clarification includes guidance`() = runTest {
            val query = TestQuery(query = "Help me")
            val response = """
                {"type": "clarification", "message": "Could you specify what you need? I can help with orders."}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Clarification with guidance should pass" }
        }

        @Test
        fun `should fail when clarification is only a question`() = runTest {
            val query = TestQuery(query = "Help me")
            val response = """
                {"type": "clarification", "message": "What do you mean?"}
            """.trimIndent()

            val result = evaluator.evaluate(response, query)

            assertFalse(result.passed) { "Clarification with only question should fail" }
        }
    }

    @Nested
    inner class NoApplicableRules {

        @Test
        fun `should pass with score 1 when no rules apply`() = runTest {
            val query = TestQuery(query = "Hello", intent = "greeting")
            val response = """{"type": "answer", "message": "Hi there!"}"""

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "No applicable rules should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0" }
        }

        @Test
        fun `should handle plain text response gracefully`() = runTest {
            val query = TestQuery(query = "Hello")
            val response = "Just a plain text response"

            val result = evaluator.evaluate(response, query)

            assertTrue(result.passed) { "Plain text with no rules should pass" }
            assertEquals(1.0, result.score) { "Score should be 1.0" }
        }
    }
}
