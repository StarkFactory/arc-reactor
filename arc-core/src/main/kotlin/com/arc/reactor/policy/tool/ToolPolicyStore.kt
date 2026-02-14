package com.arc.reactor.policy.tool

/**
 * Tool policy store.
 *
 * Backed by DB when `arc.reactor.tool-policy.dynamic.enabled=true` and JDBC is configured.
 */
interface ToolPolicyStore {
    fun getOrNull(): ToolPolicy?
    fun save(policy: ToolPolicy): ToolPolicy
    fun delete(): Boolean
}
