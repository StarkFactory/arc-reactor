package com.arc.reactor.guard.impl

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
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer()
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
            "Guard stages: [$desc]"
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
                    "Duplicate guard stage order=$order: [$names]. " +
                        "Execution order among these stages is undefined."
                }
            }
    }

    override suspend fun guard(command: GuardCommand): GuardResult {
        // ── 단계 1: 빈 파이프라인 처리 ──
        // Guard 단계가 하나도 없으면 모든 요청을 허용한다
        if (sortedStages.isEmpty()) {
            logger.debug { "No guard stages enabled, allowing request" }
            return GuardResult.Allowed.DEFAULT
        }

        val span = tracer.startSpan(
            "arc.guard.input",
            mapOf("guard.stage.count" to sortedStages.size.toString())
        )
        val pipelineStartNanos = System.nanoTime()
        var currentCommand = command

        try {
            // ── 단계 2: 각 Guard 단계를 순서대로 실행 ──
            for (stage in sortedStages) {
                val stageStartNanos = System.nanoTime()
                try {
                    logger.debug { "Executing guard stage: ${stage.stageName}" }
                    val result = stage.enforce(currentCommand)

                    when (result) {
                        is GuardResult.Allowed -> {
                            logger.debug { "Stage ${stage.stageName} passed" }
                            currentCommand = applyNormalizedText(result, currentCommand)
                            publishAudit(
                                currentCommand, stage.stageName, "allowed", null, null,
                                stageStartNanos, pipelineStartNanos
                            )
                            continue
                        }
                        is GuardResult.Rejected -> {
                            // ── 거부: 파이프라인 즉시 중단 ──
                            logger.warn { "Stage ${stage.stageName} rejected: ${result.reason}" }
                            publishAudit(
                                currentCommand, stage.stageName, "rejected",
                                result.reason, result.category.name,
                                stageStartNanos, pipelineStartNanos
                            )
                            span.setAttribute("guard.result", "rejected")
                            span.setAttribute("guard.stage", stage.stageName)
                            span.setAttribute("guard.reason", result.reason)
                            return result.copy(stage = stage.stageName)
                        }
                    }
                } catch (e: Exception) {
                    // ── CancellationException은 반드시 먼저 처리하여 재던진다 ──
                    e.throwIfCancellation()

                    // ── Fail-Close: 예외 발생 시 요청 거부 ──
                    logger.error(e) { "Guard stage ${stage.stageName} failed" }
                    publishAudit(
                        currentCommand, stage.stageName, "error", e.message, null,
                        stageStartNanos, pipelineStartNanos
                    )
                    span.setAttribute("guard.result", "error")
                    span.setAttribute("guard.stage", stage.stageName)
                    span.setError(e)
                    return GuardResult.Rejected(
                        reason = "Security check failed",
                        category = RejectionCategory.SYSTEM_ERROR,
                        stage = stage.stageName
                    )
                }
            }

            // ── 단계 3: 모든 단계 통과 — 요청 허용 ──
            publishAudit(
                currentCommand, "pipeline", "allowed", null, null,
                pipelineStartNanos, pipelineStartNanos
            )
            span.setAttribute("guard.result", "allowed")
            return GuardResult.Allowed.DEFAULT
        } finally {
            span.close()
        }
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
