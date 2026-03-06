package com.arc.reactor.agent.metrics

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class ResponseValueSummary(
    val observedResponses: Long = 0,
    val groundedResponses: Long = 0,
    val blockedResponses: Long = 0,
    val interactiveResponses: Long = 0,
    val scheduledResponses: Long = 0,
    val answerModeCounts: Map<String, Long> = emptyMap(),
    val toolFamilyCounts: Map<String, Long> = emptyMap()
)

data class MissingQueryInsight(
    val queryPreview: String,
    val count: Long,
    val lastOccurredAt: Instant,
    val blockReason: String? = null
)

internal fun normalizeMissingQueryKey(queryPreview: String): String {
    return queryPreview
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
}

internal data class MissingQueryAggregate(
    val queryPreview: String,
    val blockReason: String?,
    val count: AtomicLong = AtomicLong(),
    @Volatile var lastOccurredAt: Instant = Instant.now()
)
