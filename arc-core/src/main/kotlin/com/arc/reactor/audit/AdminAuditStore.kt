package com.arc.reactor.audit

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

data class AdminAuditLog(
    val id: String = UUID.randomUUID().toString(),
    val category: String,
    val action: String,
    val actor: String,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val detail: String? = null,
    val createdAt: Instant = Instant.now()
)

interface AdminAuditStore {
    fun list(limit: Int = 100, category: String? = null, action: String? = null): List<AdminAuditLog>
    fun save(log: AdminAuditLog): AdminAuditLog
}

class InMemoryAdminAuditStore : AdminAuditStore {
    private val logs = ConcurrentLinkedDeque<AdminAuditLog>()

    override fun list(limit: Int, category: String?, action: String?): List<AdminAuditLog> {
        val size = limit.coerceIn(1, 1000)
        val categoryFilter = category?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val actionFilter = action?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        return logs.asSequence()
            .filter { categoryFilter == null || it.category.lowercase() == categoryFilter }
            .filter { actionFilter == null || it.action.uppercase() == actionFilter }
            .take(size)
            .toList()
    }

    override fun save(log: AdminAuditLog): AdminAuditLog {
        logs.addFirst(log)
        while (logs.size > 10000) {
            logs.pollLast()
        }
        return log
    }
}

class JdbcAdminAuditStore(
    private val jdbcTemplate: JdbcTemplate
) : AdminAuditStore {

    override fun list(limit: Int, category: String?, action: String?): List<AdminAuditLog> {
        val size = limit.coerceIn(1, 1000)
        val categoryFilter = category?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val actionFilter = action?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

        return when {
            categoryFilter != null && actionFilter != null -> jdbcTemplate.query(
                """SELECT id, category, action, actor, resource_type, resource_id, detail, created_at
                   FROM admin_audits
                   WHERE LOWER(category) = ? AND UPPER(action) = ?
                   ORDER BY created_at DESC
                   LIMIT ?""",
                ROW_MAPPER,
                categoryFilter,
                actionFilter,
                size
            )

            categoryFilter != null -> jdbcTemplate.query(
                """SELECT id, category, action, actor, resource_type, resource_id, detail, created_at
                   FROM admin_audits
                   WHERE LOWER(category) = ?
                   ORDER BY created_at DESC
                   LIMIT ?""",
                ROW_MAPPER,
                categoryFilter,
                size
            )

            actionFilter != null -> jdbcTemplate.query(
                """SELECT id, category, action, actor, resource_type, resource_id, detail, created_at
                   FROM admin_audits
                   WHERE UPPER(action) = ?
                   ORDER BY created_at DESC
                   LIMIT ?""",
                ROW_MAPPER,
                actionFilter,
                size
            )

            else -> jdbcTemplate.query(
                """SELECT id, category, action, actor, resource_type, resource_id, detail, created_at
                   FROM admin_audits
                   ORDER BY created_at DESC
                   LIMIT ?""",
                ROW_MAPPER,
                size
            )
        }
    }

    override fun save(log: AdminAuditLog): AdminAuditLog {
        jdbcTemplate.update(
            """INSERT INTO admin_audits (id, category, action, actor, resource_type, resource_id, detail, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            log.id,
            log.category,
            log.action,
            log.actor,
            log.resourceType,
            log.resourceId,
            log.detail,
            java.sql.Timestamp.from(log.createdAt)
        )
        return log
    }

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            AdminAuditLog(
                id = rs.getString("id"),
                category = rs.getString("category"),
                action = rs.getString("action"),
                actor = rs.getString("actor"),
                resourceType = rs.getString("resource_type"),
                resourceId = rs.getString("resource_id"),
                detail = rs.getString("detail"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}
