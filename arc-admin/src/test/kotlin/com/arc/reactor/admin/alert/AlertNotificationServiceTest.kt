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
        fun `dispatches to all notifiers`() {
            val notifier1 = mockk<AlertNotifier>(relaxed = true)
            val notifier2 = mockk<AlertNotifier>(relaxed = true)
            val service = AlertNotificationService(listOf(notifier1, notifier2))

            service.dispatch(testAlert)

            verify { notifier1.notify(testAlert) }
            verify { notifier2.notify(testAlert) }
        }

        @Test
        fun `continues dispatching when a notifier throws exception`() {
            val failingNotifier = mockk<AlertNotifier>()
            val successNotifier = mockk<AlertNotifier>(relaxed = true)
            every { failingNotifier.notify(any()) } throws RuntimeException("Slack connection failed")

            val service = AlertNotificationService(listOf(failingNotifier, successNotifier))

            service.dispatch(testAlert)

            // Second notifier should still receive the alert
            verify { successNotifier.notify(testAlert) }
        }

        @Test
        fun `handles empty notifier list`() {
            val service = AlertNotificationService(emptyList())

            // Should not throw
            service.dispatch(testAlert)
        }

        @Test
        fun `dispatches with all severity levels`() {
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
        fun `does not throw for any severity`() {
            val logNotifier = LogAlertNotifier()

            for (severity in AlertSeverity.entries) {
                logNotifier.notify(testAlert.copy(severity = severity))
            }
        }

        @Test
        fun `handles null tenant ID`() {
            val logNotifier = LogAlertNotifier()

            logNotifier.notify(testAlert.copy(tenantId = null))
        }
    }
}
