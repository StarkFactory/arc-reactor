package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Micrometer 기반 [EvaluationMetricsCollector] 구현체.
 *
 * 모든 메트릭은 `arc.reactor.eval.*` 접두사를 사용하여 기존 메트릭과 충돌하지 않는다.
 *
 * ## 메트릭 이름
 *
 * | 지표 | 이름 | 유형 | 태그 |
 * |------|------|------|------|
 * | task 성공률 | `arc.reactor.eval.task.completed` | Counter | `result`, `error_code` |
 * | task 지연 | `arc.reactor.eval.task.duration` | Timer | `result` |
 * | 도구 호출 수 | `arc.reactor.eval.tool.calls` | DistributionSummary | — |
 * | 토큰 비용 | `arc.reactor.eval.token.cost.usd` | Counter | `model` |
 * | HITL 개입 | `arc.reactor.eval.human.override` | Counter | `outcome`, `tool` |
 * | 안전 거부 | `arc.reactor.eval.safety.rejection` | Counter | `stage`, `reason` |
 *
 * ## Fail-Open 원칙
 *
 * Micrometer 예외는 모두 로깅 후 삼킨다. 메트릭 수집 실패가 핵심 에이전트 실행을
 * 방해하면 안 된다.
 *
 * @param registry Micrometer 메트릭 레지스트리
 */
class MicrometerEvaluationMetricsCollector(
    private val registry: MeterRegistry
) : EvaluationMetricsCollector {

    override fun recordTaskCompleted(
        success: Boolean,
        durationMs: Long,
        errorCode: String?
    ) {
        runCatching {
            val result = if (success) RESULT_SUCCESS else RESULT_FAILURE
            val errorTag = errorCode ?: NONE_TAG
            Counter.builder(METRIC_TASK_COMPLETED)
                .tag(TAG_RESULT, result)
                .tag(TAG_ERROR_CODE, errorTag)
                .register(registry)
                .increment()
            Timer.builder(METRIC_TASK_DURATION)
                .tag(TAG_RESULT, result)
                .register(registry)
                .record(durationMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }.onFailure { e ->
            logger.warn(e) { "recordTaskCompleted 실패: success=$success, durationMs=$durationMs" }
        }
    }

    override fun recordToolCallCount(count: Int, toolNames: List<String>) {
        runCatching {
            DistributionSummary.builder(METRIC_TOOL_CALLS)
                .baseUnit("calls")
                .register(registry)
                .record(count.toDouble().coerceAtLeast(0.0))
        }.onFailure { e ->
            logger.warn(e) { "recordToolCallCount 실패: count=$count" }
        }
    }

    override fun recordTokenCost(costUsd: Double, model: String) {
        runCatching {
            Counter.builder(METRIC_TOKEN_COST)
                .tag(TAG_MODEL, model.ifBlank { UNKNOWN_TAG })
                .baseUnit("usd")
                .register(registry)
                .increment(costUsd.coerceAtLeast(0.0))
        }.onFailure { e ->
            logger.warn(e) { "recordTokenCost 실패: costUsd=$costUsd, model=$model" }
        }
    }

    override fun recordHumanOverride(outcome: HumanOverrideOutcome, toolName: String) {
        runCatching {
            Counter.builder(METRIC_HUMAN_OVERRIDE)
                .tag(TAG_OUTCOME, outcome.name.lowercase())
                .tag(TAG_TOOL, toolName.ifBlank { UNKNOWN_TAG })
                .register(registry)
                .increment()
        }.onFailure { e ->
            logger.warn(e) { "recordHumanOverride 실패: outcome=$outcome, tool=$toolName" }
        }
    }

    override fun recordSafetyRejection(stage: SafetyRejectionStage, reason: String) {
        runCatching {
            Counter.builder(METRIC_SAFETY_REJECTION)
                .tag(TAG_STAGE, stage.name.lowercase())
                .tag(TAG_REASON, reason.ifBlank { UNKNOWN_TAG })
                .register(registry)
                .increment()
        }.onFailure { e ->
            logger.warn(e) { "recordSafetyRejection 실패: stage=$stage, reason=$reason" }
        }
    }

    override fun recordToolResponseKind(kind: String, toolName: String) {
        runCatching {
            Counter.builder(METRIC_TOOL_RESPONSE_KIND)
                .tag(TAG_KIND, kind.ifBlank { UNKNOWN_TAG })
                .tag(TAG_TOOL, toolName.ifBlank { UNKNOWN_TAG })
                .register(registry)
                .increment()
        }.onFailure { e ->
            logger.warn(e) { "recordToolResponseKind 실패: kind=$kind, tool=$toolName" }
        }
    }

    companion object {
        const val METRIC_TASK_COMPLETED = "arc.reactor.eval.task.completed"
        const val METRIC_TASK_DURATION = "arc.reactor.eval.task.duration"
        const val METRIC_TOOL_CALLS = "arc.reactor.eval.tool.calls"
        const val METRIC_TOKEN_COST = "arc.reactor.eval.token.cost.usd"
        const val METRIC_HUMAN_OVERRIDE = "arc.reactor.eval.human.override"
        const val METRIC_SAFETY_REJECTION = "arc.reactor.eval.safety.rejection"

        /** R224: 도구 응답 요약 분류별 카운터 (R222+R223 시너지). */
        const val METRIC_TOOL_RESPONSE_KIND = "arc.reactor.eval.tool.response.kind"

        const val TAG_RESULT = "result"
        const val TAG_ERROR_CODE = "error_code"
        const val TAG_MODEL = "model"
        const val TAG_OUTCOME = "outcome"
        const val TAG_TOOL = "tool"
        const val TAG_STAGE = "stage"
        const val TAG_REASON = "reason"

        /** R224: 도구 응답 요약 분류 태그. */
        const val TAG_KIND = "kind"

        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILURE = "failure"
        const val NONE_TAG = "none"
        const val UNKNOWN_TAG = "unknown"
    }
}
