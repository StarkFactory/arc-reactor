package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Provides the effective tool policy at runtime.
 *
 * - If `arc.reactor.tool-policy.enabled=false`, the effective policy is disabled (regardless of stored values).
 * - If dynamic policy is disabled, returns policy from application properties.
 * - If dynamic policy is enabled, loads policy from the store with a small TTL cache.
 */
class ToolPolicyProvider(
    private val properties: ToolPolicyProperties,
    private val store: ToolPolicyStore
) {

    private val cached = AtomicReference<ToolPolicy?>(null)
    private val cachedAtMs = AtomicLong(0)

    fun invalidate() {
        cachedAtMs.set(0)
        cached.set(null)
    }

    fun current(): ToolPolicy {
        // Master opt-in switch.
        if (!properties.enabled) {
            return ToolPolicy(
                enabled = false,
                writeToolNames = emptySet(),
                denyWriteChannels = emptySet(),
                denyWriteMessage = properties.denyWriteMessage,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH
            )
        }

        if (!properties.dynamic.enabled) {
            return normalize(ToolPolicy.fromProperties(properties))
        }

        val now = System.currentTimeMillis()
        val ttlMs = properties.dynamic.refreshMs.coerceAtLeast(250)
        val cachedAt = cachedAtMs.get()
        val existing = cached.get()
        if (existing != null && now - cachedAt < ttlMs) return existing

        // Simple refresh, fail-soft to last cache / properties.
        return runCatching {
            val loaded = store.getOrNull() ?: ToolPolicy.fromProperties(properties)
            val normalized = normalize(loaded)
            cached.set(normalized)
            cachedAtMs.set(now)
            normalized
        }.getOrElse { e ->
            logger.warn(e) { "Failed to load dynamic tool policy; falling back to cached/properties" }
            existing ?: normalize(ToolPolicy.fromProperties(properties))
        }
    }

    private fun normalize(policy: ToolPolicy): ToolPolicy {
        return policy.copy(
            writeToolNames = policy.writeToolNames.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
            denyWriteChannels = policy.denyWriteChannels.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet(),
            denyWriteMessage = policy.denyWriteMessage.ifBlank { properties.denyWriteMessage }
        )
    }
}

