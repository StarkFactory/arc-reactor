package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 1계층: 구조 평가기.
 *
 * 응답이 필수 필드를 포함한 유효한 JSON 구조인지 검증한다.
 * 일반 텍스트 응답은 호환성 점수를 받는다.
 *
 * ## 검증 규칙
 * - JSON 파싱 가능 여부
 * - `type` 필드 존재 및 유효값 여부
 * - type별 필수 콘텐츠 필드 존재 여부 (briefing -> summary, 그 외 -> message)
 *
 * WHY: 에이전트 응답의 기본적인 형식 요구사항을 비용 없이 즉시 검증한다.
 * 구조가 올바르지 않으면 후속 평가(규칙, LLM)가 의미 없으므로
 * fail-fast로 조기 중단한다.
 *
 * @see EvaluationPipeline 파이프라인에서의 위치 (1계층)
 */
class StructuralEvaluator : PromptEvaluator {

    private val objectMapper = jacksonObjectMapper()

    override suspend fun evaluate(
        response: String,
        query: TestQuery
    ): EvaluationResult {
        // JSON 파싱 시도 — 실패하면 일반 텍스트로 처리
        val json = parseJson(response)
            ?: return plainTextResult()

        val type = json["type"]?.toString()
        val hasMessage = json.containsKey("message")
        val hasSummary = json.containsKey("summary")

        // type 필드 검증
        if (type == null || type !in VALID_TYPES) {
            logger.debug { "유효하지 않거나 누락된 type: $type" }
            return missingFieldResult("'type' 필드 누락 또는 유효하지 않음")
        }

        // type별 필수 콘텐츠 필드 검증
        val hasContent = if (type == "briefing") hasSummary else hasMessage
        if (!hasContent) {
            val field = if (type == "briefing") "summary" else "message"
            logger.debug { "type=$type 에 '$field' 누락" }
            return missingFieldResult("type=$type 에 '$field' 필드 누락")
        }

        return EvaluationResult(
            tier = EvaluationTier.STRUCTURAL,
            passed = true,
            score = 1.0,
            reason = "유효한 JSON: type=$type, 필수 필드 포함"
        )
    }

    /** JSON 파싱 시도. 실패 시 null 반환. */
    private fun parseJson(response: String): Map<*, *>? {
        return try {
            objectMapper.readValue(response.trim(), Map::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /** 일반 텍스트 응답 결과 — 합격이지만 점수 0.5 (JSON이 아님) */
    private fun plainTextResult() = EvaluationResult(
        tier = EvaluationTier.STRUCTURAL,
        passed = true,
        score = 0.5,
        reason = "일반 텍스트 응답 (비-JSON)"
    )

    /** 필수 필드 누락 결과 — 불합격, 점수 0.3 */
    private fun missingFieldResult(reason: String) = EvaluationResult(
        tier = EvaluationTier.STRUCTURAL,
        passed = false,
        score = 0.3,
        reason = reason
    )

    companion object {
        /** 허용되는 응답 type 값 */
        private val VALID_TYPES = setOf(
            "answer", "error", "action",
            "briefing", "clarification", "search"
        )
    }
}
