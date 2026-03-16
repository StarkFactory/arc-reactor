package com.arc.reactor.admin.alert

import com.arc.reactor.admin.model.AlertInstance
import com.arc.reactor.admin.model.AlertSeverity
import com.arc.reactor.admin.model.AlertStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AlertNotificationServiceTest {

    private val testAlert = AlertInstance(
        id = "alert-1",
        ruleId = "rule-1",
        tenantId = "t1",
        severity = AlertSeverity.CRITICAL,
        status = AlertStatus.ACTIVE,
        message = "High error rate detected",
        metricValue = 0.15,
        threshold = 0.10
    )

    @Nested
    inner class Dispatch {

        @Test
        fun `to all notifiers를 디스패치한다`() {
            val notifier1 = mockk<AlertNotifier>(relaxed = true)
            val notifier2 = mockk<AlertNotifier>(relaxed = true)
            val service = AlertNotificationService(listOf(notifier1, notifier2))

            service.dispatch(testAlert)

            verify { notifier1.notify(testAlert) }
            verify { notifier2.notify(testAlert) }
        }

        @Test
        fun `a notifier throws exception일 때 continues dispatching`() {
            val failingNotifier = mockk<AlertNotifier>()
            val successNotifier = mockk<AlertNotifier>(relaxed = true)
            every { failingNotifier.notify(any()) } throws RuntimeException("Slack connection failed")

            val service = AlertNotificationService(listOf(failingNotifier, successNotifier))

            service.dispatch(testAlert)

            // 두 번째 알림자도 여전히 알림을 수신해야 합니다
            verify { successNotifier.notify(testAlert) }
        }

        @Test
        fun `empty notifier list를 처리한다`() {
            val service = AlertNotificationService(emptyList())

            // 예외를 던지면 안 됩니다
            service.dispatch(testAlert)
        }

        @Test
        fun `with all severity levels를 디스패치한다`() {
            val notifier = mockk<AlertNotifier>(relaxed = true)
            val service = AlertNotificationService(listOf(notifier))

            for (severity in AlertSeverity.entries) {
                service.dispatch(testAlert.copy(severity = severity))
            }

            verify(exactly = AlertSeverity.entries.size) { notifier.notify(any()) }
        }
    }

    @Nested
    inner class LogAlertNotifierTest {

        @Test
        fun `throw for any severity하지 않는다`() {
            val logNotifier = LogAlertNotifier()

            for (severity in AlertSeverity.entries) {
                logNotifier.notify(testAlert.copy(severity = severity))
            }
        }

        @Test
        fun `null tenant ID를 처리한다`() {
            val logNotifier = LogAlertNotifier()

            logNotifier.notify(testAlert.copy(tenantId = null))
        }
    }
}
