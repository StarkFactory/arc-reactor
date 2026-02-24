package com.arc.reactor.admin.query

import org.springframework.jdbc.core.JdbcTemplate
import java.io.Writer
import java.sql.Timestamp
import java.time.Instant

class ExportService(private val jdbcTemplate: JdbcTemplate) {

    fun exportExecutionsCsv(tenantId: String, from: Instant, to: Instant, writer: Writer) {
        writer.write("time,run_id,user_id,success,duration_ms,error_code,tool_count\n")

        jdbcTemplate.query(
            """SELECT time, run_id, user_id, success, duration_ms, error_code, tool_count
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?
               ORDER BY time DESC""",
            { rs ->
                writer.write(
                    "${escapeCsv(rs.getTimestamp("time")?.toString())}," +
                        "${escapeCsv(rs.getString("run_id"))}," +
                        "${escapeCsv(rs.getString("user_id"))}," +
                        "${rs.getBoolean("success")}," +
                        "${rs.getLong("duration_ms")}," +
                        "${escapeCsv(rs.getString("error_code"))}," +
                        "${rs.getInt("tool_count")}\n"
                )
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    fun exportToolCallsCsv(tenantId: String, from: Instant, to: Instant, writer: Writer) {
        writer.write("time,run_id,tool_name,success,duration_ms,error_class\n")

        jdbcTemplate.query(
            """SELECT time, run_id, tool_name, success, duration_ms, error_class
               FROM metric_tool_calls
               WHERE tenant_id = ? AND time >= ? AND time < ?
               ORDER BY time DESC""",
            { rs ->
                writer.write(
                    "${escapeCsv(rs.getTimestamp("time")?.toString())}," +
                        "${escapeCsv(rs.getString("run_id"))}," +
                        "${escapeCsv(rs.getString("tool_name"))}," +
                        "${rs.getBoolean("success")}," +
                        "${rs.getLong("duration_ms")}," +
                        "${escapeCsv(rs.getString("error_class"))}\n"
                )
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    companion object {
        /**
         * RFC 4180 CSV escaping: if value contains comma, quote, or newline,
         * wrap in double quotes and escape internal quotes.
         */
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
