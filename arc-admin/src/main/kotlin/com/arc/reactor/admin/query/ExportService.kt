package com.arc.reactor.admin.query

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.io.Writer
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 메트릭 데이터를 CSV 형식으로 내보내는 서비스.
 *
 * ResultSet 스트리밍 콜백 내에서 Writer 쓰기 실패 시
 * [CsvWriteException]으로 래핑하여 호출자에게 전파한다.
 *
 * @see TenantAdminController CSV 내보내기 엔드포인트
 */
class ExportService(private val jdbcTemplate: JdbcTemplate) {

    /** 에이전트 실행 이력을 CSV로 내보낸다. Writer 쓰기 실패 시 [CsvWriteException] 발생. */
    fun exportExecutionsCsv(tenantId: String, from: Instant, to: Instant, writer: Writer) {
        writer.write("time,run_id,success,duration_ms,error_code,tool_count\n")

        jdbcTemplate.query(
            """SELECT time, run_id, success, duration_ms, error_code, tool_count
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?
               ORDER BY time DESC""",
            { rs ->
                try {
                    writer.write(
                        "${escapeCsv(rs.getTimestamp("time")?.toString())}," +
                            "${escapeCsv(rs.getString("run_id"))}," +
                            "${rs.getBoolean("success")}," +
                            "${rs.getLong("duration_ms")}," +
                            "${escapeCsv(rs.getString("error_code"))}," +
                            "${rs.getInt("tool_count")}\n"
                    )
                } catch (e: Exception) {
                    throw CsvWriteException("실행 이력 CSV 쓰기 중 실패: tenantId=$tenantId", e)
                }
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    /** 도구 호출 이력을 CSV로 내보낸다. Writer 쓰기 실패 시 [CsvWriteException] 발생. */
    fun exportToolCallsCsv(tenantId: String, from: Instant, to: Instant, writer: Writer) {
        writer.write("time,run_id,tool_name,success,duration_ms,error_class\n")

        jdbcTemplate.query(
            """SELECT time, run_id, tool_name, success, duration_ms, error_class
               FROM metric_tool_calls
               WHERE tenant_id = ? AND time >= ? AND time < ?
               ORDER BY time DESC""",
            { rs ->
                try {
                    writer.write(
                        "${escapeCsv(rs.getTimestamp("time")?.toString())}," +
                            "${escapeCsv(rs.getString("run_id"))}," +
                            "${escapeCsv(rs.getString("tool_name"))}," +
                            "${rs.getBoolean("success")}," +
                            "${rs.getLong("duration_ms")}," +
                            "${escapeCsv(rs.getString("error_class"))}\n"
                    )
                } catch (e: Exception) {
                    throw CsvWriteException("도구 호출 CSV 쓰기 중 실패: tenantId=$tenantId", e)
                }
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    /** CSV 내보내기 중 Writer 쓰기 실패 시 발생하는 예외. */
    class CsvWriteException(message: String, cause: Throwable) : RuntimeException(message, cause)

    companion object {
        /** RFC 4180 CSV 이스케이프: 쉼표, 따옴표, 줄바꿈 포함 시 큰따옴표로 감싸고 내부 따옴표를 이스케이프한다. */
        fun escapeCsv(value: String?): String {
            if (value == null) return ""
            return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }
    }
}
