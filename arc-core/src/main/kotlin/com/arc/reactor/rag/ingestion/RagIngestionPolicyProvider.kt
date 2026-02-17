package com.arc.reactor.rag.ingestion

import com.arc.reactor.agent.config.RagIngestionProperties
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Provides effective RAG ingestion policy at runtime.
 */
class RagIngestionPolicyProvider(
    private val properties: RagIngestionProperties,
    private val store: RagIngestionPolicyStore
) {

    private val cached = AtomicReference<RagIngestionPolicy?>(null)
    private val cachedAtMs = AtomicLong(0)

    fun invalidate() {
        cached.set(null)
        cachedAtMs.set(0)
    }

    fun current(): RagIngestionPolicy {
        if (!properties.enabled) {
            return RagIngestionPolicy(
                enabled = false,
                requireReview = properties.requireReview,
                allowedChannels = emptySet(),
                minQueryChars = properties.minQueryChars.coerceAtLeast(1),
                minResponseChars = properties.minResponseChars.coerceAtLeast(1),
                blockedPatterns = emptySet(),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH
            )
        }

        if (!properties.dynamic.enabled) {
            return normalize(RagIngestionPolicy.fromProperties(properties))
        }

        val now = System.currentTimeMillis()
        val ttlMs = properties.dynamic.refreshMs.coerceAtLeast(250)
        val cachedValue = cached.get()
        val cachedAt = cachedAtMs.get()
        if (cachedValue != null && now - cachedAt < ttlMs) return cachedValue

        return runCatching {
            val loaded = store.getOrNull() ?: RagIngestionPolicy.fromProperties(properties)
            val normalized = normalize(loaded)
            cached.set(normalized)
            cachedAtMs.set(now)
            normalized
        }.getOrElse { e ->
            logger.warn(e) { "Failed to load dynamic rag ingestion policy; falling back to cached/properties" }
            cachedValue ?: normalize(RagIngestionPolicy.fromProperties(properties))
        }
    }

    private fun normalize(policy: RagIngestionPolicy): RagIngestionPolicy {
        return policy.copy(
            allowedChannels = policy.allowedChannels.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet(),
            minQueryChars = policy.minQueryChars.coerceAtLeast(1),
            minResponseChars = policy.minResponseChars.coerceAtLeast(1),
            blockedPatterns = policy.blockedPatterns.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        )
    }
}
