package com.arc.reactor.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcMemoryStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcMemoryStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // Create table (H2-compatible version of the migration)
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id  VARCHAR(255)  NOT NULL,
                role        VARCHAR(20)   NOT NULL,
                content     TEXT          NOT NULL,
                timestamp   BIGINT        NOT NULL,
                created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        store = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, maxMessagesPerSession = 10)
    }

    @Test
    fun `should store and retrieve messages`() {
        store.addMessage("session-1", "user", "Hello!")
        store.addMessage("session-1", "assistant", "Hi there!")

        val memory = store.get("session-1")
        assertNotNull(memory)
        val history = memory!!.getHistory()
        assertEquals(2, history.size)
        assertEquals("Hello!", history[0].content)
        assertEquals("Hi there!", history[1].content)
    }

    @Test
    fun `should return null for non-existent session`() {
        val memory = store.get("non-existent")
        assertNull(memory)
    }

    @Test
    fun `should create session via getOrCreate`() {
        val memory = store.getOrCreate("new-session")
        assertNotNull(memory)
        assertEquals(0, memory.getHistory().size)
    }

    @Test
    fun `should add message via ConversationMemory add`() {
        val memory = store.getOrCreate("session-2")
        memory.add(com.arc.reactor.agent.model.Message(
            com.arc.reactor.agent.model.MessageRole.USER, "Test message"
        ))

        // Reload to verify persistence
        val reloaded = store.get("session-2")
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.getHistory().size)
        assertEquals("Test message", reloaded.getHistory()[0].content)
    }

    @Test
    fun `should remove session`() {
        store.addMessage("session-3", "user", "Hello!")
        assertNotNull(store.get("session-3"))

        store.remove("session-3")
        assertNull(store.get("session-3"))
    }

    @Test
    fun `should clear all sessions`() {
        store.addMessage("session-a", "user", "A")
        store.addMessage("session-b", "user", "B")

        store.clear()

        assertNull(store.get("session-a"))
        assertNull(store.get("session-b"))
    }

    @Test
    fun `should evict oldest messages when exceeding max per session`() {
        val smallStore = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, maxMessagesPerSession = 3)

        smallStore.addMessage("session-evict", "user", "Message 1")
        smallStore.addMessage("session-evict", "assistant", "Response 1")
        smallStore.addMessage("session-evict", "user", "Message 2")
        // Now at limit (3)
        smallStore.addMessage("session-evict", "assistant", "Response 2")
        // Should evict "Message 1"

        val memory = smallStore.get("session-evict")
        assertNotNull(memory)
        val history = memory!!.getHistory()
        assertEquals(3, history.size)
        assertEquals("Response 1", history[0].content)
        assertEquals("Message 2", history[1].content)
        assertEquals("Response 2", history[2].content)
    }

    @Test
    fun `should isolate sessions`() {
        store.addMessage("session-x", "user", "X message")
        store.addMessage("session-y", "user", "Y message")

        val xMemory = store.get("session-x")!!
        val yMemory = store.get("session-y")!!

        assertEquals(1, xMemory.getHistory().size)
        assertEquals("X message", xMemory.getHistory()[0].content)
        assertEquals(1, yMemory.getHistory().size)
        assertEquals("Y message", yMemory.getHistory()[0].content)
    }

    @Test
    fun `should return history within token limit`() {
        store.addMessage("session-tok", "user", "Short")
        store.addMessage("session-tok", "assistant", "Also short")
        store.addMessage("session-tok", "user", "A very long message that takes many tokens to represent")

        val memory = store.get("session-tok")!!

        // Very tight limit - should only return the latest message(s)
        val limited = memory.getHistoryWithinTokenLimit(maxTokens = 20)
        assertTrue(limited.isNotEmpty())
        // Last message should be included
        assertEquals("A very long message that takes many tokens to represent", limited.last().content)
    }

    @Test
    fun `should cleanup expired sessions`() {
        // Add messages with old timestamps (manually)
        val oldTimestamp = System.currentTimeMillis() - 100_000 // 100 seconds ago
        jdbcTemplate.update(
            "INSERT INTO conversation_messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
            "old-session", "user", "Old message", oldTimestamp
        )
        store.addMessage("new-session", "user", "New message")

        // Cleanup sessions older than 50 seconds
        val cleaned = store.cleanupExpiredSessions(ttlMs = 50_000)

        assertEquals(1, cleaned)
        assertNull(store.get("old-session"))
        assertNotNull(store.get("new-session"))
    }

    @Test
    fun `should preserve message roles correctly`() {
        store.addMessage("session-roles", "user", "User msg")
        store.addMessage("session-roles", "assistant", "Assistant msg")
        store.addMessage("session-roles", "system", "System msg")
        store.addMessage("session-roles", "tool", "Tool msg")

        val history = store.get("session-roles")!!.getHistory()
        assertEquals(com.arc.reactor.agent.model.MessageRole.USER, history[0].role)
        assertEquals(com.arc.reactor.agent.model.MessageRole.ASSISTANT, history[1].role)
        assertEquals(com.arc.reactor.agent.model.MessageRole.SYSTEM, history[2].role)
        assertEquals(com.arc.reactor.agent.model.MessageRole.TOOL, history[3].role)
    }
}
