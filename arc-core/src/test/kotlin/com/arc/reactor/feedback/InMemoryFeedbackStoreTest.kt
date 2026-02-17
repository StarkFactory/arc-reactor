package com.arc.reactor.feedback

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class InMemoryFeedbackStoreTest {

    private lateinit var store: InMemoryFeedbackStore

    @BeforeEach
    fun setup() {
        store = InMemoryFeedbackStore()
    }

    private fun createFeedback(
        feedbackId: String = "fb-1",
        query: String = "What is AI?",
        response: String = "AI is artificial intelligence.",
        rating: FeedbackRating = FeedbackRating.THUMBS_UP,
        timestamp: Instant = Instant.now(),
        comment: String? = null,
        sessionId: String? = null,
        intent: String? = null
    ) = Feedback(
        feedbackId = feedbackId,
        query = query,
        response = response,
        rating = rating,
        timestamp = timestamp,
        comment = comment,
        sessionId = sessionId,
        intent = intent
    )

    @Nested
    inner class BasicCrud {

        @Test
        fun `should start empty`() {
            assertEquals(0L, store.count()) { "New store should have 0 entries" }
            assertTrue(store.list().isEmpty()) { "New store should return empty list" }
        }

        @Test
        fun `should save and retrieve feedback`() {
            val feedback = createFeedback(feedbackId = "fb-1", comment = "Great answer!")

            store.save(feedback)
            val retrieved = store.get("fb-1")

            assertNotNull(retrieved) { "Saved feedback should be retrievable" }
            assertEquals("fb-1", retrieved!!.feedbackId) { "feedbackId should match" }
            assertEquals("What is AI?", retrieved.query) { "query should match" }
            assertEquals("AI is artificial intelligence.", retrieved.response) { "response should match" }
            assertEquals(FeedbackRating.THUMBS_UP, retrieved.rating) { "rating should match" }
            assertEquals("Great answer!", retrieved.comment) { "comment should match" }
        }

        @Test
        fun `should return null for nonexistent feedback`() {
            assertNull(store.get("nonexistent")) { "Should return null for unknown ID" }
        }

        @Test
        fun `should delete feedback`() {
            store.save(createFeedback(feedbackId = "to-delete"))

            store.delete("to-delete")

            assertNull(store.get("to-delete")) { "Deleted feedback should not be retrievable" }
            assertEquals(0L, store.count()) { "Count should be 0 after deletion" }
        }

        @Test
        fun `delete should be idempotent for nonexistent feedback`() {
            assertDoesNotThrow { store.delete("nonexistent") }
        }

        @Test
        fun `should count entries correctly`() {
            store.save(createFeedback(feedbackId = "fb-1"))
            store.save(createFeedback(feedbackId = "fb-2"))
            store.save(createFeedback(feedbackId = "fb-3"))

            assertEquals(3L, store.count()) { "Should count 3 entries" }
        }
    }

    @Nested
    inner class ListAndSort {

        @Test
        fun `should list all entries sorted by timestamp descending`() {
            val t1 = Instant.parse("2026-01-01T00:00:00Z")
            val t2 = Instant.parse("2026-01-02T00:00:00Z")
            val t3 = Instant.parse("2026-01-03T00:00:00Z")

            store.save(createFeedback(feedbackId = "old", timestamp = t1))
            store.save(createFeedback(feedbackId = "mid", timestamp = t2))
            store.save(createFeedback(feedbackId = "new", timestamp = t3))

            val list = store.list()

            assertEquals(3, list.size) { "Should have 3 entries" }
            assertEquals("new", list[0].feedbackId) { "Newest should be first" }
            assertEquals("mid", list[1].feedbackId) { "Middle should be second" }
            assertEquals("old", list[2].feedbackId) { "Oldest should be last" }
        }
    }

    @Nested
    inner class Filtering {

        private val t1 = Instant.parse("2026-01-01T00:00:00Z")
        private val t2 = Instant.parse("2026-01-02T00:00:00Z")
        private val t3 = Instant.parse("2026-01-03T00:00:00Z")

        @BeforeEach
        fun seedData() {
            store.save(createFeedback(
                feedbackId = "fb-1", rating = FeedbackRating.THUMBS_UP,
                timestamp = t1, intent = "order", sessionId = "s-1"
            ))
            store.save(createFeedback(
                feedbackId = "fb-2", rating = FeedbackRating.THUMBS_DOWN,
                timestamp = t2, intent = "refund", sessionId = "s-1"
            ))
            store.save(createFeedback(
                feedbackId = "fb-3", rating = FeedbackRating.THUMBS_DOWN,
                timestamp = t3, intent = "order", sessionId = "s-2"
            ))
        }

        @Test
        fun `should filter by rating`() {
            val result = store.list(rating = FeedbackRating.THUMBS_DOWN)

            assertEquals(2, result.size) { "Should have 2 thumbs_down entries" }
            assertTrue(result.all { it.rating == FeedbackRating.THUMBS_DOWN }) {
                "All entries should be THUMBS_DOWN"
            }
        }

        @Test
        fun `should filter by time range`() {
            val result = store.list(from = t2, to = t3)

            assertEquals(2, result.size) { "Should have 2 entries in range" }
            assertEquals("fb-3", result[0].feedbackId) { "Newest in range should be first" }
            assertEquals("fb-2", result[1].feedbackId) { "Older in range should be second" }
        }

        @Test
        fun `should filter by intent`() {
            val result = store.list(intent = "order")

            assertEquals(2, result.size) { "Should have 2 'order' intent entries" }
        }

        @Test
        fun `should filter by sessionId`() {
            val result = store.list(sessionId = "s-1")

            assertEquals(2, result.size) { "Should have 2 entries for session s-1" }
        }

        @Test
        fun `should combine multiple filters`() {
            val result = store.list(
                rating = FeedbackRating.THUMBS_DOWN,
                intent = "order"
            )

            assertEquals(1, result.size) { "Should have 1 entry matching both filters" }
            assertEquals("fb-3", result[0].feedbackId) { "Should be fb-3" }
        }

        @Test
        fun `should return all when no filters provided`() {
            val result = store.list(rating = null, from = null, to = null, intent = null, sessionId = null)

            assertEquals(3, result.size) { "Should return all entries without filters" }
        }

        @Test
        fun `should return empty list when no entries match`() {
            val result = store.list(rating = FeedbackRating.THUMBS_UP, intent = "refund")

            assertTrue(result.isEmpty()) { "Should return empty list when no entries match" }
        }

        @Test
        fun `should include exact boundary timestamps in range`() {
            val result = store.list(from = t1, to = t1)

            assertEquals(1, result.size) { "Should include entry at exact boundary timestamp" }
            assertEquals("fb-1", result[0].feedbackId) { "Should be the entry with exact timestamp" }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should filter on empty store`() {
            val result = store.list(rating = FeedbackRating.THUMBS_UP)

            assertTrue(result.isEmpty()) { "Filtering empty store should return empty list" }
        }

        @Test
        fun `should save and retrieve with all null optional fields`() {
            val minimal = Feedback(
                feedbackId = "minimal",
                query = "q",
                response = "r",
                rating = FeedbackRating.THUMBS_UP
            )
            store.save(minimal)

            val retrieved = store.get("minimal")
            assertNotNull(retrieved) { "Should retrieve minimal feedback" }
            assertNull(retrieved!!.comment) { "comment should be null" }
            assertNull(retrieved.sessionId) { "sessionId should be null" }
            assertNull(retrieved.runId) { "runId should be null" }
            assertNull(retrieved.toolsUsed) { "toolsUsed should be null" }
            assertNull(retrieved.tags) { "tags should be null" }
        }

        @Test
        fun `should save with toolsUsed containing special characters`() {
            val feedback = createFeedback(feedbackId = "special").copy(
                toolsUsed = listOf("fetch,data", "tool|pipe", "tool with spaces")
            )
            store.save(feedback)

            val retrieved = store.get("special")
            assertEquals(
                listOf("fetch,data", "tool|pipe", "tool with spaces"),
                retrieved?.toolsUsed
            ) { "toolsUsed with special characters should round-trip correctly" }
        }

        @Test
        fun `should overwrite existing entry with same feedbackId`() {
            store.save(createFeedback(feedbackId = "fb-1", comment = "First"))
            store.save(createFeedback(feedbackId = "fb-1", comment = "Second"))

            assertEquals(1L, store.count()) { "Should have 1 entry (overwritten)" }
            assertEquals("Second", store.get("fb-1")?.comment) { "Should have latest comment" }
        }
    }

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `concurrent saves should not lose entries`() {
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = AtomicInteger(0)

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        latch.await()
                        try {
                            store.save(createFeedback(feedbackId = "fb-$i"))
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }

                latch.countDown()
                for (future in futures) {
                    future.get()
                }

                assertEquals(0, errors.get()) { "No errors should occur during concurrent saves" }
                assertEquals(threadCount.toLong(), store.count()) {
                    "All $threadCount entries should be saved"
                }
            } finally {
                executor.shutdown()
            }
        }

        @Test
        fun `concurrent list should not fail during modifications`() {
            val threadCount = 20
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = AtomicInteger(0)

            // Pre-seed some data
            for (i in 1..10) {
                store.save(createFeedback(feedbackId = "seed-$i"))
            }

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        latch.await()
                        try {
                            // Half write, half read
                            if (i % 2 == 0) {
                                store.save(createFeedback(feedbackId = "concurrent-$i"))
                            } else {
                                store.list()
                            }
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }

                latch.countDown()
                for (future in futures) {
                    future.get()
                }

                assertEquals(0, errors.get()) {
                    "No errors should occur during concurrent read/write"
                }
            } finally {
                executor.shutdown()
            }
        }
    }
}
