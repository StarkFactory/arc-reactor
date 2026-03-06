package com.arc.reactor.agent.metrics

import java.time.Instant

/**
 * Read recent response trust events for operational dashboards.
 */
interface RecentTrustEventReader {
    fun recentTrustEvents(limit: Int = 20): List<RecentTrustEvent> = emptyList()
}

data class RecentTrustEvent(
    val occurredAt: Instant = Instant.now(),
    val type: String,
    val severity: String,
    val action: String? = null,
    val stage: String? = null,
    val reason: String? = null,
    val violation: String? = null,
    val policy: String? = null,
    val channel: String? = null,
    val runId: String? = null,
    val userId: String? = null,
    val queryPreview: String? = null
)
