package com.arc.reactor.feedback

import java.time.Instant
import java.util.UUID

/**
 * Feedback rating — thumbs up or thumbs down.
 */
enum class FeedbackRating {
    THUMBS_UP, THUMBS_DOWN
}

/**
 * User feedback on an agent response.
 *
 * Conforms to eval-testing `schemas/feedback.schema.json` data contract.
 * Fields beyond rating/comment capture execution metadata for offline evaluation.
 *
 * @param feedbackId Unique identifier (UUID)
 * @param query User's original prompt
 * @param response Agent response (JSON string)
 * @param rating Thumbs up or down
 * @param timestamp When the feedback was submitted
 * @param comment Optional free-text comment
 * @param sessionId Conversation session ID
 * @param runId Agent execution run ID
 * @param userId User who submitted the feedback
 * @param intent Classified intent of the query
 * @param domain Business domain (e.g. "order", "refund")
 * @param model LLM model used for the response
 * @param promptVersion Prompt template version number
 * @param toolsUsed List of tools invoked during execution
 * @param durationMs Total execution duration in milliseconds
 * @param tags Arbitrary tags for filtering
 */
data class Feedback(
    val feedbackId: String = UUID.randomUUID().toString(),
    val query: String,
    val response: String,
    val rating: FeedbackRating,
    val timestamp: Instant = Instant.now(),
    val comment: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val userId: String? = null,
    val intent: String? = null,
    val domain: String? = null,
    val model: String? = null,
    val promptVersion: Int? = null,
    val toolsUsed: List<String>? = null,
    val durationMs: Long? = null,
    val tags: List<String>? = null
)
