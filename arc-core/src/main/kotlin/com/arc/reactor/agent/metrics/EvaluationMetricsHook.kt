package com.arc.reactor.agent.metrics

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [EvaluationMetricsCollector]를 에이전트 라이프사이클에 연결하는 어댑터 Hook.
 *
 * `AfterAgentCompleteHook`로 등록되어 에이전트 작업이 완료될 때마다 수집기의 6개 지표를
 * 자동으로 기록한다. 이 Hook 자체는 **관측 전용** 이며 에이전트 동작에 전혀 영향을 주지 않는다
 * (fail-open 원칙).
 *
 * ## 수집 경로
 *
 * | 메트릭 | 데이터 소스 |
 * |--------|-------------|
 * | task_success_rate, latency | [AgentResponse.success], [AgentResponse.totalDurationMs] |
 * | avg tool calls | [AgentResponse.toolsUsed].size |
 * | token cost | `hookContext.metadata["costEstimateUsd"]` (String, ExecutionResultFinalizer 설정) |
 * | human override rate | `hookContext.metadata["hitlApproved_*"]`, `hitlRejectionReason_*` |
 * | safety rejection | `hookContext.metadata["blockReason"]`, `AgentResponse.errorCode` |
 *
 * ## opt-in 활성화
 *
 * 기본적으로 자동 구성은 [NoOpEvaluationMetricsCollector]를 제공하므로 이 Hook이 등록되어도
 * 실제 메트릭 기록은 발생하지 않는다 (오버헤드 0). Micrometer 백엔드를 원하면 사용자가
 * [MicrometerEvaluationMetricsCollector]를 `@Bean`으로 등록한다.
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 호출 경로 미수정
 * - Redis 캐시: `systemPrompt` 미수정
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정
 *
 * @param collector 실제 메트릭을 기록하는 수집기 구현체
 * @see EvaluationMetricsCollector 수집기 인터페이스
 * @see MicrometerEvaluationMetricsCollector Micrometer 기반 구현체
 */
class EvaluationMetricsHook(
    private val collector: EvaluationMetricsCollector
) : AfterAgentCompleteHook {

    /** AfterAgentComplete Hook 순서 — 표준 Hook 범위 (100-199) */
    override val order: Int = 150

    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(
        context: HookContext,
        response: AgentResponse
    ) {
        try {
            recordTaskAndTools(context, response)
            recordCost(context)
            recordHumanOverrides(context)
            recordSafetyRejections(context, response)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "EvaluationMetricsHook 기록 실패 (무시): runId=${context.runId}" }
        }
    }

    /** task 성공/실패 + 지연 + 도구 호출 수. */
    private fun recordTaskAndTools(context: HookContext, response: AgentResponse) {
        val durationMs = if (response.totalDurationMs > 0) {
            response.totalDurationMs
        } else {
            context.durationMs()
        }
        collector.recordTaskCompleted(
            success = response.success,
            durationMs = durationMs,
            errorCode = response.errorCode
        )
        collector.recordToolCallCount(
            count = response.toolsUsed.size,
            toolNames = response.toolsUsed
        )
    }

    /** 추정 비용 기록 (ExecutionResultFinalizer가 String으로 포맷한 costEstimateUsd). */
    private fun recordCost(context: HookContext) {
        val raw = context.metadata[COST_ESTIMATE_KEY] ?: return
        val costUsd = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return
        if (costUsd <= 0.0) return
        val model = context.metadata[MODEL_KEY]?.toString().orEmpty()
        collector.recordTokenCost(costUsd, model)
    }

    /**
     * HITL 승인/거부 메타데이터에서 사람 개입 결과를 집계한다.
     * [com.arc.reactor.agent.impl.ToolCallOrchestrator.recordApprovalMetadata]가 기록한 키를 읽는다.
     */
    private fun recordHumanOverrides(context: HookContext) {
        context.metadata.entries
            .filter { (key, _) -> key.startsWith(HITL_APPROVED_PREFIX) }
            .forEach { (key, value) ->
                val approved = value as? Boolean ?: return@forEach
                val suffix = key.removePrefix(HITL_APPROVED_PREFIX)
                val toolName = extractToolNameFromSuffix(suffix)
                val outcome = if (approved) {
                    HumanOverrideOutcome.APPROVED
                } else {
                    val hasTimeout = context.metadata[HITL_REJECTION_PREFIX + suffix]
                        ?.toString()
                        ?.contains("timed out", ignoreCase = true) == true
                    if (hasTimeout) HumanOverrideOutcome.TIMEOUT else HumanOverrideOutcome.REJECTED
                }
                collector.recordHumanOverride(outcome, toolName)
            }
    }

    /**
     * 안전 거부 기록: `blockReason` 메타데이터 또는 `errorCode`에서 stage를 추정한다.
     *
     * 분류 기준 (우선순위 순):
     * 1. `OUTPUT_GUARD_REJECTED` → OUTPUT_GUARD
     * 2. `GUARD_REJECTED` → GUARD
     * 3. `HOOK_REJECTED` → HOOK
     * 4. blockReason만 존재 → GUARD (기본값)
     */
    private fun recordSafetyRejections(context: HookContext, response: AgentResponse) {
        val blockReason = context.metadata[BLOCK_REASON_KEY]?.toString()
        val errorCode = response.errorCode
        if (blockReason == null && errorCode == null) return

        val stage = when (errorCode) {
            "OUTPUT_GUARD_REJECTED" -> SafetyRejectionStage.OUTPUT_GUARD
            "GUARD_REJECTED" -> SafetyRejectionStage.GUARD
            "HOOK_REJECTED" -> SafetyRejectionStage.HOOK
            null -> if (blockReason != null) SafetyRejectionStage.GUARD else return
            else -> return
        }
        val reason = blockReason ?: errorCode ?: MicrometerEvaluationMetricsCollector.UNKNOWN_TAG
        collector.recordSafetyRejection(stage, reason)
    }

    /**
     * `hitlApproved_{suffix}` 에서 suffix를 파싱하여 도구 이름을 추출한다.
     * ToolCallOrchestrator는 suffix를 "toolName" 또는 "toolName_iteration" 형태로 만든다.
     */
    private fun extractToolNameFromSuffix(suffix: String): String {
        // suffix 형태: "toolname" 또는 "toolname_N" 또는 hash
        // 가장 안전한 방법: 마지막 언더스코어 이전을 tool 이름으로 간주, 단 숫자로 끝나는 경우만
        val lastUnderscore = suffix.lastIndexOf('_')
        if (lastUnderscore > 0) {
            val tail = suffix.substring(lastUnderscore + 1)
            if (tail.all { it.isDigit() }) {
                return suffix.substring(0, lastUnderscore)
            }
        }
        return suffix.ifBlank { MicrometerEvaluationMetricsCollector.UNKNOWN_TAG }
    }

    companion object {
        /** [com.arc.reactor.agent.impl.ExecutionResultFinalizer]가 설정하는 비용 메타데이터 키. */
        const val COST_ESTIMATE_KEY = "costEstimateUsd"

        /** 모델 이름 메타데이터 키. */
        const val MODEL_KEY = "model"

        /** HITL 승인 메타데이터 키 접두사 (ToolCallOrchestrator 참조). */
        const val HITL_APPROVED_PREFIX = "hitlApproved_"

        /** HITL 거부 사유 메타데이터 키 접두사. */
        const val HITL_REJECTION_PREFIX = "hitlRejectionReason_"

        /** 차단 사유 메타데이터 키. */
        const val BLOCK_REASON_KEY = "blockReason"
    }
}
