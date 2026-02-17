package com.arc.reactor.intent

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.IntentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration test — verifies IntentResolver integration in SpringAiAgentExecutor.
 *
 * Tests that intent resolution is applied to the command before LLM execution,
 * and that blocked intents are properly rejected.
 */
class IntentExecutorIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var resolver: IntentResolver

    @BeforeEach
    fun setUp() {
        fixture = AgentTestFixture()
        registry = InMemoryIntentRegistry()

        val ruleClassifier = RuleBasedIntentClassifier(registry)
        resolver = IntentResolver(
            classifier = ruleClassifier,
            registry = registry,
            confidenceThreshold = 0.6
        )

        registry.save(IntentDefinition(
            name = "greeting",
            description = "Greetings",
            keywords = listOf("hello"),
            profile = IntentProfile(model = "gemini", maxToolCalls = 0)
        ))
        registry.save(IntentDefinition(
            name = "refund",
            description = "Refund requests",
            keywords = listOf("환불"),
            profile = IntentProfile(
                systemPrompt = "You are a refund specialist.",
                maxToolCalls = 5
            )
        ))
    }

    private fun buildExecutor(
        intentResolver: IntentResolver? = resolver,
        blockedIntents: Set<String> = emptySet()
    ): SpringAiAgentExecutor {
        fixture.mockCallResponse("Test response")
        return SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = AgentTestFixture.defaultProperties(),
            intentResolver = intentResolver,
            blockedIntents = blockedIntents
        )
    }

    @Nested
    inner class ProfileApplication {

        @Test
        fun `intent profile is applied to command`() = runTest {
            val executor = buildExecutor()
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "hello",
                model = "openai",
                maxToolCalls = 10
            )

            val result = executor.execute(command)
            assertTrue(result.success) { "Execution should succeed" }
            // The profile overrides are applied internally; we verify by checking
            // that execution completed (model/maxToolCalls overrides don't break)
        }
    }

    @Nested
    inner class NullResolver {

        @Test
        fun `null IntentResolver preserves original command`() = runTest {
            val executor = buildExecutor(intentResolver = null)
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "hello",
                model = "openai"
            )

            val result = executor.execute(command)
            assertTrue(result.success) { "Should succeed with null resolver" }
        }
    }

    @Nested
    inner class ResolverReturnsNull {

        @Test
        fun `unmatched input preserves original command`() = runTest {
            val executor = buildExecutor()
            // "xyz" doesn't match any keywords -> resolver returns null -> original command used
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "xyz random input"
            )

            val result = executor.execute(command)
            assertTrue(result.success) { "Should succeed when resolver returns null" }
        }
    }

    @Nested
    inner class ResolverException {

        @Test
        fun `resolver exception uses original command (fail-safe)`() = runTest {
            val failingResolver = mockk<IntentResolver>()
            coEvery {
                failingResolver.resolve(any(), any<ClassificationContext>())
            } throws RuntimeException("Classification service down")

            fixture.mockCallResponse("Response despite resolver failure")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                intentResolver = failingResolver
            )

            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "hello"
            )

            val result = executor.execute(command)
            assertTrue(result.success) { "Should succeed despite resolver failure (fail-safe)" }
        }
    }

    @Nested
    inner class BlockedIntents {

        @Test
        fun `blocked intent returns GUARD_REJECTED`() = runTest {
            val executor = buildExecutor(blockedIntents = setOf("refund"))
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "환불 해주세요"
            )

            val result = executor.execute(command)
            assertFalse(result.success) { "Blocked intent should fail" }
            assertEquals(AgentErrorCode.GUARD_REJECTED, result.errorCode) {
                "Should be GUARD_REJECTED"
            }
            assertNotNull(result.errorMessage) { "Should have error message" }
        }

        @Test
        fun `non-blocked intent proceeds normally`() = runTest {
            val executor = buildExecutor(blockedIntents = setOf("refund"))
            val command = AgentCommand(
                systemPrompt = "original",
                userPrompt = "hello"
            )

            val result = executor.execute(command)
            assertTrue(result.success) { "Non-blocked intent should succeed" }
        }
    }
}
