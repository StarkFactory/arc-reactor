package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

private val objectMapper = jacksonObjectMapper()

/**
 * LLM 응답이 요청된 구조화 형식(JSON/YAML)에 맞는지 검증하는 validator.
 *
 * LLM이 마크다운 코드 펜스(` ```json ... ``` `)로 감싼 경우 이를 제거하고,
 * 실제 콘텐츠가 유효한 JSON 또는 YAML인지 파싱을 통해 검증한다.
 *
 * @see StructuredResponseRepairer 검증 실패 시 LLM을 통한 복구 시도
 * @see ResponseFormat 응답 형식 열거형 (TEXT, JSON, YAML)
 */
internal class StructuredOutputValidator {

    /**
     * 콘텐츠가 지정된 형식에 맞는지 검증한다.
     *
     * @param content 검증할 콘텐츠
     * @param format 기대하는 응답 형식
     * @return 유효하면 true, TEXT 형식은 항상 true
     */
    fun isValidFormat(content: String, format: ResponseFormat): Boolean {
        return when (format) {
            ResponseFormat.JSON -> validateJson(content)
            ResponseFormat.YAML -> validateYaml(content)
            ResponseFormat.TEXT -> true
        }
    }

    /**
     * 마크다운 코드 펜스(` ```json ... ``` `)를 제거한다.
     *
     * LLM이 JSON/YAML 응답을 코드 펜스로 감싸는 경우가 흔하므로,
     * 검증 전에 이를 벗겨낸다.
     *
     * @param content 원본 콘텐츠
     * @return 코드 펜스가 제거된 콘텐츠
     */
    fun stripMarkdownCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        val startIdx = 1 // 첫 ``` 줄 건너뜀
        val endIdx = if (lines.last().trim() == "```") lines.size - 1 else lines.size
        return lines.subList(startIdx, endIdx).joinToString("\n").trim()
    }

    /** Jackson ObjectMapper를 사용하여 JSON 유효성을 검증한다. */
    private fun validateJson(content: String): Boolean {
        return try {
            objectMapper.readTree(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * SnakeYAML의 SafeConstructor를 사용하여 YAML 유효성을 검증한다.
     *
     * R326 fix: 기존 구현은 `result != null`만 검사하여 bare scalar("hello", 42, true 등)도
     * YAML로 유효하다고 통과시켰다. 결과적으로 에이전트가 YAML 구조를 기대하는 caller에게
     * **LLM이 생성한 평문**이 silent pass되어 스키마 제약이 vacuously 만족되는 버그. 구조화
     * 출력 계약을 엄격히 적용하기 위해 `Map` 또는 `List`만 허용한다. 단일 값만 필요한 경우에는
     * `ResponseFormat.TEXT`를 사용해야 한다.
     */
    private fun validateYaml(content: String): Boolean {
        return try {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val result = yaml.load<Any>(content)
            // bare scalar(String/Int/Boolean 등)는 구조화 출력이 아니므로 거부
            result is Map<*, *> || result is List<*>
        } catch (e: Exception) {
            false
        }
    }
}
