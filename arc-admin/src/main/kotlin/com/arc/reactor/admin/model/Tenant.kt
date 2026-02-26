package com.arc.reactor.admin.model

import java.time.Instant
import java.util.UUID

enum class TenantPlan {
    FREE,
    STARTER,
    BUSINESS,
    ENTERPRISE
}

enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}

data class TenantQuota(
    val maxRequestsPerMonth: Long = 1000,
    val maxTokensPerMonth: Long = 1_000_000,
    val maxUsers: Int = 5,
    val maxAgents: Int = 3,
    val maxMcpServers: Int = 5
)

data class Tenant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val slug: String,
    val plan: TenantPlan = TenantPlan.FREE,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val quota: TenantQuota = TenantQuota(),
    val billingCycleStart: Int = 1,
    val billingEmail: String? = null,
    val sloAvailability: Double = 0.995,
    val sloLatencyP99Ms: Long = 10000,
    val metadata: Map<String, Any> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        val DEFAULT_QUOTAS = mapOf(
            TenantPlan.FREE to TenantQuota(
                maxRequestsPerMonth = 1000,
                maxTokensPerMonth = 1_000_000,
                maxUsers = 5,
                maxAgents = 3,
                maxMcpServers = 5
            ),
            TenantPlan.STARTER to TenantQuota(
                maxRequestsPerMonth = 10_000,
                maxTokensPerMonth = 10_000_000,
                maxUsers = 20,
                maxAgents = 10,
                maxMcpServers = 10
            ),
            TenantPlan.BUSINESS to TenantQuota(
                maxRequestsPerMonth = 100_000,
                maxTokensPerMonth = 100_000_000,
                maxUsers = 100,
                maxAgents = 50,
                maxMcpServers = 30
            ),
            TenantPlan.ENTERPRISE to TenantQuota(
                maxRequestsPerMonth = Long.MAX_VALUE,
                maxTokensPerMonth = Long.MAX_VALUE,
                maxUsers = Int.MAX_VALUE,
                maxAgents = Int.MAX_VALUE,
                maxMcpServers = Int.MAX_VALUE
            )
        )

        fun defaultQuotaFor(plan: TenantPlan): TenantQuota =
            DEFAULT_QUOTAS[plan] ?: TenantQuota()
    }
}

data class TenantUsage(
    val tenantId: String,
    val requests: Long = 0,
    val tokens: Long = 0,
    val costUsd: java.math.BigDecimal = java.math.BigDecimal.ZERO
)
