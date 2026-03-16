package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 2계층: 규칙 기반 평가기.
 *
 * 인텐트와 응답 내용에 기반한 결정적 규칙을 적용하여 응답 품질을 검사한다.
 *
 * ## 적용 규칙
 * - 검색 인텐트: 답변 최소 길이 확인
 * - 변이 인텐트: 성공 시 확인 메시지 포함 여부
 * - 오류 응답: 제안사항 또는 충분한 설명 포함 여부
 * - 명확화 응답: 질문만으로 구성되지 않았는지 확인
 *
 * WHY: LLM 호출 없이 결정적으로 검증할 수 있는 품질 규칙을 적용한다.
 * 비용이 없고 즉시 실행되므로 LLM 심판 전에 빠르게 걸러낸다.
 *
 * @see EvaluationPipeline 파이프라인에서의 위치 (2계층)
 */
class RuleBasedEvaluator : PromptEvaluator {

    private val objectMapper = jacksonObjectMapper()

    override suspend fun evaluate(
        response: String,
        query: TestQuery
    ): EvaluationResult {
        val json = parseJson(response)
        val intent = query.intent?.lowercase().orEmpty()
        val type = json?.get("type")?.toString().orEmpty()
        val message = json?.get("message")?.toString().orEmpty()
        val success = json?.get("success")?.toString()?.toBooleanStrictOrNull()
        val suggestions = json?.get("suggestions")

        val results = mutableListOf<Boolean>()

        // 각 규칙을 조건부로 적용한다
        checkShortAnswer(intent, type, message, results)
        checkActionConfirmation(intent, success, message, results)
        checkErrorQuality(type, message, suggestions, results)
        checkClarificationOnly(type, message, results)

        // 적용 가능한 규칙이 없으면 합격으로 처리한다
        if (results.isEmpty()) {
            return EvaluationResult(
                tier = EvaluationTier.RULES,
                passed = true,
                score = 1.0,
                reason = "이 응답에 적용 가능한 규칙 없음"
            )
        }

        val passed = results.count { it }
        val score = passed.toDouble() / results.size
        val allPassed = results.all { it }

        logger.debug { "규칙: $passed/${results.size} 통과, 점수=$score" }
        return EvaluationResult(
            tier = EvaluationTier.RULES,
            passed = allPassed,
            score = score,
            reason = "규칙 통과: $passed/${results.size}"
        )
    }

    /** 검색 인텐트의 답변이 최소 길이를 충족하는지 확인한다 */
    private fun checkShortAnswer(
        intent: String,
        type: String,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (!SEARCH_INTENT.containsMatchIn(intent)) return
        if (type != "answer") return
        results.add(message.length > MIN_ANSWER_LENGTH)
    }

    /** 변이 인텐트에서 성공 시 확인 메시지가 포함되었는지 확인한다 */
    private fun checkActionConfirmation(
        intent: String,
        success: Boolean?,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (!MUTATION_INTENT.containsMatchIn(intent)) return
        if (success != true) return
        results.add(CONFIRMATION_PATTERN.containsMatchIn(message))
    }

    /** 오류 응답에 제안사항 또는 충분한 설명이 있는지 확인한다 */
    private fun checkErrorQuality(
        type: String,
        message: String,
        suggestions: Any?,
        results: MutableList<Boolean>
    ) {
        if (type != "error") return
        val hasSuggestions = suggestions is List<*> && suggestions.isNotEmpty()
        results.add(hasSuggestions || message.length > MIN_ERROR_LENGTH)
    }

    /** 명확화 응답이 질문만으로 구성되지 않았는지 확인한다 */
    private fun checkClarificationOnly(
        type: String,
        message: String,
        results: MutableList<Boolean>
    ) {
        if (type != "clarification") return
        results.add(!QUESTION_ONLY.matches(message.trim()))
    }

    /** JSON 파싱 시도. 실패 시 null 반환. */
    private fun parseJson(response: String): Map<*, *>? {
        return try {
            objectMapper.readValue(response.trim(), Map::class.java)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 답변 최소 길이 */
        private const val MIN_ANSWER_LENGTH = 50
        /** 오류 설명 최소 길이 */
        private const val MIN_ERROR_LENGTH = 20

        /** 검색 관련 인텐트 매칭 정규식 */
        private val SEARCH_INTENT = Regex(
            "search|find|lookup|query|retrieve",
            RegexOption.IGNORE_CASE
        )
        /** 변이(생성/수정/삭제) 관련 인텐트 매칭 정규식 */
        private val MUTATION_INTENT = Regex(
            "create|update|delete|remove|add|modify",
            RegexOption.IGNORE_CASE
        )
        /** 작업 완료 확인 메시지 패턴 */
        private val CONFIRMATION_PATTERN = Regex(
            "success|completed|done|created|updated|deleted|removed",
            RegexOption.IGNORE_CASE
        )
        /** 질문만으로 구성된 응답 패턴 */
        private val QUESTION_ONLY = Regex("^[^.!]*\\?$")
    }
}
