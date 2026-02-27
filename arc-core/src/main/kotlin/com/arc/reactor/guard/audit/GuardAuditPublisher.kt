package com.arc.reactor.guard.audit

import com.arc.reactor.guard.model.GuardCommand

/**
 * Guard Audit Publisher
 *
 * Publishes audit events for guard pipeline execution.
 * Used for SOC 2 compliance and observability.
 */
interface GuardAuditPublisher {

    /**
     * Publish a guard audit event.
     *
     * @param command The guard command being checked
     * @param stage The guard stage name (or "pipeline" for pipeline-level events)
     * @param result The result: "allowed", "rejected", or "error"
     * @param reason The rejection/error reason (null for allowed)
     * @param category The rejection category (null for allowed/error)
     * @param stageLatencyMs Latency of the individual stage in milliseconds
     * @param pipelineLatencyMs Cumulative pipeline latency in milliseconds
     */
    fun publish(
        command: GuardCommand,
        stage: String,
        result: String,
        reason: String?,
        category: String? = null,
        stageLatencyMs: Long,
        pipelineLatencyMs: Long
    )
}
