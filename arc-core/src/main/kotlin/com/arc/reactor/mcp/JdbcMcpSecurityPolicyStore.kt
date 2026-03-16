package com.arc.reactor.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * JDBC 기반 MCP 보안 정책 저장소 — 영속적 스토리지.
 *
 * 단일 행(`id = "default"`)으로 보안 정책을 관리한다.
 * 허용 서버 목록은 JSON 배열로 직렬화하여 저장한다.
 *
 * WHY: 운영 중 REST API를 통해 MCP 보안 정책을 변경할 수 있게 하고,
 * 변경 사항을 서버 재시작 이후에도 유지한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @param transactionTemplate 트랜잭션 지원 (INSERT/UPDATE 원자성)
 * @see McpSecurityPolicyStore 인터페이스 정의
 * @see InMemoryMcpSecurityPolicyStore 인메모리 대안
 */
class JdbcMcpSecurityPolicyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : McpSecurityPolicyStore {

    override fun getOrNull(): McpSecurityPolicy? {
        return jdbcTemplate.query(
            "SELECT id, allowed_server_names, max_tool_output_length, created_at, updated_at " +
                "FROM mcp_security_policy WHERE id = ?",
            { rs: ResultSet, _: Int -> mapRow(rs) },
            DEFAULT_ID
        ).firstOrNull()
    }

    /**
     * 정책을 저장한다. 기존 정책이 있으면 UPDATE, 없으면 INSERT를 수행한다.
     * 트랜잭션으로 원자성을 보장한다.
     */
    override fun save(policy: McpSecurityPolicy): McpSecurityPolicy {
        return transactionTemplate.execute {
            val existing = getOrNull()
            val now = Instant.now()
            val createdAt = existing?.createdAt ?: now
            val saved = policy.copy(createdAt = createdAt, updatedAt = now)

            if (existing == null) {
                jdbcTemplate.update(
                    "INSERT INTO mcp_security_policy " +
                        "(id, allowed_server_names, max_tool_output_length, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    DEFAULT_ID,
                    objectMapper.writeValueAsString(saved.allowedServerNames.toList()),
                    saved.maxToolOutputLength,
                    java.sql.Timestamp.from(createdAt),
                    java.sql.Timestamp.from(now)
                )
            } else {
                jdbcTemplate.update(
                    "UPDATE mcp_security_policy SET allowed_server_names = ?, max_tool_output_length = ?, " +
                        "updated_at = ? WHERE id = ?",
                    objectMapper.writeValueAsString(saved.allowedServerNames.toList()),
                    saved.maxToolOutputLength,
                    java.sql.Timestamp.from(now),
                    DEFAULT_ID
                )
            }

            saved
        } ?: error("MCP 보안 정책 저장 중 트랜잭션이 null 반환")
    }

    override fun delete(): Boolean {
        val count = jdbcTemplate.update("DELETE FROM mcp_security_policy WHERE id = ?", DEFAULT_ID)
        return count > 0
    }

    /** ResultSet 행을 McpSecurityPolicy로 변환한다 */
    private fun mapRow(rs: ResultSet): McpSecurityPolicy {
        return McpSecurityPolicy(
            allowedServerNames = parseJsonSet(rs.getString("allowed_server_names")),
            maxToolOutputLength = rs.getInt("max_tool_output_length"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    /** JSON 배열 문자열을 문자열 집합으로 파싱한다. 공백 제거 및 빈 값 필터링 적용. */
    private fun parseJsonSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            objectMapper.readValue<List<String>>(json)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    companion object {
        /** 단일 행 정책의 고정 ID */
        private const val DEFAULT_ID = "default"
        private val objectMapper = jacksonObjectMapper()
    }
}
