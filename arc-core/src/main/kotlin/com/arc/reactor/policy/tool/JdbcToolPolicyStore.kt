package com.arc.reactor.policy.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * JDBC 기반 도구 정책 저장소
 *
 * tool_policy 테이블에 도구 정책을 영구 저장한다.
 * 다중 인스턴스 배포에서 모든 인스턴스가 동일한 정책을 참조할 수 있다.
 *
 * ## 저장 전략
 * - 단일 행(DEFAULT_ID = "default")으로 정책을 관리한다
 * - 집합/맵 필드는 JSON 문자열로 직렬화하여 저장한다
 * - UPSERT: 기존 행이 있으면 UPDATE, 없으면 INSERT
 * - 트랜잭션으로 원자성을 보장한다
 *
 * @param jdbcTemplate Spring JdbcTemplate
 * @param transactionTemplate Spring TransactionTemplate
 *
 * @see ToolPolicyStore 저장소 인터페이스
 * @see InMemoryToolPolicyStore 메모리 기반 대안
 */
class JdbcToolPolicyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : ToolPolicyStore {

    override fun getOrNull(): ToolPolicy? {
        return jdbcTemplate.query(
            "SELECT id, enabled, write_tool_names, deny_write_channels, allow_write_tool_names_in_deny_channels, " +
                "allow_write_tool_names_by_channel, " +
                "deny_write_message, created_at, updated_at " +
                "FROM tool_policy WHERE id = ?",
            { rs: ResultSet, _: Int -> mapRow(rs) },
            DEFAULT_ID
        ).firstOrNull()
    }

    override fun save(policy: ToolPolicy): ToolPolicy {
        return transactionTemplate.execute {
            val existing = getOrNull()
            val now = Instant.now()
            val createdAt = existing?.createdAt ?: now
            val updated = policy.copy(createdAt = createdAt, updatedAt = now)

            if (existing == null) {
                // 신규 삽입
                jdbcTemplate.update(
                    "INSERT INTO tool_policy " +
                        "(id, enabled, write_tool_names, deny_write_channels, " +
                        "allow_write_tool_names_in_deny_channels, " +
                        "allow_write_tool_names_by_channel, " +
                        "deny_write_message, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    DEFAULT_ID,
                    updated.enabled,
                    objectMapper.writeValueAsString(updated.writeToolNames.toList()),
                    objectMapper.writeValueAsString(updated.denyWriteChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesInDenyChannels.toList()),
                    objectMapper.writeValueAsString(
                        updated.allowWriteToolNamesByChannel.mapValues { it.value.toList() }
                    ),
                    updated.denyWriteMessage,
                    java.sql.Timestamp.from(createdAt),
                    java.sql.Timestamp.from(now)
                )
            } else {
                // 기존 행 업데이트
                jdbcTemplate.update(
                    "UPDATE tool_policy SET enabled = ?, write_tool_names = ?, deny_write_channels = ?, " +
                        "allow_write_tool_names_in_deny_channels = ?, allow_write_tool_names_by_channel = ?, " +
                        "deny_write_message = ?, updated_at = ? " +
                        "WHERE id = ?",
                    updated.enabled,
                    objectMapper.writeValueAsString(updated.writeToolNames.toList()),
                    objectMapper.writeValueAsString(updated.denyWriteChannels.toList()),
                    objectMapper.writeValueAsString(updated.allowWriteToolNamesInDenyChannels.toList()),
                    objectMapper.writeValueAsString(
                        updated.allowWriteToolNamesByChannel.mapValues { it.value.toList() }
                    ),
                    updated.denyWriteMessage,
                    java.sql.Timestamp.from(now),
                    DEFAULT_ID
                )
            }

            updated
        } ?: error("도구 정책 저장 중 트랜잭션이 null 반환")
    }

    override fun delete(): Boolean {
        val count = jdbcTemplate.update("DELETE FROM tool_policy WHERE id = ?", DEFAULT_ID)
        return count > 0
    }

    /** ResultSet → ToolPolicy 매핑 */
    private fun mapRow(rs: ResultSet): ToolPolicy {
        return ToolPolicy(
            enabled = rs.getBoolean("enabled"),
            writeToolNames = parseJsonSet(rs.getString("write_tool_names")),
            denyWriteChannels = parseJsonSet(rs.getString("deny_write_channels")),
            allowWriteToolNamesInDenyChannels = parseJsonSet(rs.getString("allow_write_tool_names_in_deny_channels")),
            allowWriteToolNamesByChannel = parseJsonMap(rs.getString("allow_write_tool_names_by_channel")),
            denyWriteMessage = rs.getString("deny_write_message") ?: "",
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    /** JSON 배열 문자열을 Set<String>으로 파싱한다 */
    private fun parseJsonSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            objectMapper.readValue<List<String>>(json)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }
            .getOrDefault(emptySet())
    }

    /** JSON 객체 문자열을 Map<String, Set<String>>으로 파싱한다 */
    private fun parseJsonMap(json: String?): Map<String, Set<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readValue<Map<String, List<String>>>(json)
                .mapKeys { (k, _) -> k.trim() }
                .mapValues { (_, v) -> v.map { it.trim() }.filter { it.isNotBlank() }.toSet() }
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    companion object {
        /** 단일 행 정책의 고정 ID */
        private const val DEFAULT_ID = "default"
        private val objectMapper = jacksonObjectMapper()
    }
}
