package com.arc.reactor.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.InetAddress

/**
 * arc-admin 모듈 설정 프로퍼티 (`arc.reactor.admin.*`).
 *
 * 트레이싱, 메트릭 수집, 프라이버시, 데이터 보존, SLO 기본값, 스케일링 모드를 관리한다.
 */
@ConfigurationProperties(prefix = "arc.reactor.admin")
data class AdminProperties(
    val enabled: Boolean = false,
    val timescaleEnabled: Boolean = true,
    val tracing: TracingProperties = TracingProperties(),
    val collection: CollectionProperties = CollectionProperties(),
    val privacy: PrivacyProperties = PrivacyProperties(),
    val retention: RetentionProperties = RetentionProperties(),
    val slo: SloProperties = SloProperties(),
    val scaling: ScalingProperties = ScalingProperties()
)

/** 분산 트레이싱 설정. */
data class TracingProperties(
    val enabled: Boolean = true,
    val timescaleExport: Boolean = true,
    /** 트레이스 샘플링률: 1.0 = 100%, 0.1 = 10%. 프로덕션에서는 낮춰서 오버헤드를 줄인다. */
    val samplingRate: Double = 1.0,
    val otlp: OtlpProperties = OtlpProperties()
)

/** OTLP exporter 설정. */
data class OtlpProperties(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val protocol: String = "http/protobuf",
    val headers: Map<String, String> = emptyMap()
)

/** 메트릭 수집 파이프라인 설정. */
data class CollectionProperties(
    val ringBufferSize: Int = 8192,
    val flushIntervalMs: Long = 1000,
    val batchSize: Int = 1000,
    val writerThreads: Int = 1
)

/** 프라이버시 설정. */
data class PrivacyProperties(
    /** admin 메트릭/트레이스에 사용자 식별자를 저장한다. 개인 식별 메타데이터 저장을 피하려면 비활성화 유지. */
    val storeUserIdentifiers: Boolean = false,

    /** admin 메트릭/트레이스에 대화/세션 식별자를 저장한다. 프라이버시 우선 배포 시 비활성화 유지. */
    val storeSessionIdentifiers: Boolean = false
)

/** 데이터 보존 기간 설정. */
data class RetentionProperties(
    val rawDays: Int = 90,
    val hourlyDays: Int = 365,
    val dailyDays: Int = 1825,
    val auditYears: Int = 7,
    val compressionAfterDays: Int = 7
)

/** SLO 기본값 설정. */
data class SloProperties(
    val defaultAvailability: Double = 0.995,
    val defaultLatencyP99Ms: Long = 10000
)

/** 스케일링 설정 (인스턴스 ID, 쓰기 모드). */
data class ScalingProperties(
    val instanceId: String = hostname(),
    val mode: ScalingMode = ScalingMode.DIRECT_WRITE
)

/** 메트릭 쓰기 모드. DIRECT_WRITE: DB 직접 기록, KAFKA: Kafka를 통한 기록. */
enum class ScalingMode {
    DIRECT_WRITE,
    KAFKA
}

private fun hostname(): String = try {
    InetAddress.getLocalHost().hostName
} catch (_: Exception) {
    "unknown"
}
