package com.arc.reactor.slack.tools.observability

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.MDC
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 도구 호출 관측성(Observability) AOP Aspect.
 *
 * `@Tool` 어노테이션이 붙은 메서드의 실행을 가로채어 다음을 수행한다:
 * - Micrometer 카운터/타이머 기록 (`mcp_tool_invocations_total`, `mcp_tool_latency`)
 * - MDC에 requestId 설정 (구조화 로깅용)
 * - 도구 결과 JSON에서 성공/실패를 파싱하여 에러 코드 추출
 *
 * @param meterRegistry Micrometer 메트릭 레지스트리
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Aspect
class ToolObservabilityAspect(
    private val meterRegistry: MeterRegistry
) {

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    fun observeToolInvocation(joinPoint: ProceedingJoinPoint): Any? {
        val methodSignature = joinPoint.signature as MethodSignature
        val toolName = methodSignature.method.name
        val channelId = extractChannelId(joinPoint.args)

        val existingRequestId = MDC.get(REQUEST_ID_KEY)
        val requestId = existingRequestId ?: UUID.randomUUID().toString()
        val mdcScope = if (existingRequestId == null) MDC.putCloseable(REQUEST_ID_KEY, requestId) else null

        val startedAtNs = System.nanoTime()
        try {
            val result = joinPoint.proceed()
            val outcome = parseResult(result)
            val durationNs = System.nanoTime() - startedAtNs

            recordMetrics(toolName, outcome.status, outcome.errorCode, durationNs)
            logger.info {
                "tool_call requestId=$requestId toolName=$toolName channelId=${channelId ?: "-"} " +
                    "status=${outcome.status} errorCode=${outcome.errorCode ?: "none"} " +
                    "durationMs=${TimeUnit.NANOSECONDS.toMillis(durationNs)}"
            }
            return result
        } catch (e: Exception) {
            val durationNs = System.nanoTime() - startedAtNs
            val errorCode = normalizeExceptionCode(e)
            recordMetrics(toolName, "exception", errorCode, durationNs)
            logger.error(e) {
                "tool_call requestId=$requestId toolName=$toolName channelId=${channelId ?: "-"} " +
                    "status=exception errorCode=$errorCode durationMs=${TimeUnit.NANOSECONDS.toMillis(durationNs)}"
            }
            throw e
        } finally {
            mdcScope?.close()
        }
    }

    private fun recordMetrics(toolName: String, status: String, errorCode: String?, durationNs: Long) {
        meterRegistry.counter(
            "mcp_tool_invocations_total",
            "tool", toolName,
            "outcome", status,
            "error_code", errorCode ?: "none"
        ).increment()
        meterRegistry.timer(
            "mcp_tool_latency",
            "tool", toolName,
            "outcome", status
        ).record(durationNs, TimeUnit.NANOSECONDS)
    }

    private fun parseResult(result: Any?): ToolOutcome {
        if (result !is String) return ToolOutcome(status = "success")
        return try {
            val node = objectMapper.readTree(result)
            val okNode = node.get("ok")
            when {
                okNode?.isBoolean == true && okNode.asBoolean() -> ToolOutcome(status = "success")
                okNode?.isBoolean == true && !okNode.asBoolean() -> ToolOutcome(
                    status = "failure",
                    errorCode = extractErrorCode(node) ?: "unknown_error"
                )
                node.has("error") -> ToolOutcome(
                    status = "failure",
                    errorCode = node.get("error")?.asText()?.takeIf { it.isNotBlank() } ?: "validation_error"
                )
                else -> ToolOutcome(status = "success")
            }
        } catch (_: Exception) {
            ToolOutcome(status = "success")
        }
    }

    private fun extractErrorCode(node: com.fasterxml.jackson.databind.JsonNode): String? {
        val nested = node.path("errorDetails").path("code").asText().takeIf { it.isNotBlank() }
        if (nested != null) return nested
        return node.path("error").asText().takeIf { it.isNotBlank() }
    }

    private fun extractChannelId(args: Array<out Any?>): String? {
        return args
            .asSequence()
            .filterIsInstance<String>()
            .map { it.trim() }
            .firstOrNull { CHANNEL_ID_REGEX.matches(it) }
    }

    private fun normalizeExceptionCode(e: Exception): String {
        val name = e::class.simpleName ?: "exception"
        return name
            .replace(CAMEL_CASE_REGEX, "$1_$2")
            .lowercase(Locale.ROOT)
    }

    private data class ToolOutcome(
        val status: String,
        val errorCode: String? = null
    )

    private companion object {
        private val logger = KotlinLogging.logger {}
        private val objectMapper = jacksonObjectMapper()
        private val CHANNEL_ID_REGEX = Regex("^[CGD][A-Z0-9]{8,}$")
        private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")
        private const val REQUEST_ID_KEY = "requestId"
    }
}
