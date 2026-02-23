package com.arc.reactor.memory.summary

import java.time.Instant

/**
 * A structured fact extracted from conversation history.
 *
 * Facts preserve precise information (numbers, conditions, decisions)
 * that might be lost in a narrative summary.
 *
 * @param key Fact identifier (e.g., "order_number", "agreed_price")
 * @param value Exact value (e.g., "#1234", "50,000 KRW")
 * @param category Semantic category for grouping
 * @param extractedAt When this fact was extracted
 */
data class StructuredFact(
    val key: String,
    val value: String,
    val category: FactCategory = FactCategory.GENERAL,
    val extractedAt: Instant = Instant.now()
)

/**
 * Semantic categories for structured facts.
 */
enum class FactCategory {
    /** Named entities (person, organization, product) */
    ENTITY,

    /** Decisions made during conversation */
    DECISION,

    /** Conditions or constraints agreed upon */
    CONDITION,

    /** Current state or status */
    STATE,

    /** Numeric values (prices, quantities, dates) */
    NUMERIC,

    /** Uncategorized facts */
    GENERAL
}

/**
 * Persisted conversation summary for a session.
 *
 * Combines a narrative summary (capturing flow and tone) with
 * structured facts (preserving precise data points).
 *
 * @param sessionId Session this summary belongs to
 * @param narrative Free-text summary of the conversation flow
 * @param facts List of extracted structured facts
 * @param summarizedUpToIndex Number of messages summarized (exclusive upper bound)
 * @param createdAt When this summary was first created
 * @param updatedAt When this summary was last updated
 */
data class ConversationSummary(
    val sessionId: String,
    val narrative: String,
    val facts: List<StructuredFact>,
    val summarizedUpToIndex: Int,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * Result of a summarization operation.
 *
 * @param narrative Free-text summary
 * @param facts Extracted structured facts
 * @param tokenCost Approximate token usage for the summarization call
 */
data class SummarizationResult(
    val narrative: String,
    val facts: List<StructuredFact>,
    val tokenCost: Int = 0
)
