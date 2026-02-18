package com.arc.reactor.controller

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.model.McpServerStatus
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

@Tag(name = "Ops Dashboard", description = "Operational dashboard APIs (ADMIN)")
@RestController
@RequestMapping("/api/ops")
class OpsDashboardController(
    private val mcpManager: McpManager,
    private val properties: AgentProperties,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>
) {

    @Operation(summary = "Get operations dashboard snapshot (ADMIN)")
    @GetMapping("/dashboard")
    fun dashboard(
        @RequestParam(required = false) names: List<String>?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val metricNames = names?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_METRIC_NAMES
        val registry = meterRegistryProvider.ifAvailable

        val response = OpsDashboardResponse(
            generatedAt = Instant.now().toEpochMilli(),
            ragEnabled = properties.rag.enabled,
            mcp = mcpSummary(),
            metrics = metricNames.map { name -> metricSnapshot(name, registry) }
        )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "List available ops metric names (ADMIN)")
    @GetMapping("/metrics/names")
    fun metricNames(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val registry = meterRegistryProvider.ifAvailable
        val names = registry?.meters
            ?.asSequence()
            ?.map { it.id.name }
            ?.filter {
                it.startsWith("arc.") ||
                    it.startsWith("jvm.") ||
                    it.startsWith("process.") ||
                    it.startsWith("system.")
            }
            ?.distinct()
            ?.sorted()
            ?.toList()
            ?: emptyList()
        return ResponseEntity.ok(names)
    }

    private fun mcpSummary(): McpStatusSummary {
        val servers = mcpManager.listServers()
        val statusCounts = servers
            .groupingBy { mcpManager.getStatus(it.name) ?: McpServerStatus.PENDING }
            .eachCount()
            .mapKeys { it.key.name }

        return McpStatusSummary(
            total = servers.size,
            statusCounts = statusCounts
        )
    }

    private fun metricSnapshot(name: String, registry: MeterRegistry?): OpsMetricSnapshot {
        if (registry == null) {
            return OpsMetricSnapshot(
                name = name,
                meterCount = 0,
                measurements = emptyMap()
            )
        }

        val meters = registry.find(name).meters()
        if (meters.isEmpty()) {
            return OpsMetricSnapshot(
                name = name,
                meterCount = 0,
                measurements = emptyMap()
            )
        }

        val aggregated = linkedMapOf<String, Double>()
        meters.forEach { meter ->
            meter.measure().forEach { measurement ->
                val key = measurement.statistic.toMetricKey()
                aggregated[key] = (aggregated[key] ?: 0.0) + measurement.value
            }
        }

        return OpsMetricSnapshot(
            name = name,
            meterCount = meters.size,
            measurements = aggregated
        )
    }

    private fun io.micrometer.core.instrument.Statistic.toMetricKey(): String = name.lowercase()

    companion object {
        private val DEFAULT_METRIC_NAMES = setOf(
            "arc.agent.executions",
            "arc.agent.errors",
            "arc.agent.tool.calls",
            "arc.slack.inbound.total",
            "arc.slack.duplicate.total",
            "arc.slack.dropped.total",
            "arc.slack.handler.duration",
            "arc.slack.api.duration",
            "arc.slack.api.retry.total"
        )
    }
}

data class OpsDashboardResponse(
    val generatedAt: Long,
    val ragEnabled: Boolean,
    val mcp: McpStatusSummary,
    val metrics: List<OpsMetricSnapshot>
)

data class McpStatusSummary(
    val total: Int,
    val statusCounts: Map<String, Int>
)

data class OpsMetricSnapshot(
    val name: String,
    val meterCount: Int,
    val measurements: Map<String, Double>
)
