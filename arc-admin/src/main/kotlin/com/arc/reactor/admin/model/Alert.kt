package com.arc.reactor.admin.model

import java.time.Instant
import java.util.UUID

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class AlertType {
    STATIC_THRESHOLD,
    BASELINE_ANOMALY,
    ERROR_BUDGET_BURN_RATE
}

enum class AlertStatus {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED
}

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
