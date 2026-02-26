package com.arc.reactor.admin.tenant

import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantQuota
import com.arc.reactor.admin.model.TenantStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp

class JdbcTenantStore(
    private val jdbcTemplate: JdbcTemplate
) : TenantStore {

    override fun findById(id: String): Tenant? {
        val results = jdbcTemplate.query(
            "SELECT * FROM tenants WHERE id = ?",
            ROW_MAPPER, id
        )
        return results.firstOrNull()
    }

    override fun findBySlug(slug: String): Tenant? {
        val results = jdbcTemplate.query(
            "SELECT * FROM tenants WHERE slug = ?",
            ROW_MAPPER, slug
        )
        return results.firstOrNull()
    }

    override fun findAll(status: TenantStatus?): List<Tenant> {
        return if (status != null) {
            jdbcTemplate.query(
                "SELECT * FROM tenants WHERE status = ? ORDER BY created_at DESC",
                ROW_MAPPER, status.name
            )
        } else {
            jdbcTemplate.query(
                "SELECT * FROM tenants ORDER BY created_at DESC",
                ROW_MAPPER
            )
        }
    }

    override fun save(tenant: Tenant): Tenant {
        val metadataJson = OBJECT_MAPPER.writeValueAsString(tenant.metadata)
        jdbcTemplate.update(
            """INSERT INTO tenants (id, name, slug, plan, status,
                   max_requests_per_month, max_tokens_per_month, max_users, max_agents, max_mcp_servers,
                   billing_cycle_start, billing_email,
                   slo_availability, slo_latency_p99_ms,
                   metadata, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                   name = EXCLUDED.name, slug = EXCLUDED.slug, plan = EXCLUDED.plan,
                   status = EXCLUDED.status,
                   max_requests_per_month = EXCLUDED.max_requests_per_month,
                   max_tokens_per_month = EXCLUDED.max_tokens_per_month,
                   max_users = EXCLUDED.max_users, max_agents = EXCLUDED.max_agents,
                   max_mcp_servers = EXCLUDED.max_mcp_servers,
                   billing_cycle_start = EXCLUDED.billing_cycle_start,
                   billing_email = EXCLUDED.billing_email,
                   slo_availability = EXCLUDED.slo_availability,
                   slo_latency_p99_ms = EXCLUDED.slo_latency_p99_ms,
                   metadata = EXCLUDED.metadata, updated_at = EXCLUDED.updated_at""",
            tenant.id, tenant.name, tenant.slug, tenant.plan.name, tenant.status.name,
            tenant.quota.maxRequestsPerMonth, tenant.quota.maxTokensPerMonth,
            tenant.quota.maxUsers, tenant.quota.maxAgents, tenant.quota.maxMcpServers,
            tenant.billingCycleStart, tenant.billingEmail,
            tenant.sloAvailability, tenant.sloLatencyP99Ms,
            metadataJson,
            Timestamp.from(tenant.createdAt), Timestamp.from(tenant.updatedAt)
        )
        return tenant
    }

    override fun delete(id: String): Boolean {
        val count = jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", id)
        return count > 0
    }

    companion object {
        private val OBJECT_MAPPER = jacksonObjectMapper()

        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            val metadataJson = rs.getString("metadata")
            val metadata: Map<String, Any> = if (metadataJson.isNullOrBlank()) {
                emptyMap()
            } else {
                OBJECT_MAPPER.readValue(metadataJson)
            }
            Tenant(
                id = rs.getString("id"),
                name = rs.getString("name"),
                slug = rs.getString("slug"),
                plan = TenantPlan.valueOf(rs.getString("plan")),
                status = TenantStatus.valueOf(rs.getString("status")),
                quota = TenantQuota(
                    maxRequestsPerMonth = rs.getLong("max_requests_per_month"),
                    maxTokensPerMonth = rs.getLong("max_tokens_per_month"),
                    maxUsers = rs.getInt("max_users"),
                    maxAgents = rs.getInt("max_agents"),
                    maxMcpServers = rs.getInt("max_mcp_servers")
                ),
                billingCycleStart = rs.getInt("billing_cycle_start"),
                billingEmail = rs.getString("billing_email"),
                sloAvailability = rs.getDouble("slo_availability"),
                sloLatencyP99Ms = rs.getLong("slo_latency_p99_ms"),
                metadata = metadata,
                createdAt = rs.getTimestamp("created_at")?.toInstant() ?: java.time.Instant.now(),
                updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: java.time.Instant.now()
            )
        }
    }
}
