package com.arc.reactor.memory.summary

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.MemoryProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.config.SummaryProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.InMemoryMemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage

/**
 * E2E integration test for the Hierarchical Conversation Memory feature.
 *
 * Verifies the full flow of DefaultConversationManager with summary enabled:
 * structured facts + narrative summary + recent messages (3-layer memory).
 *
 * Uses real InMemoryMemoryStore and InMemoryConversationSummaryStore,
 * with a mock ConversationSummaryService (no real LLM calls).
 */
@Tag("integration")
class HierarchicalMemoryIntegrationTest {

    private val sessionId = "cs-session-refund-001"

    private val summaryProperties = SummaryProperties(
        enabled = true,
        triggerMessageCount = 20,
        recentMessageCount = 10,
        maxNarrativeTokens = 500
    )

    private val agentProperties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 10),
        guard = GuardProperties(),
        rag = RagProperties(),
        concurrency = ConcurrencyProperties(),
        memory = MemoryProperties(summary = summaryProperties)
    )

    private lateinit var memoryStore: InMemoryMemoryStore
    private lateinit var summaryStore: InMemoryConversationSummaryStore
    private lateinit var summaryService: ConversationSummaryService

    private val realisticFacts = listOf(
        StructuredFact(key = "customer_name", value = "Kim Minjun", category = FactCategory.ENTITY),
        StructuredFact(key = "order_number", value = "#ORD-2024-78956", category = FactCategory.ENTITY),
        StructuredFact(key = "order_amount", value = "89,000 KRW", category = FactCategory.NUMERIC),
        StructuredFact(key = "product_name", value = "Wireless Bluetooth Headphones Pro", category = FactCategory.ENTITY),
        StructuredFact(key = "refund_reason", value = "Product defect - left speaker not working", category = FactCategory.STATE),
        StructuredFact(key = "refund_status", value = "Approved", category = FactCategory.DECISION),
        StructuredFact(key = "refund_method", value = "Original payment method (credit card)", category = FactCategory.DECISION),
        StructuredFact(key = "estimated_refund_date", value = "3-5 business days", category = FactCategory.NUMERIC)
    )

    private val realisticNarrative = """
        Customer Kim Minjun contacted support regarding order #ORD-2024-78956 for Wireless Bluetooth
        Headphones Pro (89,000 KRW). The customer reported a product defect where the left speaker
        stopped working after 3 days of use. After verifying the order details and the warranty status,
        the agent confirmed the product is within the 30-day return window. The customer was offered
        either a replacement or a full refund. The customer chose a full refund to the original payment
        method (credit card). The refund was approved and the customer was informed it would take
        3-5 business days to process. A return shipping label was emailed to the customer. The customer
        expressed satisfaction with the resolution.
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        memoryStore = InMemoryMemoryStore()
        summaryStore = InMemoryConversationSummaryStore()
        summaryService = mockk()

        coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
            narrative = realisticNarrative,
            facts = realisticFacts,
            tokenCost = 350
        )

        populateCustomerServiceConversation()
    }

    /**
     * Populates the memory store with 32 messages (16 turns) simulating
     * a realistic customer service conversation about an order refund.
     */
    private fun populateCustomerServiceConversation() {
        val conversationMessages = listOf(
            // Turn 1: Greeting
            "user" to "Hi, I need help with a recent order.",
            "assistant" to "Hello! I'd be happy to help you with your order. Could you please provide me with your order number?",
            // Turn 2: Order identification
            "user" to "Sure, it's #ORD-2024-78956. My name is Kim Minjun.",
            "assistant" to "Thank you, Kim Minjun. I can see your order #ORD-2024-78956 for Wireless Bluetooth Headphones Pro, purchased on January 15th for 89,000 KRW. How can I assist you with this order?",
            // Turn 3: Problem description
            "user" to "The left speaker stopped working after just 3 days. I'm really disappointed.",
            "assistant" to "I'm sorry to hear about the issue with your headphones. A speaker malfunction after only 3 days is definitely not the experience we want for our customers. Let me check the warranty and return options for you.",
            // Turn 4: Warranty check
            "user" to "Is it still under warranty?",
            "assistant" to "Yes, your purchase is well within our 30-day return window, and the product also has a 1-year manufacturer warranty. You're eligible for both a replacement and a full refund. Which would you prefer?",
            // Turn 5: Refund preference
            "user" to "I'd prefer a full refund, please.",
            "assistant" to "Understood. I'll process a full refund of 89,000 KRW to your original payment method. Could you confirm the credit card ending in 4532 was used for this purchase?",
            // Turn 6: Payment confirmation
            "user" to "Yes, that's the card I used.",
            "assistant" to "Perfect. I've initiated the refund of 89,000 KRW to your credit card ending in 4532. The refund typically takes 3-5 business days to appear on your statement.",
            // Turn 7: Return process
            "user" to "Do I need to return the defective headphones?",
            "assistant" to "Yes, we'll need the product back. I'm sending a prepaid return shipping label to your email address on file. You should receive it within the next few minutes.",
            // Turn 8: Shipping label
            "user" to "I got the email with the shipping label. Where do I drop off the package?",
            "assistant" to "You can drop it off at any CJ Logistics pickup point or schedule a home pickup through the link in the email. Once we receive the item, your refund will be finalized.",
            // Turn 9: Timeline question
            "user" to "How long until the refund shows up after you get the item back?",
            "assistant" to "Once we receive and inspect the returned item, the refund is processed immediately. It then takes 3-5 business days for the amount to appear on your credit card statement. So in total, about 5-7 business days from when you ship it.",
            // Turn 10: Packaging question
            "user" to "Can I use my own box or do I need the original packaging?",
            "assistant" to "You can use any box as long as the headphones are securely packed. The original packaging is preferred but not required. Just make sure to include all accessories that came with the product.",
            // Turn 11: Accessories check
            "user" to "I have the charging cable and the carry case. The user manual got recycled though.",
            "assistant" to "That's perfectly fine. The charging cable and carry case are the essential accessories. A missing user manual won't affect your refund. Just pack the headphones, cable, and case.",
            // Turn 12: Pickup scheduling
            "user" to "Great, I'll schedule a pickup for tomorrow.",
            "assistant" to "Excellent choice. When you schedule the pickup, use the tracking number from the return label email. This way we can track the return shipment and process your refund as quickly as possible.",
            // Turn 13: Confirmation request
            "user" to "Can you send me a confirmation email with all the refund details?",
            "assistant" to "Of course! I've just sent a refund confirmation email to your registered address. It includes the order number, refund amount, expected timeline, and your case reference number CS-2024-45678.",
            // Turn 14: Case reference
            "user" to "Got it, thanks. And if the refund doesn't show up in 5 days?",
            "assistant" to "If the refund hasn't appeared after 5 business days from our confirmation email, please contact us again with your case reference CS-2024-45678. We'll escalate it immediately with the payment processor.",
            // Turn 15: Future order concern
            "user" to "I'm a bit worried about ordering the same headphones again. Are there known issues?",
            "assistant" to "I understand your concern. The defect you experienced is uncommon - our return rate for this model is less than 2%. If you'd like to try again, we can offer you a 10% discount on your next purchase of the same or a similar model.",
            // Turn 16: Closing
            "user" to "That's kind of you. I'll think about it. Thanks for the great help today!",
            "assistant" to "You're welcome, Kim Minjun! I'm glad I could help resolve this for you. Remember, your case reference is CS-2024-45678 if you need anything else. Have a wonderful day!"
        )

        for ((role, content) in conversationMessages) {
            memoryStore.addMessage(sessionId, role, content, "user-minjun")
        }
    }

    private fun createCommand(prompt: String = "follow-up question"): AgentCommand = AgentCommand(
        systemPrompt = "",
        userPrompt = prompt,
        metadata = mapOf("sessionId" to sessionId)
    )

    @Nested
    inner class FullHierarchicalFlow {

        @Test
        fun `should return facts system message, narrative system message, and recent messages`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            // 32 messages > triggerMessageCount(20), recentMessageCount=10
            // Expected: [Facts SystemMessage] + [Narrative SystemMessage] + [10 recent messages]
            assertEquals(12, history.size,
                "Total should be 2 system messages + 10 recent messages, got ${history.size}")

            assertTrue(history[0] is SystemMessage,
                "First message should be Facts SystemMessage but was ${history[0]::class.simpleName}")
            assertTrue(history[1] is SystemMessage,
                "Second message should be Narrative SystemMessage but was ${history[1]::class.simpleName}")
        }

        @Test
        fun `facts system message should contain key entities`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())
            val factsMessage = history[0] as SystemMessage
            val factsText = factsMessage.text

            assertTrue(factsText.contains("order_number"),
                "Facts should contain 'order_number' key, got: $factsText")
            assertTrue(factsText.contains("#ORD-2024-78956"),
                "Facts should contain order number value, got: $factsText")
            assertTrue(factsText.contains("customer_name"),
                "Facts should contain 'customer_name' key, got: $factsText")
            assertTrue(factsText.contains("Kim Minjun"),
                "Facts should contain customer name value, got: $factsText")
            assertTrue(factsText.contains("order_amount") || factsText.contains("89,000"),
                "Facts should contain order amount information, got: $factsText")
            assertTrue(factsText.contains("Conversation Facts:"),
                "Facts message should have 'Conversation Facts:' header, got: $factsText")
        }

        @Test
        fun `narrative system message should be coherent and contain summary`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())
            val narrativeMessage = history[1] as SystemMessage
            val narrativeText = narrativeMessage.text

            assertTrue(narrativeText.contains("Conversation Summary:"),
                "Narrative message should have 'Conversation Summary:' header, got: $narrativeText")
            assertTrue(narrativeText.contains("Kim Minjun"),
                "Narrative should mention the customer name, got: $narrativeText")
            assertTrue(narrativeText.contains("refund"),
                "Narrative should mention refund, got: $narrativeText")
            assertTrue(narrativeText.contains("#ORD-2024-78956"),
                "Narrative should mention the order number, got: $narrativeText")
        }

        @Test
        fun `recent messages should be verbatim with exact content match`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            // The last 10 messages in the conversation (turns 12-16)
            val recentMessages = history.subList(2, history.size)
            assertEquals(10, recentMessages.size,
                "Should have exactly 10 recent messages (recentMessageCount=10)")

            // Verify the very last message (assistant closing) is verbatim
            val lastMessage = recentMessages.last()
            assertTrue(lastMessage is AssistantMessage,
                "Last recent message should be AssistantMessage but was ${lastMessage::class.simpleName}")
            assertTrue((lastMessage as AssistantMessage).text.orEmpty().contains("CS-2024-45678"),
                "Last assistant message should contain case reference CS-2024-45678 verbatim")
            assertTrue(lastMessage.text.orEmpty().contains("Have a wonderful day!"),
                "Last assistant message should contain closing greeting verbatim")

            // Verify a user message from the recent window is verbatim
            val recentUserMessages = recentMessages.filterIsInstance<UserMessage>()
            assertTrue(recentUserMessages.isNotEmpty(),
                "Recent messages should contain at least one UserMessage")

            val lastUserMessage = recentUserMessages.last()
            assertTrue(lastUserMessage.text.orEmpty().contains("Thanks for the great help today!"),
                "Last user message should contain exact closing text")
        }

        @Test
        fun `total message count should equal 2 system messages plus recentMessageCount`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            val systemMessages = history.filterIsInstance<SystemMessage>()
            val nonSystemMessages = history.filter { it !is SystemMessage }

            assertEquals(2, systemMessages.size,
                "Should have exactly 2 SystemMessages (facts + narrative)")
            assertEquals(summaryProperties.recentMessageCount, nonSystemMessages.size,
                "Non-system messages should equal recentMessageCount (${summaryProperties.recentMessageCount})")
            assertEquals(
                2 + summaryProperties.recentMessageCount,
                history.size,
                "Total = 2 system messages + recentMessageCount"
            )
        }
    }

    @Nested
    inner class Caching {

        @Test
        fun `second loadHistory call should use cached summary without calling summaryService again`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            // First call: triggers summarization
            val firstHistory = manager.loadHistory(createCommand())
            assertEquals(12, firstHistory.size,
                "First call should return hierarchical history")

            // Second call: same session, same message count -> should use cache
            val secondHistory = manager.loadHistory(createCommand())
            assertEquals(12, secondHistory.size,
                "Second call should also return hierarchical history")

            // summaryService.summarize should have been called exactly once
            coVerify(exactly = 1) { summaryService.summarize(any(), any()) }
        }

        @Test
        fun `cached summary should produce identical results`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val firstHistory = manager.loadHistory(createCommand())
            val secondHistory = manager.loadHistory(createCommand())

            // Facts should be identical
            val firstFacts = (firstHistory[0] as SystemMessage).text
            val secondFacts = (secondHistory[0] as SystemMessage).text
            assertEquals(firstFacts, secondFacts,
                "Cached facts should be identical to first call")

            // Narrative should be identical
            val firstNarrative = (firstHistory[1] as SystemMessage).text
            val secondNarrative = (secondHistory[1] as SystemMessage).text
            assertEquals(firstNarrative, secondNarrative,
                "Cached narrative should be identical to first call")
        }
    }

    @Nested
    inner class SummaryRefreshOnNewMessages {

        @Test
        fun `should refresh summary when new messages are added after initial summarization`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            // First call: triggers initial summarization
            manager.loadHistory(createCommand())
            coVerify(exactly = 1) { summaryService.summarize(any(), any()) }

            // Add 2 new messages (1 turn) to the conversation
            memoryStore.addMessage(sessionId, "user", "Actually, can I change the refund to store credit?", "user-minjun")
            memoryStore.addMessage(sessionId, "assistant", "Of course! I've changed your refund to store credit of 89,000 KRW.", "user-minjun")

            // Prepare updated summary for the refresh call
            val updatedFacts = realisticFacts + StructuredFact(
                key = "refund_method_updated",
                value = "Store credit",
                category = FactCategory.DECISION
            )
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = realisticNarrative + " The customer later changed the refund method to store credit.",
                facts = updatedFacts,
                tokenCost = 400
            )

            // Second call: conversation grew, so splitIndex changed -> triggers re-summarization
            val refreshedHistory = manager.loadHistory(createCommand())

            // Now 34 messages, recentMessageCount=10, splitIndex=24
            // Previous summarizedUpToIndex was 22 (from 32-10), now needs 24
            coVerify(exactly = 2) { summaryService.summarize(any(), any()) }

            // Verify updated facts appear
            val factsText = (refreshedHistory[0] as SystemMessage).text
            assertTrue(factsText.contains("refund_method_updated") || factsText.contains("Store credit"),
                "Refreshed facts should contain updated refund method info, got: $factsText")
        }
    }

    @Nested
    inner class GracefulFallback {

        @Test
        fun `should fall back to takeLast when summaryService throws exception`() = runTest {
            val failingService = mockk<ConversationSummaryService>()
            coEvery { failingService.summarize(any(), any()) } throws RuntimeException(
                "LLM service unavailable: connection timeout"
            )

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, failingService
            )

            val history = manager.loadHistory(createCommand())

            // Fallback: takeLast(maxConversationTurns * 2) = takeLast(20)
            // 32 messages total, take last 20
            assertEquals(20, history.size,
                "Fallback should return takeLast(maxConversationTurns * 2 = 20), got ${history.size}")

            // Fallback should not contain any SystemMessage (no facts/narrative)
            val systemMessages = history.filterIsInstance<SystemMessage>()
            assertTrue(systemMessages.isEmpty(),
                "Fallback should not contain SystemMessages, found ${systemMessages.size}")
        }

        @Test
        fun `should preserve recent messages verbatim even in fallback mode`() = runTest {
            val failingService = mockk<ConversationSummaryService>()
            coEvery { failingService.summarize(any(), any()) } throws RuntimeException("LLM error")

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, failingService
            )

            val history = manager.loadHistory(createCommand())

            // The last message should still be the closing assistant message
            val lastMessage = history.last()
            assertTrue(lastMessage is AssistantMessage,
                "Last message in fallback should be AssistantMessage but was ${lastMessage::class.simpleName}")
            assertTrue((lastMessage as AssistantMessage).text.orEmpty().contains("Have a wonderful day!"),
                "Last message should contain closing greeting even in fallback mode")
        }

        @Test
        fun `should fall back to takeLast when summaryService throws OutOfMemoryError wrapped in RuntimeException`() = runTest {
            val failingService = mockk<ConversationSummaryService>()
            coEvery { failingService.summarize(any(), any()) } throws RuntimeException(
                "Summarization failed", OutOfMemoryError("Java heap space")
            )

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, failingService
            )

            val history = manager.loadHistory(createCommand())

            assertEquals(20, history.size,
                "Should fallback to takeLast even when cause is OOM wrapped in RuntimeException")
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should use takeLast when conversation is below trigger threshold`() = runTest {
            val smallMemoryStore = InMemoryMemoryStore()
            // Add only 10 messages (below triggerMessageCount=20)
            for (i in 1..10) {
                val role = if (i % 2 == 1) "user" else "assistant"
                smallMemoryStore.addMessage("small-session", role, "message $i", "user-1")
            }

            val manager = DefaultConversationManager(
                smallMemoryStore, agentProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "small-session")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size,
                "Below trigger threshold should return all messages via takeLast")
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
        }

        @Test
        fun `should handle conversation with exactly triggerMessageCount messages`() = runTest {
            val exactStore = InMemoryMemoryStore()
            // Add exactly 20 messages (== triggerMessageCount)
            for (i in 1..20) {
                val role = if (i % 2 == 1) "user" else "assistant"
                exactStore.addMessage("exact-session", role, "message $i", "user-1")
            }

            val manager = DefaultConversationManager(
                exactStore, agentProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "exact-session")
            )

            val history = manager.loadHistory(command)

            // allMessages.size (20) <= triggerMessageCount (20) -> takeLast path
            assertEquals(20, history.size,
                "Exactly at trigger threshold should use takeLast (uses <= comparison)")
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
        }

        @Test
        fun `should handle empty facts gracefully`() = runTest {
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = realisticNarrative,
                facts = emptyList(),
                tokenCost = 200
            )

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            // No facts -> only [Narrative SystemMessage] + [10 recent messages]
            assertEquals(11, history.size,
                "Empty facts should produce 1 narrative + 10 recent = 11 messages")
            assertTrue(history[0] is SystemMessage,
                "First message should be Narrative SystemMessage")
            assertTrue((history[0] as SystemMessage).text.contains("Conversation Summary:"),
                "Only SystemMessage should be the narrative")
        }

        @Test
        fun `should handle empty narrative gracefully`() = runTest {
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "",
                facts = realisticFacts,
                tokenCost = 100
            )

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            // No narrative -> only [Facts SystemMessage] + [10 recent messages]
            assertEquals(11, history.size,
                "Empty narrative should produce 1 facts + 10 recent = 11 messages")
            assertTrue(history[0] is SystemMessage,
                "First message should be Facts SystemMessage")
            assertTrue((history[0] as SystemMessage).text.contains("Conversation Facts:"),
                "Only SystemMessage should be the facts")
        }

        @Test
        fun `should handle both empty facts and empty narrative`() = runTest {
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "",
                facts = emptyList(),
                tokenCost = 0
            )

            val manager = DefaultConversationManager(
                memoryStore, agentProperties, summaryStore, summaryService
            )

            val history = manager.loadHistory(createCommand())

            // No facts, no narrative -> only [10 recent messages]
            assertEquals(10, history.size,
                "Both empty should produce only 10 recent messages")
            val systemMessages = history.filterIsInstance<SystemMessage>()
            assertTrue(systemMessages.isEmpty(),
                "Should have no SystemMessages when both facts and narrative are empty")
        }
    }
}
