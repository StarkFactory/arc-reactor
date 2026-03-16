package com.arc.reactor.guard.output

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 출력 Guard 파이프라인
 *
 * [OutputGuardStage] 목록을 [order] 오름차순으로 실행하여 LLM 응답 콘텐츠를 검증한다.
 *
 * ## 오류 처리 정책: Fail-Close
 * 단계에서 예외가 발생하면 [OutputRejectionCategory.SYSTEM_ERROR]로 응답을 차단한다.
 * 왜 fail-close인가: 입력 Guard 파이프라인과 동일한 이유 — 보안 컴포넌트에서
 * 불확실한 상황은 차단이 허용보다 안전하다.
 *
 * ## 실행 흐름
 * 각 단계에 대해:
 * - [OutputGuardResult.Allowed] → 다음 단계로 계속
 * - [OutputGuardResult.Modified] → 콘텐츠를 업데이트하고 다음 단계에서 수정된 콘텐츠 사용
 * - [OutputGuardResult.Rejected] → 즉시 중단하여 거부 결과 반환
 * - 예외 → SYSTEM_ERROR로 거부 (fail-close)
 *
 * 모든 단계를 통과하면 마지막 Modified 결과(있으면) 또는 Allowed를 반환한다.
 *
 * @param stages 출력 Guard 단계 목록 (비활성화된 단계는 자동 필터링)
 * @param onStageComplete 단계 완료 콜백 (모니터링/메트릭용, 선택사항)
 *
 * @see OutputGuardStage 개별 출력 Guard 단계 인터페이스
 */
class OutputGuardPipeline(
    stages: List<OutputGuardStage>,
    private val onStageComplete: ((stage: String, action: String, reason: String) -> Unit)? = null
) {

    /** 활성화된 단계만 order 기준 정렬하여 보관 */
    private val sorted: List<OutputGuardStage> = stages
        .filter { it.enabled }
        .sortedBy { it.order }

    /**
     * 모든 출력 Guard 단계를 순서대로 실행한다.
     *
     * @param content LLM 응답 콘텐츠
     * @param context 요청 및 실행 메타데이터
     * @return 모든 단계 통과 후 최종 Guard 결과
     */
    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
        if (sorted.isEmpty()) return OutputGuardResult.Allowed.DEFAULT

        var currentContent = content
        var lastModified: OutputGuardResult.Modified? = null

        for (stage in sorted) {
            val result = try {
                stage.check(currentContent, context)
            } catch (e: Exception) {
                // CancellationException은 반드시 먼저 처리하여 재던진다
                e.throwIfCancellation()
                // Fail-Close: 예외 발생 시 응답 차단
                logger.error(e) { "OutputGuardStage '${stage.stageName}' failed, rejecting (fail-close)" }
                return OutputGuardResult.Rejected(
                    reason = "Output guard check failed: ${stage.stageName}",
                    category = OutputRejectionCategory.SYSTEM_ERROR,
                    stage = stage.stageName
                )
            }

            when (result) {
                is OutputGuardResult.Allowed -> {
                    // 통과 — 다음 단계로 계속
                    onStageComplete?.invoke(stage.stageName, "allowed", "")
                    continue
                }
                is OutputGuardResult.Modified -> {
                    // 수정됨 — 후속 단계에서 수정된 콘텐츠를 사용
                    logger.info { "OutputGuardStage '${stage.stageName}' modified content: ${result.reason}" }
                    onStageComplete?.invoke(stage.stageName, "modified", result.reason)
                    currentContent = result.content
                    lastModified = result.copy(stage = stage.stageName)
                }
                is OutputGuardResult.Rejected -> {
                    // 거부 — 파이프라인 즉시 중단
                    logger.warn { "OutputGuardStage '${stage.stageName}' rejected: ${result.reason}" }
                    onStageComplete?.invoke(stage.stageName, "rejected", result.reason)
                    return result.copy(stage = stage.stageName)
                }
            }
        }

        // 모든 단계 통과: 마지막 Modified가 있으면 반환, 아니면 Allowed
        return lastModified ?: OutputGuardResult.Allowed.DEFAULT
    }

    /** 파이프라인의 활성 단계 수 */
    val size: Int get() = sorted.size
}
