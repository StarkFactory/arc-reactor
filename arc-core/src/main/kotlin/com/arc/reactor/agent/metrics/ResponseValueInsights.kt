package com.arc.reactor.agent.metrics

import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class ResponseValueSummary(
    val observedResponses: Long = 0,
    val groundedResponses: Long = 0,
    val blockedResponses: Long = 0,
    val interactiveResponses: Long = 0,
    val scheduledResponses: Long = 0,
    val answerModeCounts: Map<String, Long> = emptyMap(),
    val channelCounts: Map<String, Long> = emptyMap(),
    val toolFamilyCounts: Map<String, Long> = emptyMap(),
    val laneSummaries: List<ResponseLaneSummary> = emptyList()
)

data class ResponseLaneSummary(
    val answerMode: String,
    val observedResponses: Long,
    val groundedResponses: Long,
    val blockedResponses: Long
)

data class MissingQueryInsight(
    val queryCluster: String,
    val queryLabel: String,
    val count: Long,
    val lastOccurredAt: Instant,
    val blockReason: String? = null
)

data class RedactedQuerySignal(
    val clusterId: String,
    val label: String
)

private val WHITESPACE_REGEX = Regex("\\s+")

private val SHA256_DIGEST: ThreadLocal<MessageDigest> =
    ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

fun redactQuerySignal(queryPreview: String): RedactedQuerySignal? {
    val normalized = normalizeQueryPreview(queryPreview)
    if (normalized.isBlank()) return null
    val clusterId = sha256Hex(normalized).take(12)
    val kind = if (normalized.endsWith("?")) "Question" else "Prompt"
    return RedactedQuerySignal(
        clusterId = clusterId,
        label = "$kind cluster $clusterId"
    )
}

private fun normalizeQueryPreview(queryPreview: String): String {
    return queryPreview
        .trim()
        .lowercase()
        .replace(WHITESPACE_REGEX, " ")
}

internal data class ResponseLaneAggregate(
    val observedResponses: AtomicLong = AtomicLong(),
    val groundedResponses: AtomicLong = AtomicLong(),
    val blockedResponses: AtomicLong = AtomicLong()
)

internal data class MissingQueryAggregate(
    val queryCluster: String,
    val queryLabel: String,
    val blockReason: String?,
    val count: AtomicLong = AtomicLong(),
    @Volatile var lastOccurredAt: Instant = Instant.now()
)

private fun sha256Hex(input: String): String {
    val digest = SHA256_DIGEST.get()
    digest.reset()
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
