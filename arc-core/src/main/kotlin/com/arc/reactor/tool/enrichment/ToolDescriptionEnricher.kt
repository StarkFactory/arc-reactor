package com.arc.reactor.tool.enrichment

import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 도구 설명 품질 분석 인터페이스.
 *
 * "Learning to Rewrite Tool Descriptions for Reliable LLM-Agent Tool Use"
 * (arXiv:2602.20426) 논문에서 영감을 받아, 도구 설명의 품질을 정적으로 분석한다.
 *
 * LLM이 도구를 정확히 선택하고 호출하려면 명확한 설명이 필수적이다.
 * 이 인터페이스는 도구의 name, description, inputSchema를 분석하여
 * 품질 점수와 개선 권장 사항을 제공한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val enricher: ToolDescriptionEnricher = DefaultToolDescriptionEnricher()
 * val quality = enricher.analyze(myTool)
 * if (quality.score < 0.5) {
 *     println("도구 '${quality.toolName}'의 설명 품질이 낮습니다: ${quality.warnings}")
 * }
 * ```
 *
 * @see ToolDescriptionQuality 분석 결과
 * @see DefaultToolDescriptionEnricher 기본 구현
 */
interface ToolDescriptionEnricher {

    /**
     * 도구의 설명 품질을 분석한다.
     *
     * @param tool 분석할 도구
     * @return 품질 점수 및 경고 목록을 포함하는 분석 결과
     */
    fun analyze(tool: ToolCallback): ToolDescriptionQuality
}

/**
 * 도구 설명 품질 분석 결과.
 *
 * @param toolName 분석된 도구의 이름
 * @param score 품질 점수 (0.0 ~ 1.0). 높을수록 좋음
 * @param warnings 개선 권장 사항 목록
 * @param hasInputSchema 입력 스키마 존재 여부 (빈 객체가 아닌 실제 파라미터 정의)
 * @param descriptionLength 도구 설명의 문자 수
 */
data class ToolDescriptionQuality(
    val toolName: String,
    val score: Double,
    val warnings: List<String>,
    val hasInputSchema: Boolean,
    val descriptionLength: Int
)

/**
 * 도구 설명 품질 분석기 기본 구현.
 *
 * 다음 기준으로 도구 설명 품질을 평가한다:
 * - **설명 존재 여부**: 설명이 비어있으면 0점
 * - **설명 길이**: 최소 [minDescriptionLength]자 이상 권장
 * - **설명 충분성**: [goodDescriptionLength]자 이상이면 만점
 * - **입력 스키마**: 파라미터 정의가 있으면 가산점
 * - **파라미터 설명**: 각 파라미터에 description 필드가 있는지 확인
 * - **도구 이름**: snake_case 또는 camelCase 네이밍 준수 여부
 *
 * @param minDescriptionLength 설명이 짧다고 경고할 최소 길이 (기본: 20)
 * @param goodDescriptionLength 충분한 설명으로 간주할 길이 (기본: 80)
 */
class DefaultToolDescriptionEnricher(
    private val minDescriptionLength: Int = MIN_DESCRIPTION_LENGTH,
    private val goodDescriptionLength: Int = GOOD_DESCRIPTION_LENGTH
) : ToolDescriptionEnricher {

    override fun analyze(tool: ToolCallback): ToolDescriptionQuality {
        val warnings = mutableListOf<String>()
        var score = 0.0

        val description = tool.description.trim()
        val descriptionLength = description.length

        // 1. 설명 존재 여부 (0.3점)
        if (description.isEmpty()) {
            warnings.add("도구 설명이 비어있습니다. LLM이 도구의 목적을 파악할 수 없습니다.")
        } else {
            score += WEIGHT_DESCRIPTION_EXISTS
        }

        // 2. 설명 길이 적정성 (0.2점)
        if (description.isNotEmpty() && descriptionLength < minDescriptionLength) {
            warnings.add(
                "도구 설명이 짧습니다 (${descriptionLength}자). " +
                    "최소 ${minDescriptionLength}자 이상을 권장합니다."
            )
        } else if (descriptionLength >= goodDescriptionLength) {
            score += WEIGHT_DESCRIPTION_LENGTH
        } else if (descriptionLength >= minDescriptionLength) {
            val ratio = (descriptionLength - minDescriptionLength).toDouble() /
                (goodDescriptionLength - minDescriptionLength)
            score += WEIGHT_DESCRIPTION_LENGTH * ratio
        }

        // 3. 입력 스키마 존재 여부 (0.2점)
        val hasInputSchema = hasRealInputSchema(tool.inputSchema)
        if (hasInputSchema) {
            score += WEIGHT_INPUT_SCHEMA
        } else {
            warnings.add("입력 스키마에 파라미터가 정의되지 않았습니다.")
        }

        // 4. 파라미터 설명 완전성 (0.2점)
        if (hasInputSchema) {
            val missingDescriptions = findMissingParameterDescriptions(tool.inputSchema)
            if (missingDescriptions.isEmpty()) {
                score += WEIGHT_PARAM_DESCRIPTIONS
            } else {
                val paramWarning = "다음 파라미터에 description이 누락되었습니다: ${missingDescriptions.joinToString(", ")}"
                warnings.add(paramWarning)
                // 일부 파라미터에 설명이 있으면 부분 점수
                val totalParams = countProperties(tool.inputSchema)
                if (totalParams > 0) {
                    val describedRatio = (totalParams - missingDescriptions.size).toDouble() / totalParams
                    score += WEIGHT_PARAM_DESCRIPTIONS * describedRatio
                }
            }
        }

        // 5. 도구 이름 규칙 준수 (0.1점)
        if (isValidToolName(tool.name)) {
            score += WEIGHT_TOOL_NAME
        } else {
            warnings.add(
                "도구 이름 '${tool.name}'이 snake_case 또는 camelCase 규칙을 따르지 않습니다."
            )
        }

        val clampedScore = score.coerceIn(0.0, 1.0)

        if (warnings.isNotEmpty()) {
            logger.warn { "도구 '${tool.name}' 설명 품질 점수: ${"%.2f".format(clampedScore)} — 경고: $warnings" }
        } else {
            logger.debug { "도구 '${tool.name}' 설명 품질 점수: ${"%.2f".format(clampedScore)}" }
        }

        return ToolDescriptionQuality(
            toolName = tool.name,
            score = clampedScore,
            warnings = warnings,
            hasInputSchema = hasInputSchema,
            descriptionLength = descriptionLength
        )
    }

    /**
     * 입력 스키마에 실제 파라미터가 정의되어 있는지 확인한다.
     * `{"type":"object","properties":{}}` 같은 빈 스키마는 false를 반환한다.
     */
    private fun hasRealInputSchema(inputSchema: String): Boolean {
        val trimmed = inputSchema.trim()
        if (trimmed.isEmpty()) return false
        // "properties":{} 패턴이면 빈 스키마
        if (EMPTY_PROPERTIES_REGEX.containsMatchIn(trimmed)) return false
        // "properties" 키가 존재하고 내부에 무언가 있으면 true
        return trimmed.contains("\"properties\"") && !trimmed.contains("\"properties\":{}")
    }

    /**
     * 입력 스키마에서 description 필드가 누락된 파라미터 이름을 찾는다.
     * JSON 파싱 없이 정규식으로 간단하게 탐지한다.
     */
    private fun findMissingParameterDescriptions(inputSchema: String): List<String> {
        val missing = mutableListOf<String>()
        val propertyNames = PROPERTY_NAME_REGEX.findAll(inputSchema)

        for (match in propertyNames) {
            val paramName = match.groupValues[1]
            // 해당 파라미터 블록에서 "description" 키가 있는지 간단히 확인
            val paramStart = match.range.last
            val nextPropertyOrEnd = findNextPropertyBoundary(inputSchema, paramStart)
            val paramBlock = inputSchema.substring(paramStart, nextPropertyOrEnd)
            if (!paramBlock.contains("\"description\"")) {
                missing.add(paramName)
            }
        }

        return missing
    }

    /**
     * 입력 스키마에서 properties 안의 전체 파라미터 수를 센다.
     */
    private fun countProperties(inputSchema: String): Int =
        PROPERTY_NAME_REGEX.findAll(inputSchema).count()

    /**
     * 현재 위치 이후의 다음 프로퍼티 경계 또는 스키마 끝 위치를 찾는다.
     */
    private fun findNextPropertyBoundary(schema: String, fromIndex: Int): Int {
        // 다음 최상위 프로퍼티를 간단히 찾기: 중괄호 깊이가 0으로 돌아오는 지점
        var depth = 0
        for (i in fromIndex until schema.length) {
            when (schema[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth <= 0) return (i + 1).coerceAtMost(schema.length)
                }
            }
        }
        return schema.length
    }

    /**
     * 도구 이름이 snake_case 또는 camelCase 규칙을 따르는지 확인한다.
     */
    private fun isValidToolName(name: String): Boolean {
        if (name.isEmpty()) return false
        return SNAKE_CASE_REGEX.matches(name) || CAMEL_CASE_REGEX.matches(name)
    }

    companion object {
        /** 설명이 짧다고 경고할 최소 글자 수 */
        const val MIN_DESCRIPTION_LENGTH = 20

        /** 충분한 설명으로 간주할 글자 수 */
        const val GOOD_DESCRIPTION_LENGTH = 80

        // 점수 가중치
        private const val WEIGHT_DESCRIPTION_EXISTS = 0.3
        private const val WEIGHT_DESCRIPTION_LENGTH = 0.2
        private const val WEIGHT_INPUT_SCHEMA = 0.2
        private const val WEIGHT_PARAM_DESCRIPTIONS = 0.2
        private const val WEIGHT_TOOL_NAME = 0.1

        // 정규식 — companion object에 추출하여 hot path에서 재컴파일 방지
        private val EMPTY_PROPERTIES_REGEX = Regex(""""properties"\s*:\s*\{\s*\}""")
        private val PROPERTY_NAME_REGEX = Regex(""""(\w+)"\s*:\s*\{""")
        private val SNAKE_CASE_REGEX = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        private val CAMEL_CASE_REGEX = Regex("^[a-z][a-zA-Z0-9]*$")
    }
}
