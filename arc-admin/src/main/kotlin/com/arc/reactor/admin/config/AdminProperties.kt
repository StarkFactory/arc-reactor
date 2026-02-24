package com.arc.reactor.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.InetAddress

@ConfigurationProperties(prefix = "arc.reactor.admin")
data class AdminProperties(
    val enabled: Boolean = false,
    val timescaleEnabled: Boolean = true,
    val tracing: TracingProperties = TracingProperties(),
    val collection: CollectionProperties = CollectionProperties(),
    val retention: RetentionProperties = RetentionProperties(),
    val slo: SloProperties = SloProperties(),
    val scaling: ScalingProperties = ScalingProperties()
)

data class TracingProperties(
    val enabled: Boolean = true,
    val timescaleExport: Boolean = true,
    val otlp: OtlpProperties = OtlpProperties()
)

data class OtlpProperties(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val protocol: String = "http/protobuf",
    val headers: Map<String, String> = emptyMap()
)

data class CollectionProperties(
    val ringBufferSize: Int = 8192,
    val flushIntervalMs: Long = 1000,
    val batchSize: Int = 1000,
    val writerThreads: Int = 3
)

data class RetentionProperties(
    val rawDays: Int = 90,
    val hourlyDays: Int = 365,
    val dailyDays: Int = 1825,
    val auditYears: Int = 7,
    val compressionAfterDays: Int = 7
)

data class SloProperties(
    val defaultAvailability: Double = 0.995,
    val defaultLatencyP99Ms: Long = 10000
)

data class ScalingProperties(
    val instanceId: String = hostname(),
    val mode: ScalingMode = ScalingMode.DIRECT_WRITE
)

enum class ScalingMode {
    DIRECT_WRITE,
    KAFKA
}

private fun hostname(): String = try {
    InetAddress.getLocalHost().hostName
} catch (_: Exception) {
    "unknown"
}
