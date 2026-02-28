package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.SlackToolsProperties
import mu.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

interface WriteOperationIdempotencyService {
    fun execute(
        toolName: String,
        explicitIdempotencyKey: String?,
        keyParts: List<String>,
        operation: () -> String
    ): String
}

object NoopWriteOperationIdempotencyService : WriteOperationIdempotencyService {
    override fun execute(
        toolName: String,
        explicitIdempotencyKey: String?,
        keyParts: List<String>,
        operation: () -> String
    ): String = operation()
}

class InMemoryWriteOperationIdempotencyService(
    private val properties: SlackToolsProperties
) : WriteOperationIdempotencyService {

    private val completed = ConcurrentHashMap<String, CachedResult>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<String>>()

    override fun execute(
        toolName: String,
        explicitIdempotencyKey: String?,
        keyParts: List<String>,
        operation: () -> String
    ): String {
        val cfg = properties.writeIdempotency
        if (!cfg.enabled) return operation()

        val now = System.currentTimeMillis()
        cleanupExpired(now)
        val cacheKey = buildCacheKey(toolName, explicitIdempotencyKey, keyParts)

        completed[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.result
            completed.remove(cacheKey, cached)
        }

        val pending = CompletableFuture<String>()
        val existing = inFlight.putIfAbsent(cacheKey, pending)
        if (existing != null) {
            return try {
                existing.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                operation()
            } catch (e: ExecutionException) {
                logger.warn(e) { "Idempotent replay failed for tool=$toolName, re-running operation once." }
                operation()
            }
        }

        return try {
            val result = operation()
            completed[cacheKey] = CachedResult(result = result, expiresAtMs = now + cfg.ttlSeconds * 1000)
            trimIfNeeded(cfg.maxEntries)
            pending.complete(result)
            result
        } catch (t: Throwable) {
            pending.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(cacheKey, pending)
        }
    }

    private fun cleanupExpired(now: Long) {
        completed.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun trimIfNeeded(maxEntries: Int) {
        val overflow = completed.size - maxEntries
        if (overflow <= 0) return
        val victims = completed.entries
            .sortedBy { it.value.expiresAtMs }
            .take(overflow)
        victims.forEach { entry -> completed.remove(entry.key, entry.value) }
    }

    private fun buildCacheKey(toolName: String, explicitIdempotencyKey: String?, keyParts: List<String>): String {
        val canonical = if (!explicitIdempotencyKey.isNullOrBlank()) {
            "$toolName|key|${explicitIdempotencyKey.trim()}"
        } else {
            val normalized = keyParts.joinToString(separator = "\u001F") { it.trim() }
            "$toolName|payload|$normalized"
        }
        return sha256Hex(canonical)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class CachedResult(
        val result: String,
        val expiresAtMs: Long
    )

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
