package com.arc.reactor.memory.summary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryConversationSummaryStoreTest {

    private val store = InMemoryConversationSummaryStore()

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
                StructuredFact(key = "order_number", value = "#1234", category = FactCategory.ENTITY)
            ),
            summarizedUpToIndex = 10
        )

        store.save(summary)
        val result = store.get("s1")

        assertNotNull(result, "Saved summary should be retrievable")
        assertEquals("s1", result!!.sessionId, "Session ID should match")
        assertEquals("User asked about refund policy", result.narrative, "Narrative should match")
        assertEquals(1, result.facts.size, "Should have one fact")
        assertEquals("#1234", result.facts[0].value, "Fact value should match")
        assertEquals(10, result.summarizedUpToIndex, "summarizedUpToIndex should match")
    }

    @Test
    fun `should upsert on save`() {
        val original = ConversationSummary(
            sessionId = "s1",
            narrative = "original",
            facts = emptyList(),
            summarizedUpToIndex = 5
        )
        store.save(original)

        val updated = ConversationSummary(
            sessionId = "s1",
            narrative = "updated",
            facts = listOf(StructuredFact(key = "k", value = "v")),
            summarizedUpToIndex = 15,
            updatedAt = Instant.now()
        )
        store.save(updated)

        val result = store.get("s1")
        assertEquals("updated", result?.narrative, "Narrative should be updated")
        assertEquals(15, result?.summarizedUpToIndex, "Index should be updated")
        assertEquals(1, result?.facts?.size, "Facts should be updated")
    }

    @Test
    fun `should delete summary`() {
        store.save(
            ConversationSummary(
                sessionId = "s1",
                narrative = "test",
                facts = emptyList(),
                summarizedUpToIndex = 5
            )
        )

        store.delete("s1")

        assertNull(store.get("s1"), "Deleted summary should not be retrievable")
    }

    @Test
    fun `should handle delete of non-existent session`() {
        store.delete("non-existent") // should not throw
    }
}
