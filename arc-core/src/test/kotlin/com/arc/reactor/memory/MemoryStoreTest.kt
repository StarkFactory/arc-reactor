package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MemoryStoreTest {

    @Test
    fun `new session에 대해 create memory해야 한다`() {
        val store = InMemoryMemoryStore()

        val memory = store.getOrCreate("session-1")

        assertNotNull(memory) { "Memory for new session should not be null" }
        assertTrue(memory.getHistory().isEmpty()) { "New session should have empty history, got: ${memory.getHistory().size} messages" }
    }

    @Test
    fun `existing session에 대해 return same memory해야 한다`() {
        val store = InMemoryMemoryStore()

        val memory1 = store.getOrCreate("session-1")
        memory1.add(Message(MessageRole.USER, "Hello"))

        val memory2 = store.getOrCreate("session-1")

        assertSame(memory1, memory2) { "Same session ID should return the same memory instance" }
        assertEquals(1, memory2.getHistory().size) { "Memory should contain 1 message" }
    }

    @Test
    fun `nonexistent session에 대해 return null해야 한다`() {
        val store = InMemoryMemoryStore()

        val memory = store.get("nonexistent")

        assertNull(memory, "Nonexistent session should return null")
    }

    @Test
    fun `remove session해야 한다`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")

        store.remove("session-1")

        assertNull(store.get("session-1"), "Removed session should return null")
    }

    @Test
    fun `clear all sessions해야 한다`() {
        val store = InMemoryMemoryStore()
        store.getOrCreate("session-1")
        store.getOrCreate("session-2")

        store.clear()

        assertNull(store.get("session-1"), "session-1 should be null after clear")
        assertNull(store.get("session-2"), "session-2 should be null after clear")
    }

    @Test
    fun `max reached일 때 evict sessions해야 한다`() {
        val store = InMemoryMemoryStore(maxSessions = 2)

        store.getOrCreate("session-1")
        store.getOrCreate("session-2")
        store.getOrCreate("session-3")  // trigger eviction해야 합니다

        // Caffeine LRU: exactly maxSessions은(는) survive해야 합니다
        val surviving = listOf("session-1", "session-2", "session-3")
            .count { store.get(it) != null }
        assertEquals(2, surviving, "Exactly maxSessions entries should survive after eviction")
        // Most recently added은(는) survive해야 합니다
        assertNotNull(store.get("session-3"), "Most recently added session should survive eviction")
    }

    @Test
    fun `session is evicted일 때 clean up sessionOwners해야 한다`() {
        val store = InMemoryMemoryStore(maxSessions = 2)

        store.addMessage("session-1", "user", "Hello", "owner-1")
        store.addMessage("session-2", "user", "Hello", "owner-2")
        store.addMessage("session-3", "user", "Hello", "owner-3")

        // Caffeine cleanup so RemovalListener fires를 강제합니다
        Thread.sleep(50)
        store.getOrCreate("session-3") // triggers cleanUp()

        // session-3은(는) survive; evicted session owner should be cleaned up해야 합니다
        assertNotNull(store.get("session-3"), "Most recently added session should survive")
        assertEquals("owner-3", store.getSessionOwner("session-3"),
            "Surviving session owner should remain")

        // At least one of session-1/session-2 was evicted — its owner은(는) be gone해야 합니다
        val evictedOwners = listOf("session-1", "session-2")
            .count { store.get(it) == null && store.getSessionOwner(it) == null }
        assertTrue(evictedOwners >= 1,
            "Evicted sessions should have their owners cleaned up")
    }

    @Test
    fun `clean up sessionOwners on remove해야 한다`() {
        val store = InMemoryMemoryStore()

        store.addMessage("session-1", "user", "Hello", "owner-1")
        assertEquals("owner-1", store.getSessionOwner("session-1"),
            "Owner should be set after addMessage with userId")

        store.remove("session-1")

        assertNull(store.getSessionOwner("session-1"),
            "Owner should be removed after session removal")
    }

    @Test
    fun `clean up sessionOwners on clear해야 한다`() {
        val store = InMemoryMemoryStore()

        store.addMessage("session-1", "user", "Hello", "owner-1")
        store.addMessage("session-2", "user", "Hello", "owner-2")

        store.clear()

        assertNull(store.getSessionOwner("session-1"),
            "Owner should be removed after clear")
        assertNull(store.getSessionOwner("session-2"),
            "Owner should be removed after clear")
    }

    @Test
    fun `add message via helper method해야 한다`() {
        val store = InMemoryMemoryStore()

        store.addMessage("session-1", "user", "Hello")
        store.addMessage("session-1", "assistant", "Hi there!")

        val memory = store.get("session-1")
        assertNotNull(memory) { "Memory for session-1 should exist after addMessage calls" }
        assertEquals(2, memory!!.getHistory().size) { "Should have 2 messages after addMessage calls" }
        assertEquals(MessageRole.USER, memory.getHistory()[0].role) { "First message should be USER" }
        assertEquals(MessageRole.ASSISTANT, memory.getHistory()[1].role) { "Second message should be ASSISTANT" }
    }

    @Nested
    inner class ListSessions {

        @Test
        fun `no sessions일 때 return empty list해야 한다`() {
            val store = InMemoryMemoryStore()

            val sessions = store.listSessions()

            assertTrue(sessions.isEmpty()) { "Expected empty list, got ${sessions.size}" }
        }

        @Test
        fun `correct metadata로 list sessions해야 한다`() {
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
        fun `truncate preview to 50 chars해야 한다`() {
            val store = InMemoryMemoryStore()
            val longMessage = "A".repeat(60)
            store.addMessage("session-1", "user", longMessage)

            val sessions = store.listSessions()

            val expected = "A".repeat(50) + "..."
            assertEquals(expected, sessions[0].preview) { "Preview should be truncated with ellipsis" }
        }

        @Test
        fun `use first user message as preview해야 한다`() {
            val store = InMemoryMemoryStore()
            store.addMessage("session-1", "system", "System prompt")
            store.addMessage("session-1", "user", "First user message")
            store.addMessage("session-1", "user", "Second user message")

            val sessions = store.listSessions()

            assertEquals("First user message", sessions[0].preview) { "Preview should be first USER message" }
        }

        @Test
        fun `order by last activity descending해야 한다`() {
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
        fun `no user messages로 show Empty conversation for sessions해야 한다`() {
            val store = InMemoryMemoryStore()
            store.addMessage("session-1", "system", "You are a helpful assistant")

            val sessions = store.listSessions()

            assertEquals("Empty conversation", sessions[0].preview) { "Preview should be 'Empty conversation'" }
        }
    }
}

class ConversationMemoryTest {

    @Test
    fun `add and retrieve messages해야 한다`() {
        val memory = InMemoryConversationMemory()

        memory.add(Message(MessageRole.USER, "Hello"))
        memory.add(Message(MessageRole.ASSISTANT, "Hi!"))

        val history = memory.getHistory()
        assertEquals(2, history.size) { "Should have 2 messages" }
        assertEquals("Hello", history[0].content) { "First message content should be 'Hello'" }
        assertEquals("Hi!", history[1].content) { "Second message content should be 'Hi!'" }
    }

    @Test
    fun `limit message count해야 한다`() {
        val memory = InMemoryConversationMemory(maxMessages = 3)

        memory.add(Message(MessageRole.USER, "Message 1"))
        memory.add(Message(MessageRole.USER, "Message 2"))
        memory.add(Message(MessageRole.USER, "Message 3"))
        memory.add(Message(MessageRole.USER, "Message 4"))

        val history = memory.getHistory()
        assertEquals(3, history.size) { "Should retain maxMessages (3) after overflow" }
        assertEquals("Message 2", history[0].content) { "Oldest surviving message should be 'Message 2'" }
        assertEquals("Message 4", history[2].content) { "Newest message should be 'Message 4'" }
    }

    @Test
    fun `clear history해야 한다`() {
        val memory = InMemoryConversationMemory()
        memory.add(Message(MessageRole.USER, "Hello"))

        memory.clear()

        assertTrue(memory.getHistory().isEmpty()) { "History should be empty after clear(), got: ${memory.getHistory().size} messages" }
    }

    @Test
    fun `get history within token limit해야 한다`() {
        val memory = InMemoryConversationMemory()

        // messages with varying lengths를 추가합니다
        memory.add(Message(MessageRole.USER, "A".repeat(100))) // ~25 tokens
        memory.add(Message(MessageRole.USER, "B".repeat(100))) // ~25 tokens
        memory.add(Message(MessageRole.USER, "C".repeat(100))) // ~25 tokens

        // Get history with small token limit
        val limitedHistory = memory.getHistoryWithinTokenLimit(40)  // fit ~1-2 messages해야 합니다

        assertTrue(limitedHistory.size < 3) { "Expected fewer than 3 messages within token limit, got: ${limitedHistory.size}" }
        // return most recent messages that fit해야 합니다
        assertTrue(limitedHistory.last().content.startsWith("C")) { "Last message should start with 'C', got: '${limitedHistory.last().content.take(10)}'" }
    }

    @Test
    fun `use custom token estimator해야 한다`() {
        val fixedEstimator = TokenEstimator { 10 }  // Always 10 tokens
        val memory = InMemoryConversationMemory(tokenEstimator = fixedEstimator)

        memory.add(Message(MessageRole.USER, "Short"))
        memory.add(Message(MessageRole.USER, "Medium message"))
        memory.add(Message(MessageRole.USER, "Longer message content"))

        val limited = memory.getHistoryWithinTokenLimit(25)
        assertEquals(2, limited.size) { "2 messages * 10 tokens = 20 fits, 3 * 10 = 30 exceeds limit of 25" }
    }

    @Test
    fun `default estimator은(는) handle CJK characters해야 한다`() {
        val estimator = DefaultTokenEstimator()
        val koreanText = "안녕하세요"  // 5 Hangul characters
        val latinText = "Hello"        // 5 ASCII characters

        val koreanTokens = estimator.estimate(koreanText)
        val latinTokens = estimator.estimate(latinText)

        assertTrue(koreanTokens > latinTokens,
            "CJK text should produce more tokens: $koreanTokens vs $latinTokens")
    }

    @Test
    fun `default estimator은(는) return at least 1 for non-empty text해야 한다`() {
        val estimator = DefaultTokenEstimator()

        assertTrue(estimator.estimate("a") >= 1) { "Expected at least 1 token for 'a', got: ${estimator.estimate("a")}" }
        assertTrue(estimator.estimate("한") >= 1) { "Expected at least 1 token for '한', got: ${estimator.estimate("한")}" }
        assertEquals(0, estimator.estimate("")) { "Empty string should produce 0 tokens" }
    }

    @Test
    fun `use extension functions해야 한다`() {
        val memory = InMemoryConversationMemory()

        memory.addUserMessage("User message")
        memory.addAssistantMessage("Assistant message")
        memory.addSystemMessage("System message")

        val history = memory.getHistory()
        assertEquals(3, history.size) { "Should have 3 messages from extension functions" }
        assertEquals(MessageRole.USER, history[0].role) { "First message should be USER" }
        assertEquals(MessageRole.ASSISTANT, history[1].role) { "Second message should be ASSISTANT" }
        assertEquals(MessageRole.SYSTEM, history[2].role) { "Third message should be SYSTEM" }
    }
}
