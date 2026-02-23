package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.MemoryProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.config.SummaryProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.memory.summary.ConversationSummary
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.memory.summary.FactCategory
import com.arc.reactor.memory.summary.InMemoryConversationSummaryStore
import com.arc.reactor.memory.summary.StructuredFact
import com.arc.reactor.memory.summary.SummarizationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.SystemMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationManagerTest {

    private val properties = AgentProperties(
        llm = LlmProperties(maxConversationTurns = 5),
        guard = GuardProperties(),
        rag = RagProperties(),
        concurrency = ConcurrencyProperties()
    )

    @Nested
    inner class LoadHistory {

        @Test
        fun `should use conversationHistory from command when provided`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                conversationHistory = listOf(
                    Message(MessageRole.USER, "previous question"),
                    Message(MessageRole.ASSISTANT, "previous answer")
                )
            )

            val history = manager.loadHistory(command)

            assertEquals(2, history.size, "Should return provided conversation history")
        }

        @Test
        fun `should load from memoryStore when no conversationHistory`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory
            every { memory.getHistory() } returns listOf(
                Message(MessageRole.USER, "q1"),
                Message(MessageRole.ASSISTANT, "a1"),
                Message(MessageRole.USER, "q2"),
                Message(MessageRole.ASSISTANT, "a2")
            )

            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertEquals(4, history.size, "Should load all messages from memory")
        }

        @Test
        fun `should limit history by maxConversationTurns`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("session-1") } returns memory

            // 20 messages (10 turns), but maxConversationTurns=5 means max 10 messages
            val messages = (1..20).map { i ->
                if (i % 2 == 1) Message(MessageRole.USER, "q$i")
                else Message(MessageRole.ASSISTANT, "a$i")
            }
            every { memory.getHistory() } returns messages

            val props = properties.copy(llm = properties.llm.copy(maxConversationTurns = 5))
            val manager = DefaultConversationManager(memoryStore, props)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size, "Should limit to maxConversationTurns * 2")
        }

        @Test
        fun `should return empty list when no sessionId and no conversationHistory`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(systemPrompt = "", userPrompt = "hello")

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty(), "Should return empty when no session and no history")
        }

        @Test
        fun `should return empty list when memoryStore is null`() = runTest {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "session-1")
            )

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty(), "Should return empty when memoryStore is null")
        }
    }

    @Nested
    inner class SaveHistory {

        @Test
        fun `should save user and assistant messages on success`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "what is AI?",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.success("AI is artificial intelligence")

            manager.saveHistory(command, result)

            verify { memoryStore.addMessage("s1", "user", "what is AI?", "anonymous") }
            verify { memoryStore.addMessage("s1", "assistant", "AI is artificial intelligence", "anonymous") }
        }

        @Test
        fun `should not save on failure`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.failure("error")

            manager.saveHistory(command, result)

            verify(exactly = 0) { memoryStore.addMessage(any(), any(), any(), any()) }
        }

        @Test
        fun `should not throw when memoryStore save fails`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            every { memoryStore.addMessage(any(), any(), any(), any()) } throws RuntimeException("DB error")
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )
            val result = AgentResult.success("ok")

            assertDoesNotThrow({
                kotlinx.coroutines.runBlocking { manager.saveHistory(command, result) }
            }, "Should handle memoryStore errors gracefully")
        }
    }

    @Nested
    inner class SaveStreamingHistory {

        @Test
        fun `should save streaming content`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "tell me a story",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "Once upon a time...")

            verify { memoryStore.addMessage("s1", "user", "tell me a story", "anonymous") }
            verify { memoryStore.addMessage("s1", "assistant", "Once upon a time...", "anonymous") }
        }

        @Test
        fun `should not save empty streaming content as assistant message`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hello",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "")

            verify { memoryStore.addMessage("s1", "user", "hello", "anonymous") }
            verify(exactly = 0) { memoryStore.addMessage("s1", "assistant", any(), any()) }
        }
    }

    @Nested
    inner class HierarchicalMemory {

        private val summaryProps = SummaryProperties(
            enabled = true,
            triggerMessageCount = 6,
            recentMessageCount = 4,
            maxNarrativeTokens = 500
        )

        private val summaryEnabledProperties = properties.copy(
            memory = MemoryProperties(summary = summaryProps)
        )

        @Test
        fun `should use takeLast when summary is disabled`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(30)

            val manager = DefaultConversationManager(memoryStore, properties)
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size, "Should use takeLast when summary disabled")
        }

        @Test
        fun `should use takeLast when message count below trigger`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(4)

            val manager = DefaultConversationManager(memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = mockk())
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(4, history.size, "Should use takeLast when below trigger count")
        }

        @Test
        fun `should build hierarchical history with facts and narrative`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10) // above trigger (6)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "User discussed order details",
                facts = listOf(
                    StructuredFact(key = "order_id", value = "#1234", category = FactCategory.ENTITY)
                )
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // Expected: [Facts SystemMessage] + [Narrative SystemMessage] + [4 recent messages]
            assertEquals(6, history.size, "Should have 2 system messages + 4 recent messages")
            assertTrue(history[0] is SystemMessage, "First message should be Facts SystemMessage")
            assertTrue((history[0] as SystemMessage).text.contains("order_id"),
                "Facts should contain order_id")
            assertTrue(history[1] is SystemMessage, "Second message should be Narrative SystemMessage")
            assertTrue((history[1] as SystemMessage).text.contains("order details"),
                "Narrative should contain summary text")
        }

        @Test
        fun `should reuse cached summary when up to date`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            // Pre-populate cached summary covering messages 0..5 (splitIndex = 10 - 4 = 6)
            summaryStore.save(ConversationSummary(
                sessionId = "s1",
                narrative = "Cached summary",
                facts = listOf(StructuredFact(key = "cached", value = "true")),
                summarizedUpToIndex = 6
            ))

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // Should use cached summary without calling summaryService
            assertEquals(6, history.size, "Should use cached summary")
            assertTrue((history[0] as SystemMessage).text.contains("cached"),
                "Should use cached facts")
        }

        @Test
        fun `should fallback to takeLast on summarization failure`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            coEvery { summaryService.summarize(any(), any()) } throws RuntimeException("LLM error")

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            assertEquals(10, history.size,
                "Should fallback to takeLast (maxConversationTurns=5 * 2=10)")
        }

        @Test
        fun `should handle empty facts in hierarchical history`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns generateMessages(10)

            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "Just a narrative",
                facts = emptyList()
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // No facts SystemMessage, just [Narrative SystemMessage] + [4 recent messages]
            assertEquals(5, history.size, "Should have 1 narrative message + 4 recent messages")
            assertTrue(history[0] is SystemMessage, "First should be Narrative SystemMessage")
        }

        @Test
        fun `should trigger async summarization from saveStreamingHistory`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            // Pre-populate 20 messages (above trigger of 6)
            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            val latch = CountDownLatch(1)
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                latch.countDown()
                SummarizationResult(narrative = "Streamed summary", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "streaming msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveStreamingHistory(command, "response content")

            assertTrue(latch.await(5, TimeUnit.SECONDS)) {
                "saveStreamingHistory should trigger async summarization"
            }
            manager.destroy()
        }

        @Test
        fun `should cancel previous summarization job for same session on rapid saves`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            val secondCallLatch = CountDownLatch(1)
            var callCount = 0
            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) {
                    delay(5000) // First call blocks long
                }
                secondCallLatch.countDown()
                SummarizationResult(narrative = "summary-$callCount", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            // Trigger two rapid saves — second should cancel first
            manager.saveHistory(command, AgentResult.success("r1"))
            manager.saveHistory(command, AgentResult.success("r2"))

            assertTrue(secondCallLatch.await(3, TimeUnit.SECONDS)) {
                "Second summarization should complete (first should be cancelled)"
            }
            manager.destroy()
        }

        @Test
        fun `should not trigger async summarization when sessionId is missing`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            // No sessionId in metadata
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = emptyMap()
            )

            manager.saveHistory(command, AgentResult.success("response"))

            // Should never attempt summarization
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
            manager.destroy()
        }

        @Test
        fun `should not trigger async summarization when session memory is null`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            // memoryStore.get() returns null (e.g. session was deleted)
            every { memoryStore.get("s1") } returns null

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveHistory(command, AgentResult.success("response"))
            Thread.sleep(200) // Give async a chance to fire (it shouldn't)

            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
            manager.destroy()
        }

        @Test
        fun `should cancel asyncScope on destroy`() = runTest {
            val memoryStore = mockk<MemoryStore>(relaxed = true)
            val memory = mockk<ConversationMemory>()
            val summaryStore = mockk<ConversationSummaryStore>(relaxed = true)
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(20)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages
            every { summaryStore.get("s1") } returns null

            coEvery { summaryService.summarize(any(), any()) } coAnswers {
                delay(10000) // Long-running job
                SummarizationResult(narrative = "should not complete", facts = emptyList())
            }

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "msg",
                metadata = mapOf("sessionId" to "s1")
            )

            manager.saveHistory(command, AgentResult.success("r1"))
            Thread.sleep(100) // Let async job start

            // destroy should cancel all pending jobs
            manager.destroy()
            Thread.sleep(200) // Let cancellation propagate

            // Verify the summary was NOT saved (job was cancelled before completion)
            coVerify(exactly = 0) { summaryStore.save(any()) }
        }

        @Test
        fun `should fallback to takeLast when summary produces empty facts and narrative`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryStore = InMemoryConversationSummaryStore()
            val summaryService = mockk<ConversationSummaryService>()

            val messages = generateMessages(10)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            // LLM returns empty summary (no facts, blank narrative)
            coEvery { summaryService.summarize(any(), any()) } returns SummarizationResult(
                narrative = "",
                facts = emptyList()
            )

            val manager = DefaultConversationManager(
                memoryStore, summaryEnabledProperties, summaryStore, summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // Must fallback to takeLast, NOT return only recentMessageCount (4)
            assertEquals(10, history.size) {
                "Empty summary should fallback to takeLast (maxConversationTurns=5 * 2=10), not recentMessageCount=4"
            }
            assertFalse(history.any { it is SystemMessage }) {
                "Fallback path should not contain SystemMessages"
            }
        }

        @Test
        fun `should return all messages when recentMessageCount exceeds total messages`() = runTest {
            val memoryStore = mockk<MemoryStore>()
            val memory = mockk<ConversationMemory>()
            val summaryService = mockk<ConversationSummaryService>()

            // recentMessageCount=4 but only 3 messages above trigger
            // This requires a config where triggerMessageCount < recentMessageCount
            val misconfigProps = properties.copy(
                memory = MemoryProperties(summary = SummaryProperties(
                    enabled = true,
                    triggerMessageCount = 2,  // trigger at 2
                    recentMessageCount = 10,  // but recent window is 10
                    maxNarrativeTokens = 500
                ))
            )

            val messages = generateMessages(4) // 4 messages > trigger (2)
            every { memoryStore.get("s1") } returns memory
            every { memory.getHistory() } returns messages

            val manager = DefaultConversationManager(
                memoryStore, misconfigProps,
                summaryStore = InMemoryConversationSummaryStore(),
                summaryService = summaryService
            )
            val command = AgentCommand(
                systemPrompt = "",
                userPrompt = "hi",
                metadata = mapOf("sessionId" to "s1")
            )

            val history = manager.loadHistory(command)

            // splitIndex = max(4 - 10, 0) = 0 → early exit, return all messages
            assertEquals(4, history.size) {
                "When recentMessageCount >= totalMessages, should return all messages without summarizing"
            }
            // summarize should NOT be called
            coVerify(exactly = 0) { summaryService.summarize(any(), any()) }
        }

        private fun generateMessages(count: Int): List<Message> = (1..count).map { i ->
            if (i % 2 == 1) Message(MessageRole.USER, "q$i")
            else Message(MessageRole.ASSISTANT, "a$i")
        }
    }
}
