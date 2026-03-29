package com.arc.reactor.admin.collection

import com.arc.reactor.admin.AdminClassifiers
import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.util.HashUtils
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 가드 감사 이벤트를 [MetricRingBuffer]에 발행하는 publisher.
 *
 * SOC 2 컴플라이언스를 위해 가드 결과를 기록한다.
 * 입력 텍스트는 SHA-256 해싱되어 원문은 절대 저장되지 않는다.
 *
 * @see MetricRingBuffer 이벤트 버퍼
 */
class MetricGuardAuditPublisher(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor
) : GuardAuditPublisher {

    override fun publish(
        command: GuardCommand,
        stage: String,
        result: String,
        reason: String?,
        category: String?,
        stageLatencyMs: Long,
        pipelineLatencyMs: Long
    ) {
        val resolvedCategory = category ?: AdminClassifiers.classifyGuardStage(stage)
        val event = GuardEvent(
            tenantId = command.metadata["tenantId"]?.toString() ?: "default",
            userId = command.userId,
            channel = command.channel,
            stage = stage,
            category = resolvedCategory,
            reasonClass = if (result == "rejected") resolvedCategory else null,
            reasonDetail = reason?.take(500),
            action = result,
            inputHash = sha256(command.text),
            sessionId = command.metadata["sessionId"]?.toString(),
            requestId = command.metadata["requestId"]?.toString(),
            stageLatencyMs = stageLatencyMs,
            pipelineLatencyMs = pipelineLatencyMs
        )

        if (!ringBuffer.publish(event)) {
            healthMonitor.recordDrop(1)
            logger.debug { "MetricRingBuffer 가득 참, 가드 감사 이벤트 drop" }
        }
    }

    companion object {
        private fun sha256(text: String): String = HashUtils.sha256Hex(text)
    }
}
