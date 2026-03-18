package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.support.formatBoundaryViolation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 비-스트리밍 모드에서 LLM 응답의 출력 길이가 경계값(최대/최소)을 준수하는지 검사하고 정책을 적용한다.
 *
 * 적용 정책:
 * - **최대 길이 초과**: 항상 truncate (잘라내기 + "[Response truncated]" 추가)
 * - **최소 길이 미달**:
 *   - WARN: 경고 로그만 기록하고 그대로 반환
 *   - RETRY_ONCE: [attemptLongerResponse]로 더 긴 응답 재시도, 실패 시 그대로 반환
 *   - FAIL: null 반환 (호출자가 OUTPUT_TOO_SHORT 에러로 처리)
 *
 * @see StreamingCompletionFinalizer 스트리밍 모드의 경계값 검사 (대응 역할)
 * @see com.arc.reactor.agent.config.BoundaryProperties 경계값 설정
 * @see com.arc.reactor.agent.config.OutputMinViolationMode 최소 길이 위반 모드
 */
internal class OutputBoundaryEnforcer(
    private val boundaries: BoundaryProperties,
    private val agentMetrics: AgentMetrics
) {

    companion object {
        /** 출력이 최소 길이보다 짧을 때의 위반 유형명 */
        const val VIOLATION_OUTPUT_TOO_SHORT = "output_too_short"
        /** 출력이 최대 길이를 초과할 때의 위반 유형명 */
        const val VIOLATION_OUTPUT_TOO_LONG = "output_too_long"
        /** 최대 길이 초과로 잘린 응답에 추가되는 접미사 */
        const val TRUNCATION_SUFFIX = "\n\n[Response truncated]"
    }

    /**
     * 출력 경계값 정책을 적용한다.
     *
     * @param result 원본 에이전트 결과
     * @param command 에이전트 명령
     * @param metadata 메트릭 기록용 메타데이터
     * @param attemptLongerResponse RETRY_ONCE 모드에서 더 긴 응답을 시도하는 함수
     * @return 정책이 적용된 결과. FAIL 모드에서 최소 미달 시 null
     */
    suspend fun enforceOutputBoundaries(
        result: AgentResult,
        command: AgentCommand,
        metadata: Map<String, Any>,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult? {
        val content = result.content ?: return result
        val len = content.length

        // ── 단계 1: 최대 길이 초과 시 truncate ──
        val afterMax = if (boundaries.outputMaxChars > 0 && len > boundaries.outputMaxChars) {
            val policy = "truncate"
            agentMetrics.recordBoundaryViolation(
                VIOLATION_OUTPUT_TOO_LONG, policy, boundaries.outputMaxChars, len, metadata
            )
            logger.info {
                formatBoundaryViolation(VIOLATION_OUTPUT_TOO_LONG, policy, boundaries.outputMaxChars, len)
            }
            result.copy(content = content.take(boundaries.outputMaxChars) + TRUNCATION_SUFFIX)
        } else {
            result
        }

        // ── 단계 2: 최소 길이 미달 시 위반 모드에 따른 정책 적용 ──
        val effectiveContent = afterMax.content ?: return afterMax
        if (boundaries.outputMinChars <= 0 || effectiveContent.length >= boundaries.outputMinChars) {
            return afterMax
        }

        return when (boundaries.outputMinViolationMode) {
            OutputMinViolationMode.WARN -> {
                recordMinViolation(OutputMinViolationMode.WARN, effectiveContent.length, metadata)
                afterMax
            }

            OutputMinViolationMode.RETRY_ONCE -> {
                recordMinViolation(OutputMinViolationMode.RETRY_ONCE, effectiveContent.length, metadata)
                val retried = attemptLongerResponse(effectiveContent, boundaries.outputMinChars, command)
                if (retried != null && retried.length >= boundaries.outputMinChars) {
                    afterMax.copy(content = retried)
                } else {
                    logger.warn {
                        "Boundary retry result: $VIOLATION_OUTPUT_TOO_SHORT still below limit " +
                            "(actual=${retried?.length ?: 0}, limit=${boundaries.outputMinChars})"
                    }
                    afterMax
                }
            }

            OutputMinViolationMode.FAIL -> {
                recordMinViolation(OutputMinViolationMode.FAIL, effectiveContent.length, metadata)
                null
            }
        }
    }

    /** 최소 길이 위반을 메트릭과 로그에 기록한다. */
    private fun recordMinViolation(
        mode: OutputMinViolationMode,
        contentLength: Int,
        metadata: Map<String, Any>
    ) {
        val policy = mode.name.lowercase()
        agentMetrics.recordBoundaryViolation(
            VIOLATION_OUTPUT_TOO_SHORT, policy, boundaries.outputMinChars, contentLength, metadata
        )
        val msg = formatBoundaryViolation(VIOLATION_OUTPUT_TOO_SHORT, policy, boundaries.outputMinChars, contentLength)
        if (mode == OutputMinViolationMode.RETRY_ONCE) logger.info { msg } else logger.warn { msg }
    }
}
