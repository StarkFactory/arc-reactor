package com.arc.reactor.agent.slo

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("LoggingSloAlertNotifier")
class LoggingSloAlertNotifierTest {

    private val notifier = LoggingSloAlertNotifier()

    @Test
    fun `레이턴시 위반을 예외 없이 로깅한다`() = runTest {
        val violation = SloViolation(
            type = SloViolationType.LATENCY,
            currentValue = 3000.0,
            threshold = 2000.0,
            message = "P95 레이턴시 3000ms가 임계값 2000ms를 초과했습니다",
            timestamp = Instant.now()
        )

        // 예외가 발생하지 않으면 성공
        notifier.notify(listOf(violation))
    }

    @Test
    fun `에러율 위반을 예외 없이 로깅한다`() = runTest {
        val violation = SloViolation(
            type = SloViolationType.ERROR_RATE,
            currentValue = 0.08,
            threshold = 0.05,
            message = "에러율 8.0%가 임계값 5.0%를 초과했습니다",
            timestamp = Instant.now()
        )

        notifier.notify(listOf(violation))
    }

    @Test
    fun `빈 위반 목록을 예외 없이 처리한다`() = runTest {
        notifier.notify(emptyList())
    }

    @Test
    fun `복수 위반을 예외 없이 로깅한다`() = runTest {
        val violations = listOf(
            SloViolation(
                type = SloViolationType.LATENCY,
                currentValue = 5000.0,
                threshold = 2000.0,
                message = "레이턴시 초과"
            ),
            SloViolation(
                type = SloViolationType.ERROR_RATE,
                currentValue = 0.15,
                threshold = 0.05,
                message = "에러율 초과"
            )
        )

        notifier.notify(violations)
    }
}
