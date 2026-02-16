package com.arc.reactor.feedback

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Feedback Store Interface
 *
 * Manages CRUD operations for user feedback on agent responses.
 *
 * @see InMemoryFeedbackStore for default implementation
 */
interface FeedbackStore {

    /**
     * Save a new feedback entry.
     *
     * @return The saved feedback
     */
    fun save(feedback: Feedback): Feedback

    /**
     * Get a feedback entry by ID.
     *
     * @return Feedback if found, null otherwise
     */
    fun get(feedbackId: String): Feedback?

    /**
     * List all feedback entries, ordered by timestamp descending.
     */
    fun list(): List<Feedback>

    /**
     * List feedback entries with optional filters.
     * All filters are AND-combined. Null means "no filter on this field".
     *
     * @param rating Filter by rating
     * @param from Filter by timestamp >= from
     * @param to Filter by timestamp <= to
     * @param intent Filter by intent (exact match)
     * @param sessionId Filter by session ID (exact match)
     * @return Filtered list, ordered by timestamp descending
     */
    fun list(
        rating: FeedbackRating? = null,
        from: Instant? = null,
        to: Instant? = null,
        intent: String? = null,
        sessionId: String? = null
    ): List<Feedback>

    /**
     * Delete a feedback entry by ID. Idempotent — no error if not found.
     */
    fun delete(feedbackId: String)

    /**
     * Count total feedback entries.
     */
    fun count(): Long
}

/**
 * In-Memory Feedback Store
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Not persistent — data is lost on server restart.
 */
class InMemoryFeedbackStore : FeedbackStore {

    private val entries = ConcurrentHashMap<String, Feedback>()

    override fun save(feedback: Feedback): Feedback {
        entries[feedback.feedbackId] = feedback
        return feedback
    }

    override fun get(feedbackId: String): Feedback? = entries[feedbackId]

    override fun list(): List<Feedback> {
        return entries.values.toList().sortedByDescending { it.timestamp }
    }

    override fun list(
        rating: FeedbackRating?,
        from: Instant?,
        to: Instant?,
        intent: String?,
        sessionId: String?
    ): List<Feedback> {
        return entries.values.toList()
            .asSequence()
            .filter { rating == null || it.rating == rating }
            .filter { from == null || !it.timestamp.isBefore(from) }
            .filter { to == null || !it.timestamp.isAfter(to) }
            .filter { intent == null || it.intent == intent }
            .filter { sessionId == null || it.sessionId == sessionId }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    override fun delete(feedbackId: String) {
        entries.remove(feedbackId)
    }

    override fun count(): Long = entries.size.toLong()
}
