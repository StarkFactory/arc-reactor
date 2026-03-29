package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.HitlEvent
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * 도구 호출에서 HITL(Human-in-the-Loop) 승인/거부 이벤트를 수집하는 Hook.
 *
 * ToolCallOrchestrator가 설정한 HITL 메타데이터를 읽는다:
 * - `hitlWaitMs_{toolName}_{callIndex}`: 사람 승인 대기 시간 (ms)
 * - `hitlApproved_{toolName}_{callIndex}`: 사람이 승인했는지 여부
 * - `hitlRejectionReason_{toolName}_{callIndex}`: 거부 사유 (거부 시)
 *
 * 하위 호환성을 위해 callIndex 접미사 없는 레거시 키도 지원한다.
 *
 * Order 201: [MetricCollectionHook] (200) 이후에 실행된다.
 */
class HitlEventHook(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor
) : AfterToolCallHook {

    override val order: Int = 201
    override val enabled: Boolean = true
    override val failOnError: Boolean = false

    override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
        try {
            val meta = context.agentContext.metadata
            val toolName = context.toolName
            val keySuffix = "${toolName}_${context.callIndex}"
            val waitMs = readMeta(meta, "hitlWaitMs_$keySuffix", "hitlWaitMs_$toolName")
                ?.toLongOrNull()
                ?: return
            val approved = readMeta(meta, "hitlApproved_$keySuffix", "hitlApproved_$toolName")
                ?.toBoolean()
                ?: false
            val reason = readMeta(meta, "hitlRejectionReason_$keySuffix", "hitlRejectionReason_$toolName")

            val event = HitlEvent(
                tenantId = meta["tenantId"]?.toString() ?: "default",
                runId = context.agentContext.runId,
                toolName = toolName,
                approved = approved,
                waitMs = waitMs,
                rejectionReason = reason
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthMonitor.recordDrop(1)
            logger.warn(e) { "HITL 이벤트 기록 실패: ${context.toolName}" }
        }
    }

    private fun readMeta(meta: Map<String, Any>, primaryKey: String, fallbackKey: String): String? {
        return meta[primaryKey]?.toString() ?: meta[fallbackKey]?.toString()
    }
}
