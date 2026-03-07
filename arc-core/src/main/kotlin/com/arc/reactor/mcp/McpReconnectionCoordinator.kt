package com.arc.reactor.mcp

import com.arc.reactor.agent.config.McpReconnectionProperties
import com.arc.reactor.mcp.model.McpServerStatus
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

private val logger = KotlinLogging.logger {}

/**
 * Background reconnection scheduler for MCP servers.
 */
internal class McpReconnectionCoordinator(
    private val scope: CoroutineScope,
    private val properties: McpReconnectionProperties,
    private val statusProvider: (String) -> McpServerStatus?,
    private val serverExists: (String) -> Boolean,
    private val reconnectAction: suspend (String) -> Boolean
) {

    private val reconnectionJobs = ConcurrentHashMap<String, Job>()

    fun clear(serverName: String) {
        reconnectionJobs.remove(serverName)?.cancel()
    }

    fun clearAll() {
        reconnectionJobs.values.forEach { it.cancel() }
        reconnectionJobs.clear()
    }

    fun schedule(serverName: String) {
        if (!properties.enabled) return
        if (reconnectionJobs[serverName]?.isActive == true) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val maxAttempts = properties.maxAttempts
            try {
                for (attempt in 1..maxAttempts) {
                    val baseDelay = minOf(
                        (properties.initialDelayMs * properties.multiplier.pow((attempt - 1).toDouble())).toLong(),
                        properties.maxDelayMs
                    )
                    val jitter = (baseDelay * 0.25 * (Math.random() * 2 - 1)).toLong()
                    val delayMs = (baseDelay + jitter).coerceAtLeast(0)

                    logger.info {
                        "MCP reconnection scheduled for '$serverName' " +
                            "(attempt $attempt/$maxAttempts, delay ${delayMs}ms)"
                    }

                    try {
                        delay(delayMs)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        throw e
                    }

                    val currentStatus = statusProvider(serverName)
                    if (!serverExists(serverName) ||
                        currentStatus == McpServerStatus.CONNECTED ||
                        currentStatus == McpServerStatus.DISCONNECTED
                    ) {
                        return@launch
                    }

                    val success = try {
                        reconnectAction(serverName)
                    } catch (e: Exception) {
                        e.throwIfCancellation()
                        logger.warn(e) { "Reconnection attempt $attempt/$maxAttempts failed for '$serverName'" }
                        false
                    }

                    if (success) {
                        logger.info { "MCP server '$serverName' reconnected on attempt $attempt" }
                        return@launch
                    }
                }
                logger.warn { "MCP reconnection exhausted (${properties.maxAttempts} attempts) for '$serverName'" }
            } finally {
                reconnectionJobs.remove(serverName, kotlinx.coroutines.currentCoroutineContext()[Job])
            }
        }

        val existing = reconnectionJobs.putIfAbsent(serverName, job)
        if (existing != null) {
            job.cancel()
            return
        }
        job.start()
    }
}
