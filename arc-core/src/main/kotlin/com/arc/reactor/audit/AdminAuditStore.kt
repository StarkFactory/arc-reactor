package com.arc.reactor.audit

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 관리자 감사 로그 데이터 클래스
 *
 * 관리자의 모든 작업(설정 변경, 규칙 생성/삭제 등)을 기록하는 감사 로그이다.
 *
 * @property id 감사 로그 고유 ID
 * @property category 카테고리 (예: "tool_policy", "output_guard_rule")
 * @property action 수행된 동작 (예: "CREATE", "UPDATE", "DELETE")
 * @property actor 동작을 수행한 관리자 식별자
 * @property resourceType 대상 리소스 유형 (선택사항)
 * @property resourceId 대상 리소스 ID (선택사항)
 * @property detail 변경 상세 내용 (선택사항)
 * @property createdAt 감사 로그 생성 시각
 */
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

/**
 * 관리자 감사 로그 저장소 인터페이스
 *
 * @see InMemoryAdminAuditStore 메모리 기반 구현체
 * @see JdbcAdminAuditStore JDBC 기반 구현체
 */
interface AdminAuditStore {
    /**
     * 감사 로그 목록을 최신순으로 조회한다.
     *
     * @param limit 최대 조회 건수 (1~1000)
     * @param category 카테고리 필터 (선택사항)
     * @param action 동작 필터 (선택사항)
     */
    fun list(limit: Int = 100, category: String? = null, action: String? = null): List<AdminAuditLog>

    /** 감사 로그를 저장한다. */
    fun save(log: AdminAuditLog): AdminAuditLog
}

/**
 * 메모리 기반 관리자 감사 로그 저장소
 *
 * [ConcurrentLinkedDeque]를 사용하여 스레드 안전하게 로그를 관리한다.
 * 최대 10,000건을 유지하며, 초과 시 가장 오래된 로그를 제거한다.
 *
 * @see JdbcAdminAuditStore 영구 저장이 필요한 환경용
 */
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
        // 최대 10,000건 유지
        while (logs.size > 10000) {
            logs.pollLast()
        }
        return log
    }
}

/**
 * JDBC 기반 관리자 감사 로그 저장소
 *
 * admin_audits 테이블에 감사 로그를 영구 저장한다.
 *
 * @param jdbcTemplate Spring JdbcTemplate
 *
 * @see InMemoryAdminAuditStore 메모리 기반 대안
 */
class JdbcAdminAuditStore(
    private val jdbcTemplate: JdbcTemplate
) : AdminAuditStore {

    override fun list(limit: Int, category: String?, action: String?): List<AdminAuditLog> {
        val size = limit.coerceIn(1, 1000)
        val categoryFilter = category?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val actionFilter = action?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

        // 카테고리와 동작 필터 조합에 따라 쿼리를 분기한다
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
        /** ResultSet → AdminAuditLog 매핑 함수 */
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
