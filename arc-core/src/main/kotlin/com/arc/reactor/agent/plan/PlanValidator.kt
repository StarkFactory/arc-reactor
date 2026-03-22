package com.arc.reactor.agent.plan

import com.arc.reactor.approval.ToolApprovalPolicy
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 계획-실행 모드에서 LLM이 생성한 계획의 유효성을 검증하는 인터페이스.
 *
 * 계획의 각 단계가 실행 가능한지 사전 검증하여 불필요한 도구 호출을 방지한다.
 * - 도구 이름이 등록된 도구 목록에 존재하는지 확인
 * - 도구가 현재 컨텍스트에서 승인이 필요한지 확인
 *
 * @see DefaultPlanValidator 기본 구현체
 * @see PlanValidationResult 검증 결과
 */
interface PlanValidator {

    /**
     * 계획의 유효성을 검증한다.
     *
     * @param steps LLM이 생성한 계획 단계 목록
     * @param availableToolNames 사용 가능한 도구 이름 집합
     * @param toolApprovalPolicy 도구 승인 정책 (null이면 승인 검사 생략)
     * @return 검증 결과
     */
    suspend fun validate(
        steps: List<PlanStep>,
        availableToolNames: Set<String>,
        toolApprovalPolicy: ToolApprovalPolicy? = null
    ): PlanValidationResult
}

/**
 * 계획 검증 결과.
 *
 * @property valid 계획이 유효한지 여부
 * @property steps 검증을 통과한 원본 계획 단계 목록
 * @property errors 검증 실패 사유 목록
 */
data class PlanValidationResult(
    val valid: Boolean,
    val steps: List<PlanStep>,
    val errors: List<String>
) {
    companion object {
        /** 성공 결과를 생성한다. */
        fun success(steps: List<PlanStep>) = PlanValidationResult(
            valid = true,
            steps = steps,
            errors = emptyList()
        )

        /** 실패 결과를 생성한다. */
        fun failure(steps: List<PlanStep>, errors: List<String>) = PlanValidationResult(
            valid = false,
            steps = steps,
            errors = errors
        )
    }
}

/**
 * [PlanValidator]의 기본 구현체.
 *
 * 각 계획 단계에 대해 다음을 검증한다:
 * 1. 도구 이름이 비어 있지 않은지 확인
 * 2. 도구 이름이 등록된 도구 목록에 존재하는지 확인
 * 3. 도구가 승인이 필요한 경우 사전 경고 (에러는 아님, 실행 시점에 차단)
 *
 * 하나라도 유효하지 않은 단계가 있으면 전체 계획을 무효화한다.
 */
class DefaultPlanValidator : PlanValidator {

    override suspend fun validate(
        steps: List<PlanStep>,
        availableToolNames: Set<String>,
        toolApprovalPolicy: ToolApprovalPolicy?
    ): PlanValidationResult {
        if (steps.isEmpty()) {
            return PlanValidationResult.failure(
                steps, listOf("계획이 비어 있습니다.")
            )
        }
        val errors = mutableListOf<String>()
        for ((index, step) in steps.withIndex()) {
            validateStep(index, step, availableToolNames, toolApprovalPolicy, errors)
        }
        return if (errors.isEmpty()) {
            logger.debug { "계획 검증 성공: ${steps.size}개 단계" }
            PlanValidationResult.success(steps)
        } else {
            logger.warn { "계획 검증 실패: ${errors.size}개 오류" }
            PlanValidationResult.failure(steps, errors)
        }
    }

    /** 단일 계획 단계의 유효성을 검증한다. */
    private fun validateStep(
        index: Int,
        step: PlanStep,
        availableToolNames: Set<String>,
        toolApprovalPolicy: ToolApprovalPolicy?,
        errors: MutableList<String>
    ) {
        val stepNum = index + 1
        if (step.tool.isBlank()) {
            errors.add("단계 $stepNum: 도구 이름이 비어 있습니다.")
            return
        }
        if (step.tool !in availableToolNames) {
            errors.add(
                "단계 $stepNum: 도구 '${step.tool}'이(가) " +
                    "등록된 도구 목록에 존재하지 않습니다."
            )
            return
        }
        if (toolApprovalPolicy?.requiresApproval(step.tool, step.args) == true) {
            logger.info {
                "단계 $stepNum: 도구 '${step.tool}'은(는) 승인이 필요합니다."
            }
        }
    }
}
