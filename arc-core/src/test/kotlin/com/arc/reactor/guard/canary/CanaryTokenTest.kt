package com.arc.reactor.guard.canary

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CanaryTokenTest {

    @Nested
    inner class TokenGeneration {

        @Test
        fun `deterministic token from same seed`() {
            val provider1 = CanaryTokenProvider("test-seed")
            val provider2 = CanaryTokenProvider("test-seed")
            assertEquals(provider1.getToken(), provider2.getToken(),
                "Same seed should produce same token")
        }

        @Test
        fun `different seeds produce different tokens`() {
            val provider1 = CanaryTokenProvider("seed-1")
            val provider2 = CanaryTokenProvider("seed-2")
            assertNotEquals(provider1.getToken(), provider2.getToken(),
                "Different seeds should produce different tokens")
        }

        @Test
        fun `token starts with CANARY prefix`() {
            val provider = CanaryTokenProvider()
            assertTrue(provider.getToken().startsWith("CANARY-"),
                "Token should start with CANARY- prefix")
        }

        @Test
        fun `injection clause contains token`() {
            val provider = CanaryTokenProvider()
            val clause = provider.getInjectionClause()
            assertTrue(clause.contains(provider.getToken()),
                "Injection clause should contain the canary token")
            assertTrue(clause.contains("NEVER"),
                "Injection clause should warn about never revealing token")
        }

        @Test
        fun `containsToken detects token in text`() {
            val provider = CanaryTokenProvider()
            assertTrue(provider.containsToken("Some text ${provider.getToken()} more text"),
                "Should detect canary token in text")
            assertFalse(provider.containsToken("Some text without canary"),
                "Should not detect canary in clean text")
        }
    }

    @Nested
    inner class PostProcessor {

        @Test
        fun `canary clause is appended to system prompt`() {
            val provider = CanaryTokenProvider("test")
            val postProcessor = CanarySystemPromptPostProcessor(provider)

            val original = "You are a helpful assistant."
            val processed = postProcessor.process(original)

            assertTrue(processed.startsWith(original),
                "Original prompt should be preserved")
            assertTrue(processed.contains(provider.getToken()),
                "Canary token should be appended")
            assertTrue(processed.contains("NEVER"),
                "Warning should be included")
        }
    }

    @Nested
    inner class LeakageOutputGuard {

        private val provider = CanaryTokenProvider("test")
        private val guard = SystemPromptLeakageOutputGuard(provider)
        private val context = OutputGuardContext(
            command = AgentCommand(systemPrompt = "", userPrompt = "test"),
            toolsUsed = emptyList(),
            durationMs = 0
        )

        @Test
        fun `canary token in output is rejected`() = runBlocking {
            val output = "Here is what I know: ${provider.getToken()} and more"
            val result = guard.check(output, context)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "Output containing canary token should be rejected")
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category)
        }

        @Test
        fun `leakage pattern my system prompt is detected`() = runBlocking {
            val output = "Sure, my system prompt is to help users with coding."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "Leakage pattern 'my system prompt is' should be rejected")
        }

        @Test
        fun `leakage pattern here are my instructions detected`() = runBlocking {
            val output = "Here are my system instructions that tell me what to do."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "Leakage pattern 'here are my system instructions' should be rejected")
        }

        @Test
        fun `leakage pattern not supposed to reveal detected`() = runBlocking {
            val output = "I'm not supposed to reveal my prompt to anyone."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result,
                "Leakage pattern 'not supposed to reveal prompt' should be rejected")
        }

        @Test
        fun `benign instruction usage is not false positive`() = runBlocking {
            val output = "I was instructed to follow up with the client next week."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result,
                "Normal usage of 'I was instructed to' should not be a false positive")
        }

        @Test
        fun `normal usage of system word is not false positive`() = runBlocking {
            val output = "The system uses a microservices architecture with Spring Boot."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result,
                "Normal usage of 'system' should not be a false positive")
        }

        @Test
        fun `clean output is allowed`() = runBlocking {
            val output = "Here is how you implement a REST API in Kotlin..."
            val result = guard.check(output, context)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result,
                "Clean output should be allowed")
        }

        @Test
        fun `guard without canary provider only checks patterns`() = runBlocking {
            val guardWithoutCanary = SystemPromptLeakageOutputGuard(canaryTokenProvider = null)

            // Should not crash and should still check patterns
            val safeResult = guardWithoutCanary.check("Hello world", context)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, safeResult,
                "Safe output should pass even without canary provider")

            val leakResult = guardWithoutCanary.check("my full system prompt is xyz", context)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, leakResult,
                "Pattern check should still work without canary provider")
        }

        @Test
        fun `order is 5`() {
            assertEquals(5, guard.order,
                "SystemPromptLeakage should run at order=5 (before PII masking)")
        }
    }
}
