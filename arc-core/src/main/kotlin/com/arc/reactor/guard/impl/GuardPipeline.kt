package com.arc.reactor.guard.impl

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 5+2단계 Guard 파이프라인
 *
 * 등록된 [GuardStage]들을 order 순서대로 실행한다.
 * 어떤 단계든 [GuardResult.Rejected]를 반환하면 즉시 파이프라인을 중단한다.
 *
 * ## 오류 처리 정책: Fail-Close
 * Guard 단계에서 예외가 발생하면 파이프라인은 요청을 **거부**한다.
 * 이 fail-close 동작은 오작동하는 단계에 의해 보안이 우회되는 것을 방지한다.
 *
 * 왜 fail-close인가: Guard는 보안 컴포넌트이므로, 불확실한 상황에서는
 * 차단하는 것이 허용하는 것보다 안전하다. 이는 기본적으로 fail-open으로
 * 동작하는 [com.arc.reactor.hook.HookExecutor]와 의도적으로 대조된다.
 * ([com.arc.reactor.hook.AgentHook.failOnError]로 개별 변경 가능)
 *
 * ## 실행 흐름
 * ```
 * [0.UnicodeNormalization] → [1.RateLimit] → [2.InputValidation]
 *     → [3.InjectionDetection] → [4.Classification] → [5.Permission]
 *     → [10.TopicDriftDetection] → Allowed
 * ```
 *
 * ## 정규화된 텍스트 전파
 * UnicodeNormalizationStage가 "normalized:{텍스트}" 힌트를 반환하면,
 * 파이프라인이 커맨드의 텍스트를 정규화된 버전으로 교체하여
 * 후속 단계에 전달한다. 이를 통해 Unicode 우회 공격을 방어한다.
 *
 * @param stages Guard 단계 목록 (비활성화된 단계는 자동 필터링)
 * @param auditPublisher 감사 이벤트 퍼블리셔 (SOC 2 준수용, 선택사항)
 *
 * @see GuardStage 개별 단계 인터페이스
 * @see RequestGuard Guard 계약
 */
class GuardPipeline(
    stages: List<GuardStage> = emptyList(),
    private val auditPublisher: GuardAuditPublisher? = null,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer(),
    /**
     * R251: Guard stage 예외를 `execution.error{stage="guard"}` 메트릭에 기록.
     * 기본값 NoOp으로 backward compat. Fail-close로 Rejected가 반환되지만 OutputGuard(R250)와
     * 대칭적으로 시스템 레벨 이상(stage 구현 버그, Redis 장애 등)을 별도 관측한다.
     *
     * `safety.rejection{stage=guard, reason=injection}` — 정책에 의한 정상 거부
     * `execution.error{stage=guard, exception=...}` — stage 구현이 throw한 예외
     */
    private val evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector
) : RequestGuard {

    // ── 초기화: 활성화된 단계만 order 기준 정렬하여 보관 ──
    private val sortedStages: List<GuardStage> = stages
        .filter { it.enabled }
        .sortedBy { it.order }

    init {
        // ── 중복 order 검증 ──
        // 같은 order 값을 가진 단계가 있으면 실행 순서가 불확정적이므로 경고한다
        validateStageOrders()
        logger.debug {
            val desc = sortedStages.joinToString { "order=${it.order}:${it.stageName}" }
            "Guard 단계 초기화: [$desc]"
        }
    }

    /**
     * 중복 order 값을 가진 단계들에 대해 경고를 출력한다.
     * 같은 order를 공유하는 단계들의 실행 순서는 보장되지 않는다.
     */
    private fun validateStageOrders() {
        sortedStages.groupBy { it.order }
            .filter { it.value.size > 1 }
            .forEach { (order, stages) ->
                val names = stages.joinToString { it.stageName }
                logger.warn {
                    "Guard 단계 order 중복: order=$order: [$names]. " +
                        "이 단계들 간의 실행 순서는 보장되지 않습니다."
                }
            }
    }

    override suspend fun guard(command: GuardCommand): GuardResult {
        if (sortedStages.isEmpty()) {
            logger.debug { "활성화된 Guard 단계 없음, 요청 허용" }
            return GuardResult.Allowed.DEFAULT
        }
        val span = tracer.startSpan(
            "arc.guard.input",
            mapOf("guard.stage.count" to sortedStages.size.toString())
        )
        try {
            return executeAllStages(command, span)
        } finally {
            span.close()
        }
    }

    /** 모든 Guard 단계를 순서대로 실행하고 최종 결과를 반환한다. */
    private suspend fun executeAllStages(
        command: GuardCommand,
        span: ArcReactorTracer.SpanHandle
    ): GuardResult {
        val pipelineStartNanos = System.nanoTime()
        var currentCommand = command

        for (stage in sortedStages) {
            val outcome = executeSingleStage(
                stage, currentCommand, pipelineStartNanos, span
            )
            when (outcome) {
                is StageOutcome.Passed -> {
                    currentCommand = outcome.effectiveCommand
                }
                is StageOutcome.Rejected -> return outcome.result
            }
        }
        publishAudit(
            currentCommand, "pipeline", "allowed", null, null,
            pipelineStartNanos, pipelineStartNanos
        )
        span.setAttribute("guard.result", "allowed")
        return GuardResult.Allowed.DEFAULT
    }

    /**
     * 단일 Guard 단계를 실행한다.
     * 허용 시 [StageOutcome.Passed], 거부/에러 시 [StageOutcome.Rejected]를 반환한다.
     */
    private suspend fun executeSingleStage(
        stage: GuardStage,
        command: GuardCommand,
        pipelineStartNanos: Long,
        span: ArcReactorTracer.SpanHandle
    ): StageOutcome {
        val stageStartNanos = System.nanoTime()
        return try {
            logger.debug { "Guard 단계 실행: ${stage.stageName}" }
            when (val result = stage.enforce(command)) {
                is GuardResult.Allowed -> handleAllowed(
                    stage, result, command, stageStartNanos, pipelineStartNanos
                )
                is GuardResult.Rejected -> handleRejected(
                    stage, result, command, stageStartNanos, pipelineStartNanos, span
                )
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            handleStageError(
                stage, e, command, stageStartNanos, pipelineStartNanos, span
            )
        }
    }

    /** 단계 통과 시 정규화된 텍스트를 적용하고 감사 이벤트를 발행한다. */
    private fun handleAllowed(
        stage: GuardStage,
        result: GuardResult.Allowed,
        command: GuardCommand,
        stageStartNanos: Long,
        pipelineStartNanos: Long
    ): StageOutcome.Passed {
        logger.debug { "Guard 단계 ${stage.stageName} 통과" }
        val effective = applyNormalizedText(result, command)
        publishAudit(
            effective, stage.stageName, "allowed", null, null,
            stageStartNanos, pipelineStartNanos
        )
        return StageOutcome.Passed(effective)
    }

    /** 단계 거부 시 tracing 속성을 설정하고 감사 이벤트를 발행한다. */
    private fun handleRejected(
        stage: GuardStage,
        result: GuardResult.Rejected,
        command: GuardCommand,
        stageStartNanos: Long,
        pipelineStartNanos: Long,
        span: ArcReactorTracer.SpanHandle
    ): StageOutcome.Rejected {
        logger.warn { "Guard 단계 ${stage.stageName} 거부: ${result.reason}" }
        publishAudit(
            command, stage.stageName, "rejected",
            result.reason, result.category.name,
            stageStartNanos, pipelineStartNanos
        )
        span.setAttribute("guard.result", "rejected")
        span.setAttribute("guard.stage", stage.stageName)
        span.setAttribute("guard.reason", result.reason)
        return StageOutcome.Rejected(result.copy(stage = stage.stageName))
    }

    /** Fail-Close: 단계 예외 발생 시 요청을 거부한다. */
    private fun handleStageError(
        stage: GuardStage,
        e: Exception,
        command: GuardCommand,
        stageStartNanos: Long,
        pipelineStartNanos: Long,
        span: ArcReactorTracer.SpanHandle
    ): StageOutcome.Rejected {
        // R251: Guard stage 예외를 execution.error 메트릭에 기록
        // fail-close로 Rejected가 반환되지만 stage 구현 버그 등 시스템 레벨 이상을 별도 관측
        evaluationMetricsCollector.recordError(ExecutionStage.GUARD, e)
        logger.error(e) { "Guard 단계 ${stage.stageName} 실행 실패" }
        publishAudit(
            command, stage.stageName, "error", e.message, null,
            stageStartNanos, pipelineStartNanos
        )
        span.setAttribute("guard.result", "error")
        span.setAttribute("guard.stage", stage.stageName)
        span.setError(e)
        return StageOutcome.Rejected(
            GuardResult.Rejected(
                reason = "보안 검사 실패",
                category = RejectionCategory.SYSTEM_ERROR,
                stage = stage.stageName
            )
        )
    }

    /** 단일 Guard 단계 실행 결과를 표현하는 sealed 인터페이스. */
    private sealed interface StageOutcome {
        /** 단계 통과 — 정규화된 커맨드를 후속 단계에 전달한다. */
        data class Passed(val effectiveCommand: GuardCommand) : StageOutcome
        /** 단계 거부 또는 에러 — 파이프라인을 즉시 종료한다. */
        data class Rejected(val result: GuardResult.Rejected) : StageOutcome
    }

    /**
     * 정규화된 텍스트 힌트가 있으면 커맨드의 텍스트를 교체한다.
     *
     * UnicodeNormalizationStage 등이 "normalized:" 접두사 힌트로
     * 정규화된 텍스트를 전달하면 후속 단계에서 사용하도록 커맨드를 교체한다.
     */
    private fun applyNormalizedText(result: GuardResult.Allowed, command: GuardCommand): GuardCommand {
        val normalizedText = result.hints.firstOrNull {
            it.startsWith("normalized:")
        }?.removePrefix("normalized:") ?: return command
        return command.copy(text = normalizedText)
    }

    /** 감사 이벤트 발행 헬퍼 — auditPublisher가 null이면 아무것도 하지 않는다. */
    private fun publishAudit(
        command: GuardCommand,
        stageName: String,
        result: String,
        reason: String?,
        category: String?,
        stageStartNanos: Long,
        pipelineStartNanos: Long
    ) {
        auditPublisher?.publish(
            command = command,
            stage = stageName,
            result = result,
            reason = reason,
            category = category,
            stageLatencyMs = (System.nanoTime() - stageStartNanos) / 1_000_000,
            pipelineLatencyMs = (System.nanoTime() - pipelineStartNanos) / 1_000_000
        )
    }
}
