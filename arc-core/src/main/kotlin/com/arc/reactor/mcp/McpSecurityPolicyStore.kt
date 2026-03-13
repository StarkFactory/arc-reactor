package com.arc.reactor.mcp

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Admin-managed MCP security policy.
 *
 * This overlays the static configuration from application properties so operators can
 * adjust the allowlist without redeploying.
 */
data class McpSecurityPolicy(
    val allowedServerNames: Set<String> = emptySet(),
    val maxToolOutputLength: Int = 50_000,
    val allowedStdioCommands: Set<String> = McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
) {
    fun toConfig(): McpSecurityConfig = McpSecurityConfig(
        allowedServerNames = allowedServerNames,
        maxToolOutputLength = maxToolOutputLength,
        allowedStdioCommands = allowedStdioCommands
    )

    companion object {
        fun fromConfig(config: McpSecurityConfig): McpSecurityPolicy = McpSecurityPolicy(
            allowedServerNames = config.allowedServerNames,
            maxToolOutputLength = config.maxToolOutputLength,
            allowedStdioCommands = config.allowedStdioCommands
        )
    }
}

interface McpSecurityPolicyStore {
    fun getOrNull(): McpSecurityPolicy?
    fun save(policy: McpSecurityPolicy): McpSecurityPolicy
    fun delete(): Boolean
}

class InMemoryMcpSecurityPolicyStore(
    initial: McpSecurityPolicy? = null
) : McpSecurityPolicyStore {

    private val ref = AtomicReference<McpSecurityPolicy?>(initial)

    override fun getOrNull(): McpSecurityPolicy? = ref.get()

    override fun save(policy: McpSecurityPolicy): McpSecurityPolicy {
        val now = Instant.now()
        val current = ref.get()
        val saved = policy.copy(
            createdAt = current?.createdAt ?: now,
            updatedAt = now
        )
        ref.set(saved)
        return saved
    }

    override fun delete(): Boolean = ref.getAndSet(null) != null
}

class McpSecurityPolicyProvider(
    private val defaultConfig: McpSecurityConfig,
    private val store: McpSecurityPolicyStore
) {

    fun currentPolicy(): McpSecurityPolicy {
        val stored = store.getOrNull()
        return normalize(stored ?: McpSecurityPolicy.fromConfig(defaultConfig))
    }

    fun currentConfig(): McpSecurityConfig = currentPolicy().toConfig()

    fun invalidate() {
        // Reserved for parity with other dynamic policy providers.
    }

    private fun normalize(policy: McpSecurityPolicy): McpSecurityPolicy {
        return policy.copy(
            allowedServerNames = policy.allowedServerNames
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            maxToolOutputLength = policy.maxToolOutputLength.coerceIn(MIN_TOOL_OUTPUT_LENGTH, MAX_TOOL_OUTPUT_LENGTH)
        )
    }

    companion object {
        const val MIN_TOOL_OUTPUT_LENGTH = 1_024
        const val MAX_TOOL_OUTPUT_LENGTH = 500_000
    }
}
