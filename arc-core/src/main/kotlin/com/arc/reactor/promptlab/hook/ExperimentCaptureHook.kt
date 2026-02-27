package com.arc.reactor.promptlab.hook

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Captured experiment execution data for observability.
 */
data class CapturedExperimentData(
    val runId: String,
    val experimentId: String,
    val versionId: String,
    val response: String?,
    val toolsUsed: List<String>,
    val durationMs: Long,
    val success: Boolean,
    val capturedAt: Instant = Instant.now()
)

/**
 * Experiment Capture Hook
 *
 * AfterAgentCompleteHook that captures experiment execution data when
 * the agent command contains `promptlab.experimentId` metadata.
 *
 * ## Behavior
 * - Order 270: After FeedbackCapture(250) and RagCapture(260)
 * - Fail-open: Never blocks agent response
 * - TTL: 1 hour, max 10,000 entries
 * - Only activates when metadata contains experiment identifiers
 */
class ExperimentCaptureHook(
    private val clock: Clock = Clock.systemUTC()
) : AfterAgentCompleteHook {

    override val order: Int = 270
    override val failOnError: Boolean = false

    private val cache = ConcurrentHashMap<String, CapturedExperimentData>()
    private val lastEvictionTime = AtomicReference(Instant.EPOCH)

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            val experimentId = context.metadata[EXPERIMENT_ID_KEY]?.toString()
                ?: return
            val versionId = context.metadata[VERSION_ID_KEY]?.toString()
                ?: return

            val data = CapturedExperimentData(
                runId = context.runId,
                experimentId = experimentId,
                versionId = versionId,
                response = response.response,
                toolsUsed = response.toolsUsed,
                durationMs = response.totalDurationMs,
                success = response.success,
                capturedAt = Instant.now(clock)
            )
            cache[context.runId] = data
            evictIfNeeded()
            logger.debug { "Captured experiment data for runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Failed to capture experiment data: ${e.message}" }
        }
    }

    /** Retrieve cached data by runId */
    fun get(runId: String): CapturedExperimentData? {
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
        if (now.epochSecond - lastRun.epochSecond < EVICTION_INTERVAL_SECONDS) {
            return
        }
        if (!lastEvictionTime.compareAndSet(lastRun, now)) return
        evictStale()
    }

    private fun evictStale() {
        val cutoff = Instant.now(clock).minusSeconds(TTL_SECONDS)
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.capturedAt.isBefore(cutoff)) {
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
        const val EXPERIMENT_ID_KEY = "promptlab.experimentId"
        const val VERSION_ID_KEY = "promptlab.versionId"
        const val RUN_ID_KEY = "promptlab.runId"
        internal const val TTL_SECONDS = 3600L
        private const val MAX_ENTRIES = 10_000
        private const val EVICTION_INTERVAL_SECONDS = 30L
    }
}
