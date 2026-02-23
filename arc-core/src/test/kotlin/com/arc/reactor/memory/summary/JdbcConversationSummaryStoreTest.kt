package com.arc.reactor.memory.summary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcConversationSummaryStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcConversationSummaryStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summaries (
                session_id       VARCHAR(255) PRIMARY KEY,
                narrative        TEXT         NOT NULL,
                facts_json       TEXT         NOT NULL DEFAULT '[]',
                summarized_up_to INT          NOT NULL DEFAULT 0,
                created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        store = JdbcConversationSummaryStore(jdbcTemplate)
    }

    @Nested
    inner class BasicOperations {

        @Test
        fun `should return null for unknown session`() {
            assertNull(store.get("unknown"), "Unknown session should return null")
        }

        @Test
        fun `should save and retrieve summary`() {
            val summary = ConversationSummary(
                sessionId = "s1",
                narrative = "User asked about refund policy",
                facts = listOf(
                    StructuredFact(key = "order_number", value = "#1234", category = FactCategory.ENTITY),
                    StructuredFact(key = "amount", value = "50000", category = FactCategory.NUMERIC)
                ),
                summarizedUpToIndex = 10
            )

            store.save(summary)
            val result = store.get("s1")

            assertNotNull(result, "Saved summary should be retrievable")
            assertEquals("s1", result!!.sessionId, "Session ID should match")
            assertEquals("User asked about refund policy", result.narrative, "Narrative should match")
            assertEquals(2, result.facts.size, "Should have two facts")
            assertEquals("order_number", result.facts[0].key, "First fact key should match")
            assertEquals("#1234", result.facts[0].value, "First fact value should match")
            assertEquals(FactCategory.ENTITY, result.facts[0].category, "First fact category should match")
            assertEquals(10, result.summarizedUpToIndex, "summarizedUpToIndex should match")
        }

        @Test
        fun `should delete summary`() {
            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "test",
                facts = emptyList(),
                summarizedUpToIndex = 5
            ))

            store.delete("s1")

            assertNull(store.get("s1"), "Deleted summary should not be retrievable")
        }
    }

    @Nested
    inner class UpsertBehavior {

        @Test
        fun `should update existing summary on save`() {
            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "original",
                facts = emptyList(),
                summarizedUpToIndex = 5
            ))

            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "updated",
                facts = listOf(StructuredFact(key = "k", value = "v")),
                summarizedUpToIndex = 15
            ))

            val result = store.get("s1")
            assertEquals("updated", result?.narrative, "Narrative should be updated")
            assertEquals(15, result?.summarizedUpToIndex, "Index should be updated")
            assertEquals(1, result?.facts?.size, "Facts should be updated")
        }

        @Test
        fun `should preserve timestamps on update`() {
            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "first",
                facts = emptyList(),
                summarizedUpToIndex = 5
            ))
            val first = store.get("s1")!!

            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "second",
                facts = emptyList(),
                summarizedUpToIndex = 10
            ))
            val second = store.get("s1")!!

            assertTrue(
                second.updatedAt >= first.createdAt,
                "updatedAt should be at or after original createdAt"
            )
        }
    }

    @Nested
    inner class FactsSerialization {

        @Test
        fun `should handle empty facts list`() {
            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "no facts",
                facts = emptyList(),
                summarizedUpToIndex = 3
            ))

            val result = store.get("s1")
            assertTrue(result?.facts?.isEmpty() == true, "Empty facts should round-trip correctly")
        }

        @Test
        fun `should handle all fact categories`() {
            val facts = FactCategory.entries.mapIndexed { i, category ->
                StructuredFact(key = "key$i", value = "val$i", category = category)
            }

            store.save(ConversationSummary(
                sessionId = "s1",
                narrative = "all categories",
                facts = facts,
                summarizedUpToIndex = 20
            ))

            val result = store.get("s1")
            assertEquals(FactCategory.entries.size, result?.facts?.size, "All categories should be preserved")
            for (category in FactCategory.entries) {
                assertTrue(
                    result?.facts?.any { it.category == category } == true,
                    "Category $category should be preserved"
                )
            }
        }
    }
}
