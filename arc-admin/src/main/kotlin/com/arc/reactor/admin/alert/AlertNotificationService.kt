package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertSeverity
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 알림 알림을 설정된 채널로 전송하는 인터페이스.
 *
 * 기본 구현체는 로그로 출력한다. Slack, 이메일, webhook 등으로 확장 가능.
 */
interface AlertNotifier {
    /** 알림 인스턴스를 전송한다. */
    fun notify(alert: AlertInstance)
}

/** 로그로 알림을 출력하는 기본 [AlertNotifier] 구현체. */
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

/**
 * 등록된 [AlertNotifier] 목록을 순회하며 알림을 전송하는 서비스.
 *
 * 개별 notifier 실패는 경고 로깅 후 건너뛴다 (fail-open).
 */
class AlertNotificationService(
    private val notifiers: List<AlertNotifier> = listOf(LogAlertNotifier())
) {

    /** 모든 notifier에게 알림을 전달한다. */
    fun dispatch(alert: AlertInstance) {
        for (notifier in notifiers) {
            try {
                notifier.notify(alert)
            } catch (e: Exception) {
                logger.warn(e) { "알림 전송 실패: ${notifier::class.simpleName}" }
            }
        }
    }
}
