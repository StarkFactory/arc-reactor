package com.arc.reactor.memory.summary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * InMemoryConversationSummaryStore에 대한 테스트.
 *
 * 인메모리 대화 요약 저장소의 동작을 검증합니다.
 */
class InMemoryConversationSummaryStoreTest {

    private val store = InMemoryConversationSummaryStore()

    @Test
    fun `unknown session에 대해 return null해야 한다`() {
        assertNull(store.get("unknown"), "Unknown session should return null")
    }

    @Test
    fun `save and retrieve summary해야 한다`() {
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
    fun `upsert on save해야 한다`() {
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
    fun `delete summary해야 한다`() {
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
    fun `handle delete of non-existent session해야 한다`() {
        store.delete("non-existent")  // not throw해야 합니다
    }
}
