package com.arc.reactor.admin.model

import java.time.Instant
import java.util.UUID

/** 알림 심각도. */
enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

/** 알림 유형. 정적 임계값, baseline 이상치, error budget burn rate. */
enum class AlertType {
    STATIC_THRESHOLD,
    BASELINE_ANOMALY,
    ERROR_BUDGET_BURN_RATE
}

/** 알림 상태. 활성, 확인됨, 해결됨. */
enum class AlertStatus {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED
}

/** 알림 규칙 정의. 테넌트 범위 또는 플랫폼 전역으로 설정 가능. */
data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val tenantId: String? = null,
    val name: String,
    val description: String = "",
    val type: AlertType,
    val severity: AlertSeverity = AlertSeverity.WARNING,
    val metric: String,
    val threshold: Double,
    val windowMinutes: Int = 15,
    val enabled: Boolean = true,
    val platformOnly: Boolean = false,
    val createdAt: Instant = Instant.now()
)

/** 발동된 알림 인스턴스. 규칙 평가 결과로 생성된다. */
data class AlertInstance(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val tenantId: String? = null,
    val severity: AlertSeverity,
    val status: AlertStatus = AlertStatus.ACTIVE,
    val message: String,
    val metricValue: Double = 0.0,
    val threshold: Double = 0.0,
    val firedAt: Instant = Instant.now(),
    val resolvedAt: Instant? = null,
    val acknowledgedBy: String? = null
)
