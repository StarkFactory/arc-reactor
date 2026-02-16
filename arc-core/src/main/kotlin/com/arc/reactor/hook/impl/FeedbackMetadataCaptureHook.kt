package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Captured execution metadata for feedback auto-enrichment.
 *
 * When a user submits feedback with a runId, the controller uses this data
 * to automatically populate query, response, toolsUsed, and durationMs.
 */
data class CapturedExecutionMetadata(
    val runId: String,
    val userId: String,
    val userPrompt: String,
    val agentResponse: String?,
    val toolsUsed: List<String>,
    val durationMs: Long,
    val sessionId: String?,
    val capturedAt: Instant = Instant.now()
)

/**
 * Feedback Metadata Capture Hook
 *
 * AfterAgentCompleteHook that caches execution metadata in memory.
 * When a user later submits feedback with a runId, the FeedbackController
 * can auto-enrich the feedback with query, response, toolsUsed, and durationMs.
 *
 * ## Behavior
 * - Order 250: Late hook, runs after webhooks (200)
 * - Fail-open: Never blocks agent response
 * - TTL: Entries older than 1 hour are evicted periodically
 * - Max entries: 10,000 (oldest evicted when exceeded)
 * - Eviction throttled: runs at most once per 30 seconds
 *
 * @param clock Injectable clock for testability (default: system UTC clock)
 */
class FeedbackMetadataCaptureHook(
    private val clock: Clock = Clock.systemUTC()
) : AfterAgentCompleteHook {

    override val order: Int = 250

    override val failOnError: Boolean = false

    private val cache = ConcurrentHashMap<String, CapturedExecutionMetadata>()
    private val lastEvictionTime = AtomicReference(Instant.EPOCH)

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            val metadata = CapturedExecutionMetadata(
                runId = context.runId,
                userId = context.userId,
                userPrompt = context.userPrompt,
                agentResponse = response.response,
                toolsUsed = response.toolsUsed,
                durationMs = response.totalDurationMs,
                sessionId = context.metadata["sessionId"]?.toString(),
                capturedAt = Instant.now(clock)
            )
            cache[context.runId] = metadata
            evictIfNeeded()
            logger.debug { "Captured execution metadata for runId=${context.runId}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Failed to capture metadata for runId=${context.runId}: ${e.message}" }
        }
    }

    /**
     * Retrieve cached execution metadata by runId.
     *
     * @return Metadata if found and not expired, null otherwise
     */
    fun get(runId: String): CapturedExecutionMetadata? {
        val entry = cache[runId] ?: return null
        val cutoff = Instant.now(clock).minusSeconds(TTL_SECONDS)
        if (entry.capturedAt.isBefore(cutoff)) {
            cache.remove(runId)
            return null
        }
        return entry
    }

    private fun evictIfNeeded() {
        val now = Instant.now(clock)
        val lastRun = lastEvictionTime.get()
        if (now.epochSecond - lastRun.epochSecond < EVICTION_INTERVAL_SECONDS) return
        if (!lastEvictionTime.compareAndSet(lastRun, now)) return

        evictStale()
    }

    private fun evictStale() {
        val cutoff = Instant.now(clock).minusSeconds(TTL_SECONDS)
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.capturedAt.isBefore(cutoff)) {
                iterator.remove()
            }
        }

        if (cache.size > MAX_ENTRIES) {
            val toRemove = cache.entries.toList()
                .sortedBy { it.value.capturedAt }
                .take(cache.size - MAX_ENTRIES)
            for (entry in toRemove) {
                cache.remove(entry.key)
            }
        }
    }

    internal fun cacheSize(): Int = cache.size

    companion object {
        internal const val TTL_SECONDS = 3600L
        private const val MAX_ENTRIES = 10_000
        private const val EVICTION_INTERVAL_SECONDS = 30L
    }
}
