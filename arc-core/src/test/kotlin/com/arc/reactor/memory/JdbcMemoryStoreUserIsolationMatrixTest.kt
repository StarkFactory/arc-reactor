package com.arc.reactor.memory

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

@Tag("matrix")
class JdbcMemoryStoreUserIsolationMatrixTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcMemoryStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id VARCHAR(255) NOT NULL,
                role VARCHAR(20) NOT NULL,
                content TEXT NOT NULL,
                timestamp BIGINT NOT NULL,
                user_id VARCHAR(64) NOT NULL DEFAULT 'anonymous',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        store = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, maxMessagesPerSession = 7)
    }

    @Test
    fun `listSessionsByUserId and getSessionOwner should stay consistent across 240 writes`() {
        val sessions = (0 until 12).map { "session-$it" }
        val users = listOf("u-a", "u-b", "u-c")
        val ownerBySession = sessions.associateWith { session ->
            users[session.substringAfter("session-").toInt() % users.size]
        }
        val totalBySession = sessions.associateWith { 0 }.toMutableMap()

        repeat(240) { i ->
            val sessionId = sessions[i % sessions.size]
            val owner = ownerBySession.getValue(sessionId)
            store.addMessage(
                sessionId = sessionId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "msg-$i",
                userId = owner
            )
            totalBySession[sessionId] = totalBySession.getValue(sessionId) + 1
        }

        users.forEach { userId ->
            val sessionsForUser = store.listSessionsByUserId(userId)
            val expectedSessionIds = ownerBySession.filterValues { it == userId }.keys
            assertEquals(expectedSessionIds.size, sessionsForUser.size, "user=$userId session count mismatch")
            assertTrue(sessionsForUser.all { it.sessionId in expectedSessionIds }, "user=$userId unexpected session present")

            sessionsForUser.forEach { summary ->
                val expectedCount = minOf(totalBySession.getValue(summary.sessionId), 7)
                assertEquals(expectedCount, summary.messageCount, "session=${summary.sessionId} count mismatch")
                assertEquals(userId, store.getSessionOwner(summary.sessionId), "session=${summary.sessionId} owner mismatch")
            }
        }

        assertTrue(store.listSessionsByUserId("unknown-user").isEmpty())
    }

    @Test
    fun `default addMessage path should map owner to anonymous`() {
        store.addMessage("anon-session", "user", "hello")
        store.addMessage("anon-session", "assistant", "hi")

        assertEquals("anonymous", store.getSessionOwner("anon-session"))
        val sessions = store.listSessionsByUserId("anonymous")
        assertEquals(1, sessions.size)
        assertEquals("anon-session", sessions.first().sessionId)
        assertEquals(2, sessions.first().messageCount)
    }
}
