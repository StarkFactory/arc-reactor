package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertSeverity
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dispatches alert notifications via configured channels.
 * Default implementation logs alerts. Extend with Slack, email, webhook, etc.
 */
interface AlertNotifier {
    fun notify(alert: AlertInstance)
}

class LogAlertNotifier : AlertNotifier {
    override fun notify(alert: AlertInstance) {
        val level = when (alert.severity) {
            AlertSeverity.CRITICAL -> "ERROR"
            AlertSeverity.WARNING -> "WARN"
            AlertSeverity.INFO -> "INFO"
        }
        logger.warn {
            "[$level] Alert: ${alert.message} (tenant=${alert.tenantId ?: "platform"}, " +
                "metric=${alert.metricValue}, threshold=${alert.threshold})"
        }
    }
}

class AlertNotificationService(
    private val notifiers: List<AlertNotifier> = listOf(LogAlertNotifier())
) {

    fun dispatch(alert: AlertInstance) {
        for (notifier in notifiers) {
            try {
                notifier.notify(alert)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to send alert notification via ${notifier::class.simpleName}" }
            }
        }
    }
}
