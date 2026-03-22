package com.arc.reactor.agent.slo

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 로깅 기반 [SloAlertNotifier] 기본 구현체.
 *
 * SLO 위반을 WARN 레벨로 로깅한다.
 * 운영 환경에서는 Slack, 이메일 등 커스텀 구현체로 교체할 수 있다.
 */
class LoggingSloAlertNotifier : SloAlertNotifier {

    override suspend fun notify(violations: List<SloViolation>) {
        for (violation in violations) {
            logger.warn {
                "SLO 위반 [${violation.type}]: ${violation.message} " +
                    "(현재=${formatValue(violation)}, 임계값=${formatThreshold(violation)})"
            }
        }
    }

    /** 위반 유형에 따라 현재값을 포맷한다. */
    private fun formatValue(v: SloViolation): String = when (v.type) {
        SloViolationType.LATENCY -> "${v.currentValue.toLong()}ms"
        SloViolationType.ERROR_RATE -> String.format("%.1f%%", v.currentValue * 100)
    }

    /** 위반 유형에 따라 임계값을 포맷한다. */
    private fun formatThreshold(v: SloViolation): String = when (v.type) {
        SloViolationType.LATENCY -> "${v.threshold.toLong()}ms"
        SloViolationType.ERROR_RATE -> String.format("%.1f%%", v.threshold * 100)
    }
}
