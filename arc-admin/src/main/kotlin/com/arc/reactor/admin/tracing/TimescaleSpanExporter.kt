package com.arc.reactor.admin.tracing

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import mu.KotlinLogging
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * OTel SpanExporter that writes spans to the metric_spans hypertable.
 *
 * Called by OTel SDK's BatchSpanProcessor, so already batched.
 */
class TimescaleSpanExporter(
    private val jdbcTemplate: JdbcTemplate
) : SpanExporter {

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (spans.isEmpty()) return CompletableResultCode.ofSuccess()

        return try {
            val spanList = spans.toList()
            jdbcTemplate.batchUpdate(
                """INSERT INTO metric_spans
                   (time, tenant_id, trace_id, span_id, parent_span_id,
                    run_id, operation_name, service_name,
                    duration_ms, success, error_class, attributes)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                object : BatchPreparedStatementSetter {
                    override fun setValues(ps: PreparedStatement, i: Int) {
                        val span = spanList[i]
                        val tenantId = span.attributes.get(TENANT_ID_KEY) ?: "default"
                        val runId = span.attributes.get(RUN_ID_KEY)
                        val success = span.status.statusCode != StatusCode.ERROR
                        val durationMs = (span.endEpochNanos - span.startEpochNanos) / 1_000_000
                        val errorClass = if (!success) span.attributes.get(ERROR_TYPE_KEY) else null
                        val serviceName = span.resource.attributes.get(SERVICE_NAME_KEY) ?: "arc-reactor"
                        val attrs = spanAttributesToJson(span)

                        ps.setTimestamp(1, Timestamp.from(Instant.ofEpochSecond(0, span.startEpochNanos)))
                        ps.setString(2, tenantId)
                        ps.setString(3, span.traceId)
                        ps.setString(4, span.spanId)
                        ps.setString(5, span.parentSpanId.takeIf { it.isNotBlank() && it != "0000000000000000" })
                        ps.setString(6, runId)
                        ps.setString(7, span.name)
                        ps.setString(8, serviceName)
                        ps.setLong(9, durationMs)
                        ps.setBoolean(10, success)
                        ps.setString(11, errorClass)
                        ps.setString(12, attrs)
                    }

                    override fun getBatchSize() = spanList.size
                }
            )

            logger.debug { "Exported ${spans.size} spans to TimescaleDB" }
            CompletableResultCode.ofSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to export ${spans.size} spans to TimescaleDB" }
            CompletableResultCode.ofFailure()
        }
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    private fun spanAttributesToJson(span: SpanData): String {
        val map = mutableMapOf<String, String>()
        span.attributes.forEach { key, value ->
            if (key.key != "tenant_id") {
                map[key.key] = value.toString()
            }
        }
        // Simple JSON serialization to avoid Jackson dependency in this layer
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        private val TENANT_ID_KEY = AttributeKey.stringKey("tenant_id")
        private val RUN_ID_KEY = AttributeKey.stringKey("run_id")
        private val ERROR_TYPE_KEY = AttributeKey.stringKey("error.type")
        private val SERVICE_NAME_KEY = AttributeKey.stringKey("service.name")
    }
}
