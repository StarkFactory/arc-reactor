package com.arc.reactor.agent.metrics

/**
 * No-op [SlaMetrics] 구현체 (기본값).
 *
 * 메트릭 백엔드(Micrometer)가 설정되지 않은 경우 사용된다. 모든 메서드가 no-op.
 */
class NoOpSlaMetrics : SlaMetrics {
    override fun recordReActConvergence(
        steps: Int,
        stopReason: String,
        durationMs: Long,
        metadata: Map<String, Any>
    ) {
        // no-op
    }

    override fun recordToolFailureDetail(
        toolName: String,
        errorType: String,
        errorMessage: String,
        durationMs: Long
    ) {
        // no-op
    }

    override fun recordAvailabilitySample(healthy: Boolean) {
        // no-op
    }

    override fun recordE2eLatency(durationMs: Long, channel: String) {
        // no-op
    }
}
