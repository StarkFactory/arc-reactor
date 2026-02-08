package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
        fun `should use conversationHistory from command when provided`() {
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
        fun `should load from memoryStore when no conversationHistory`() {
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
        fun `should limit history by maxConversationTurns`() {
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
        fun `should return empty list when no sessionId and no conversationHistory`() {
            val manager = DefaultConversationManager(memoryStore = null, properties = properties)
            val command = AgentCommand(systemPrompt = "", userPrompt = "hello")

            val history = manager.loadHistory(command)

            assertTrue(history.isEmpty(), "Should return empty when no session and no history")
        }

        @Test
        fun `should return empty list when memoryStore is null`() {
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
        fun `should save user and assistant messages on success`() {
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
        fun `should not save on failure`() {
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
        fun `should not throw when memoryStore save fails`() {
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
                manager.saveHistory(command, result)
            }, "Should handle memoryStore errors gracefully")
        }
    }

    @Nested
    inner class SaveStreamingHistory {

        @Test
        fun `should save streaming content`() {
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
        fun `should not save empty streaming content as assistant message`() {
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
}
