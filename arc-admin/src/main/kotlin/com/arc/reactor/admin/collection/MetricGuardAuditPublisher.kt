package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.model.GuardCommand
import mu.KotlinLogging
import java.security.MessageDigest

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
        val event = GuardEvent(
            tenantId = command.metadata["tenantId"]?.toString() ?: "default",
            userId = command.userId,
            channel = command.channel,
            stage = stage,
            category = category ?: classifyReason(stage),
            reasonClass = if (result == "rejected") category ?: classifyReason(stage) else null,
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
            logger.debug { "MetricRingBuffer full, guard audit event dropped" }
        }
    }

    companion object {
        private fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        private fun classifyReason(stage: String): String {
            return when {
                stage.contains("RateLimit", ignoreCase = true) -> "rate_limit"
                stage.contains("Injection", ignoreCase = true) -> "prompt_injection"
                stage.contains("Classification", ignoreCase = true) -> "classification"
                stage.contains("Permission", ignoreCase = true) -> "permission"
                stage.contains("InputValidation", ignoreCase = true) -> "input_validation"
                stage.contains("Unicode", ignoreCase = true) -> "unicode_normalization"
                stage.contains("TopicDrift", ignoreCase = true) -> "topic_drift"
                else -> "other"
            }
        }
    }
}
