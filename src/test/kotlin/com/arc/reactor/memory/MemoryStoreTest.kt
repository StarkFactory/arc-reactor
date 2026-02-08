package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemoryStoreTest {

    @Test
    fun `should create memory for new session`() {
        val store = InMemoryMemoryStore()

        val memory = store.getOrCreate("session-1")

        assertNotNull(memory) { "Memory for new session should not be null" }
        assertTrue(memory.getHistory().isEmpty()) { "New session should have empty history, got: ${memory.getHistory().size} messages" }
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

        assertNull(memory, "Nonexistent session should return null")
    }

    @Test
    fun `should remove session`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")

        store.remove("session-1")

        assertNull(store.get("session-1"), "Removed session should return null")
    }

    @Test
    fun `should clear all sessions`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")
        store.getOrCreate("session-2")

        store.clear()

        assertNull(store.get("session-1"), "session-1 should be null after clear")
        assertNull(store.get("session-2"), "session-2 should be null after clear")
    }

    @Test
    fun `should evict sessions when max reached`() {
        val store = InMemoryMemoryStore(maxSessions = 2)

        store.getOrCreate("session-1")
        store.getOrCreate("session-2")
        store.getOrCreate("session-3") // Should trigger eviction

        // Caffeine LRU: exactly maxSessions should survive
        val surviving = listOf("session-1", "session-2", "session-3")
            .count { store.get(it) != null }
        assertEquals(2, surviving, "Exactly maxSessions entries should survive after eviction")
        // Most recently added should survive
        assertNotNull(store.get("session-3"), "Most recently added session should survive eviction")
    }

    @Test
    fun `should add message via helper method`() {
        val store = InMemoryMemoryStore()

        store.addMessage("session-1", "user", "Hello")
        store.addMessage("session-1", "assistant", "Hi there!")

        val memory = store.get("session-1")
        assertNotNull(memory) { "Memory for session-1 should exist after addMessage calls" }
        assertEquals(2, memory!!.getHistory().size)
        assertEquals(MessageRole.USER, memory.getHistory()[0].role)
        assertEquals(MessageRole.ASSISTANT, memory.getHistory()[1].role)
    }

    @Nested
    inner class ListSessions {

        @Test
        fun `should return empty list when no sessions`() {
            val store = InMemoryMemoryStore()

            val sessions = store.listSessions()

            assertTrue(sessions.isEmpty()) { "Expected empty list, got ${sessions.size}" }
        }

        @Test
        fun `should list sessions with correct metadata`() {
            val store = InMemoryMemoryStore()
            store.addMessage("session-1", "user", "Hello world")
            store.addMessage("session-1", "assistant", "Hi!")

            val sessions = store.listSessions()

            assertEquals(1, sessions.size) { "Expected 1 session" }
            val session = sessions[0]
            assertEquals("session-1", session.sessionId) { "Session ID should match" }
            assertEquals(2, session.messageCount) { "Should have 2 messages" }
            assertEquals("Hello world", session.preview) { "Preview should be first user message" }
        }

        @Test
        fun `should truncate preview to 50 chars`() {
            val store = InMemoryMemoryStore()
            val longMessage = "A".repeat(60)
            store.addMessage("session-1", "user", longMessage)

            val sessions = store.listSessions()

            val expected = "A".repeat(50) + "..."
            assertEquals(expected, sessions[0].preview) { "Preview should be truncated with ellipsis" }
        }

        @Test
        fun `should use first user message as preview`() {
            val store = InMemoryMemoryStore()
            store.addMessage("session-1", "system", "System prompt")
            store.addMessage("session-1", "user", "First user message")
            store.addMessage("session-1", "user", "Second user message")

            val sessions = store.listSessions()

            assertEquals("First user message", sessions[0].preview) { "Preview should be first USER message" }
        }

        @Test
        fun `should order by last activity descending`() {
            val store = InMemoryMemoryStore()
            store.addMessage("old-session", "user", "Old")
            Thread.sleep(10) // Ensure different timestamps
            store.addMessage("new-session", "user", "New")

            val sessions = store.listSessions()

            assertEquals(2, sessions.size) { "Expected 2 sessions" }
            assertEquals("new-session", sessions[0].sessionId) { "Newest session should be first" }
            assertEquals("old-session", sessions[1].sessionId) { "Oldest session should be last" }
        }

        @Test
        fun `should show Empty conversation for sessions with no user messages`() {
            val store = InMemoryMemoryStore()
            store.addMessage("session-1", "system", "You are a helpful assistant")

            val sessions = store.listSessions()

            assertEquals("Empty conversation", sessions[0].preview) { "Preview should be 'Empty conversation'" }
        }
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

        assertTrue(memory.getHistory().isEmpty()) { "History should be empty after clear(), got: ${memory.getHistory().size} messages" }
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

        assertTrue(limitedHistory.size < 3) { "Expected fewer than 3 messages within token limit, got: ${limitedHistory.size}" }
        // Should return most recent messages that fit
        assertTrue(limitedHistory.last().content.startsWith("C")) { "Last message should start with 'C', got: '${limitedHistory.last().content.take(10)}'" }
    }

    @Test
    fun `should use custom token estimator`() {
        val fixedEstimator = TokenEstimator { 10 }  // Always 10 tokens
        val memory = InMemoryConversationMemory(tokenEstimator = fixedEstimator)

        memory.add(Message(MessageRole.USER, "Short"))
        memory.add(Message(MessageRole.USER, "Medium message"))
        memory.add(Message(MessageRole.USER, "Longer message content"))

        val limited = memory.getHistoryWithinTokenLimit(25)
        assertEquals(2, limited.size) // 2 messages * 10 tokens = 20, 3 would be 30 > 25
    }

    @Test
    fun `default estimator should handle CJK characters`() {
        val estimator = DefaultTokenEstimator()
        val koreanText = "안녕하세요"  // 5 Hangul characters
        val latinText = "Hello"        // 5 ASCII characters

        val koreanTokens = estimator.estimate(koreanText)
        val latinTokens = estimator.estimate(latinText)

        assertTrue(koreanTokens > latinTokens,
            "CJK text should produce more tokens: $koreanTokens vs $latinTokens")
    }

    @Test
    fun `default estimator should return at least 1 for non-empty text`() {
        val estimator = DefaultTokenEstimator()

        assertTrue(estimator.estimate("a") >= 1) { "Expected at least 1 token for 'a', got: ${estimator.estimate("a")}" }
        assertTrue(estimator.estimate("한") >= 1) { "Expected at least 1 token for '한', got: ${estimator.estimate("한")}" }
        assertEquals(0, estimator.estimate(""))
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
