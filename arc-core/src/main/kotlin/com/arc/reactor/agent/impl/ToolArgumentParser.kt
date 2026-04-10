package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

/**
 * LLM이 생성한 도구 호출 인자(JSON 문자열)를 `Map<String, Any?>`로 파싱하는 유틸리티.
 *
 * ReAct 루프에서 LLM이 tool call을 요청할 때 인자를 JSON 문자열로 제공하는데,
 * 이를 안전하게 파싱하여 도구 실행에 전달한다. 파싱 실패 시 빈 맵을 반환한다.
 *
 * @see com.arc.reactor.agent.impl.ToolCallOrchestrator 도구 호출 시 인자 파싱에 사용
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor ReAct 루프에서 도구 호출 처리
 */

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()
private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

/**
 * JSON 문자열을 도구 인자 맵으로 파싱한다.
 *
 * R254: [evaluationMetricsCollector]가 주입되면 파싱 실패 시 `execution.error{stage="parsing"}`
 * 메트릭에 자동 기록한다. 기본값 NoOp으로 backward compat 유지. 파싱은 fail-open으로
 * 빈 맵을 반환하므로 이 메트릭 없이는 LLM이 유효하지 않은 JSON을 자주 생성해도 관측이 어렵다.
 *
 * @param json LLM이 생성한 도구 호출 인자 JSON 문자열
 * @param evaluationMetricsCollector R254: 파싱 실패 자동 기록용 수집기 (기본 NoOp)
 * @return 파싱된 인자 맵. null/빈 문자열이거나 파싱 실패 시 빈 맵 반환
 */
internal fun parseToolArguments(
    json: String?,
    evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector
): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        objectMapper.readValue(json, mapTypeRef)
    } catch (e: Exception) {
        // R254: PARSING stage로 JSON 인자 파싱 실패 기록 (fail-open 빈 맵 반환 전)
        evaluationMetricsCollector.recordError(ExecutionStage.PARSING, e)
        logger.warn(e) { "JSON 인자 파싱 실패" }
        emptyMap()
    }
}
