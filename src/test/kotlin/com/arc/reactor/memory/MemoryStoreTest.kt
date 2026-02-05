package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemoryStoreTest {

    @Test
    fun `should create memory for new session`() {
        val store = InMemoryMemoryStore()

        val memory = store.getOrCreate("session-1")

        assertNotNull(memory)
        assertTrue(memory.getHistory().isEmpty())
    }

    @Test
    fun `should return same memory for existing session`() {
        val store = InMemoryMemoryStore()

        val memory1 = store.getOrCreate("session-1")
        memory1.add(Message(MessageRole.USER, "Hello"))

        val memory2 = store.getOrCreate("session-1")

        assertSame(memory1, memory2)
        assertEquals(1, memory2.getHistory().size)
    }

    @Test
    fun `should return null for nonexistent session`() {
        val store = InMemoryMemoryStore()

        val memory = store.get("nonexistent")

        assertNull(memory)
    }

    @Test
    fun `should remove session`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")

        store.remove("session-1")

        assertNull(store.get("session-1"))
    }

    @Test
    fun `should clear all sessions`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")
        store.getOrCreate("session-2")

        store.clear()

        assertNull(store.get("session-1"))
        assertNull(store.get("session-2"))
    }

    @Test
    fun `should evict oldest session when max reached`() {
        val store = InMemoryMemoryStore(maxSessions = 2)

        store.getOrCreate("session-1")
        store.getOrCreate("session-2")
        store.getOrCreate("session-3") // Should evict session-1

        assertNull(store.get("session-1"))
        assertNotNull(store.get("session-2"))
        assertNotNull(store.get("session-3"))
    }

    @Test
    fun `should add message via helper method`() {
        val store = InMemoryMemoryStore()

        store.addMessage("session-1", "user", "Hello")
        store.addMessage("session-1", "assistant", "Hi there!")

        val memory = store.get("session-1")
        assertNotNull(memory)
        assertEquals(2, memory!!.getHistory().size)
        assertEquals(MessageRole.USER, memory.getHistory()[0].role)
        assertEquals(MessageRole.ASSISTANT, memory.getHistory()[1].role)
    }
}

class ConversationMemoryTest {

    @Test
    fun `should add and retrieve messages`() {
        val memory = InMemoryConversationMemory()

        memory.add(Message(MessageRole.USER, "Hello"))
        memory.add(Message(MessageRole.ASSISTANT, "Hi!"))

        val history = memory.getHistory()
        assertEquals(2, history.size)
        assertEquals("Hello", history[0].content)
        assertEquals("Hi!", history[1].content)
    }

    @Test
    fun `should limit message count`() {
        val memory = InMemoryConversationMemory(maxMessages = 3)

        memory.add(Message(MessageRole.USER, "Message 1"))
        memory.add(Message(MessageRole.USER, "Message 2"))
        memory.add(Message(MessageRole.USER, "Message 3"))
        memory.add(Message(MessageRole.USER, "Message 4"))

        val history = memory.getHistory()
        assertEquals(3, history.size)
        assertEquals("Message 2", history[0].content)
        assertEquals("Message 4", history[2].content)
    }

    @Test
    fun `should clear history`() {
        val memory = InMemoryConversationMemory()
        memory.add(Message(MessageRole.USER, "Hello"))

        memory.clear()

        assertTrue(memory.getHistory().isEmpty())
    }

    @Test
    fun `should get history within token limit`() {
        val memory = InMemoryConversationMemory()

        // Add messages with varying lengths
        memory.add(Message(MessageRole.USER, "A".repeat(100))) // ~25 tokens
        memory.add(Message(MessageRole.USER, "B".repeat(100))) // ~25 tokens
        memory.add(Message(MessageRole.USER, "C".repeat(100))) // ~25 tokens

        // Get history with small token limit
        val limitedHistory = memory.getHistoryWithinTokenLimit(40) // Should fit ~1-2 messages

        assertTrue(limitedHistory.size < 3)
        // Should return most recent messages that fit
        assertTrue(limitedHistory.last().content.startsWith("C"))
    }

    @Test
    fun `should use extension functions`() {
        val memory = InMemoryConversationMemory()

        memory.addUserMessage("User message")
        memory.addAssistantMessage("Assistant message")
        memory.addSystemMessage("System message")

        val history = memory.getHistory()
        assertEquals(3, history.size)
        assertEquals(MessageRole.USER, history[0].role)
        assertEquals(MessageRole.ASSISTANT, history[1].role)
        assertEquals(MessageRole.SYSTEM, history[2].role)
    }
}
