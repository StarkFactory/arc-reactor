package com.arc.reactor.intent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IntentResolverTest {

    private lateinit var classifier: IntentClassifier
    private lateinit var registry: InMemoryIntentRegistry
    private lateinit var resolver: IntentResolver

    @BeforeEach
    fun setUp() {
        classifier = mockk()
        registry = InMemoryIntentRegistry()
        resolver = IntentResolver(
            classifier = classifier,
            registry = registry,
            confidenceThreshold = 0.6
        )
    }

    @Nested
    inner class ResolveIntent {

        @Test
        fun `confident match found일 때 resolved intent를 반환한다`() = runTest {
            registry.save(IntentDefinition(
                name = "greeting",
                description = "Greetings",
                profile = IntentProfile(model = "gemini", maxToolCalls = 3)
            ))
            coEvery { classifier.classify(any(), any()) } returns IntentResult(
                primary = ClassifiedIntent("greeting", 0.95),
                classifiedBy = "rule"
            )

            val resolved = resolver.resolve("hello")
            assertNotNull(resolved) { "Expected a resolved intent" }
            assertEquals("greeting", resolved!!.intentName) { "Intent name should match" }
            assertEquals("gemini", resolved.profile.model) { "Profile model should be from definition" }
        }

        @Test
        fun `classification is unknown일 때 null를 반환한다`() = runTest {
            coEvery { classifier.classify(any(), any()) } returns IntentResult.unknown("rule")

            val resolved = resolver.resolve("random input")
            assertNull(resolved) { "Expected null for unknown classification" }
        }

        @Test
        fun `confidence is below threshold일 때 null를 반환한다`() = runTest {
            registry.save(IntentDefinition(name = "order", description = "Orders"))
            coEvery { classifier.classify(any(), any()) } returns IntentResult(
                primary = ClassifiedIntent("order", 0.4),
                classifiedBy = "llm"
            )

            val resolved = resolver.resolve("maybe an order?")
            assertNull(resolved) { "Expected null when confidence (0.4) < threshold (0.6)" }
        }

        @Test
        fun `classified intent not found in registry일 때 null를 반환한다`() = runTest {
            coEvery { classifier.classify(any(), any()) } returns IntentResult(
                primary = ClassifiedIntent("non_existent", 0.9),
                classifiedBy = "llm"
            )

            val resolved = resolver.resolve("test")
            assertNull(resolved) { "Expected null when intent not in registry" }
        }

        @Test
        fun `classifier throws exception일 때 null를 반환한다`() = runTest {
            coEvery { classifier.classify(any(), any()) } throws RuntimeException("Classifier error")

            val resolved = resolver.resolve("test")
            assertNull(resolved) { "Expected null on classifier exception" }
        }
    }

    @Nested
    inner class ApplyProfile {

        @Test
        fun `profile overrides to command를 적용한다`() = runTest {
            val profile = IntentProfile(
                model = "anthropic",
                temperature = 0.1,
                maxToolCalls = 3,
                systemPrompt = "You are a specialist"
            )
            val resolved = createResolvedIntent("test_intent", profile)

            val command = AgentCommand(
                systemPrompt = "Original prompt",
                userPrompt = "test",
                model = "gemini",
                temperature = 0.7,
                maxToolCalls = 10
            )

            val applied = resolver.applyProfile(command, resolved)

            assertEquals("anthropic", applied.model) { "Model should be overridden" }
            assertEquals(0.1, applied.temperature) { "Temperature should be overridden" }
            assertEquals(3, applied.maxToolCalls) { "MaxToolCalls should be overridden" }
            assertEquals("You are a specialist", applied.systemPrompt) {
                "SystemPrompt should be overridden"
            }
            assertEquals("test", applied.userPrompt) { "UserPrompt should not change" }
        }

        @Test
        fun `original command values for null profile fields를 보존한다`() = runTest {
            val profile = IntentProfile(model = "anthropic") // only model set
            val resolved = createResolvedIntent("test_intent", profile)

            val command = AgentCommand(
                systemPrompt = "Original prompt",
                userPrompt = "test",
                model = "gemini",
                temperature = 0.5,
                maxToolCalls = 10
            )

            val applied = resolver.applyProfile(command, resolved)

            assertEquals("anthropic", applied.model) { "Model should be overridden" }
            assertEquals(0.5, applied.temperature) { "Temperature should be preserved" }
            assertEquals(10, applied.maxToolCalls) { "MaxToolCalls should be preserved" }
            assertEquals("Original prompt", applied.systemPrompt) {
                "SystemPrompt should be preserved"
            }
        }

        @Test
        fun `intent metadata to command를 추가한다`() = runTest {
            val profile = IntentProfile(model = "gemini")
            val resolved = createResolvedIntent("greeting", profile, confidence = 0.92)

            val command = AgentCommand(
                systemPrompt = "test",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )

            val applied = resolver.applyProfile(command, resolved)

            assertEquals("greeting", applied.metadata[IntentResolver.METADATA_INTENT_NAME]) {
                "Should include intent name in metadata"
            }
            assertEquals(0.92, applied.metadata[IntentResolver.METADATA_INTENT_CONFIDENCE]) {
                "Should include intent confidence in metadata"
            }
            assertEquals("s1", applied.metadata["sessionId"]) {
                "Original metadata should be preserved"
            }
        }
    }

    @Nested
    inner class MultiIntentProfileMerging {

        @Test
        fun `allowed tools from secondary intents를 병합한다`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refunds",
                profile = IntentProfile(allowedTools = setOf("processRefund"))
            ))
            registry.save(IntentDefinition(
                name = "shipping",
                description = "Shipping",
                profile = IntentProfile(allowedTools = setOf("trackShipping"))
            ))

            coEvery { classifier.classify(any(), any()) } returns IntentResult(
                primary = ClassifiedIntent("refund", 0.85),
                secondary = listOf(ClassifiedIntent("shipping", 0.72)),
                classifiedBy = "llm"
            )

            val resolved = resolver.resolve("환불하고 배송 확인")
            assertNotNull(resolved) { "Expected a resolved intent" }
            val tools = resolved!!.profile.allowedTools!!
            assertTrue(tools.contains("processRefund")) { "Should include primary tools" }
            assertTrue(tools.contains("trackShipping")) { "Should include secondary tools" }
        }

        @Test
        fun `secondary intents below confidence threshold를 무시한다`() = runTest {
            registry.save(IntentDefinition(
                name = "refund",
                description = "Refunds",
                profile = IntentProfile(allowedTools = setOf("processRefund"))
            ))
            registry.save(IntentDefinition(
                name = "shipping",
                description = "Shipping",
                profile = IntentProfile(allowedTools = setOf("trackShipping"))
            ))

            coEvery { classifier.classify(any(), any()) } returns IntentResult(
                primary = ClassifiedIntent("refund", 0.85),
                secondary = listOf(ClassifiedIntent("shipping", 0.3)), // below threshold
                classifiedBy = "llm"
            )

            val resolved = resolver.resolve("환불 관련")
            assertNotNull(resolved) { "Expected a resolved intent" }
            val tools = resolved!!.profile.allowedTools
            assertEquals(setOf("processRefund"), tools) {
                "Should NOT include tools from low-confidence secondary intent"
            }
        }
    }

    private fun createResolvedIntent(
        name: String,
        profile: IntentProfile,
        confidence: Double = 0.9
    ) = ResolvedIntent(
        intentName = name,
        profile = profile,
        result = IntentResult(
            primary = ClassifiedIntent(name, confidence),
            classifiedBy = "rule"
        )
    )
}
