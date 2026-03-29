package com.arc.reactor.tool

import com.arc.reactor.support.throwIfCancellation

/**
 * 도구 콜백 인터페이스
 *
 * 프레임워크 비의존적 도구 실행 추상화.
 * Spring AI의 ToolCallback에 대한 독립성을 유지하면서 호환성을 제공한다.
 *
 * ## 목적
 * - 도구 구현을 Spring AI 구체 사항에서 분리
 * - Spring AI 의존성 없이 테스트 가능
 * - 로컬 도구와 MCP(Model Context Protocol) 도구 모두 지원
 *
 * ## 구현 예시
 * ```kotlin
 * class WeatherTool : ToolCallback {
 *     override val name = "get_weather"
 *     override val description = "Get current weather for a location"
 *
 *     override suspend fun call(arguments: Map<String, Any?>): Any? {
 *         val location = arguments["location"] as? String
 *             ?: return "Error: location required"
 *         return weatherService.getWeather(location)
 *     }
 * }
 * ```
 *
 * @see SpringAiToolCallbackAdapter Spring AI 도구 래핑용 어댑터
 * @see ToolDefinition 도구 메타데이터
 */
interface ToolCallback {
    /** LLM이 이 도구를 호출할 때 사용하는 고유 식별자 */
    val name: String

    /** 이 도구의 기능을 설명하는 문자열 (LLM에게 노출됨) */
    val description: String

    /**
     * 도구의 입력 파라미터를 설명하는 JSON Schema.
     * LLM이 올바른 도구 호출 인자를 생성하는 데 사용된다.
     *
     * 파라미터를 지정하려면 오버라이드한다:
     * ```kotlin
     * override val inputSchema: String get() = """
     *   {"type":"object","properties":{"location":{"type":"string","description":"City name"}},"required":["location"]}
     * """
     * ```
     *
     * 기본값: 빈 객체 (파라미터 없음).
     */
    val inputSchema: String
        get() = """{"type":"object","properties":{}}"""

    /**
     * 도구별 타임아웃 (밀리초). 설정하면 전역 `arc.reactor.concurrency.tool-call-timeout-ms`를
     * 이 도구에 한해 오버라이드한다. null이면 전역 기본값을 사용한다.
     */
    val timeoutMs: Long?
        get() = null

    /**
     * 주어진 인자로 도구를 실행한다.
     *
     * @param arguments 도구 파라미터 키-값 쌍 (LLM의 JSON에서 파싱됨)
     * @return 도구 실행 결과 (LLM을 위해 문자열로 변환됨)
     */
    suspend fun call(arguments: Map<String, Any?>): Any?
}

/**
 * Spring AI ToolCallback 어댑터
 *
 * Spring AI의 ToolCallback을 래핑하여 프레임워크 비의존적 접근을 제공한다.
 * 리플렉션을 사용하여 Spring AI 클래스와의 느슨한 결합을 유지한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val springTool: org.springframework.ai.tool.ToolCallback = ...
 * val arcTool = SpringAiToolCallbackAdapter(springTool)
 *
 * // Arc Reactor 인터페이스를 통해 사용
 * val result = arcTool.call(mapOf("query" to "test"))
 * ```
 *
 * @param springAiCallback 래핑할 Spring AI ToolCallback 인스턴스
 */
class SpringAiToolCallbackAdapter(
    private val springAiCallback: Any  // org.springframework.ai.tool.ToolCallback
) : ToolCallback {
    private val callMethod by lazy {
        runCatching { springAiCallback::class.java.getMethod("call", String::class.java) }.getOrNull()
    }

    private val toolDefinitionMethod by lazy {
        runCatching { springAiCallback::class.java.getMethod("getToolDefinition") }.getOrNull()
    }

    private val toolDefinition by lazy {
        toolDefinitionMethod?.let { getter ->
            runCatching { getter.invoke(springAiCallback) }.getOrNull()
        }
    }

    override val name: String by lazy {
        readToolDefinitionProperty("name", "getName")
            ?: invokeStringMethod(springAiCallback, "getName", "name")
            ?: "unknown"
    }

    override val description: String by lazy {
        readToolDefinitionProperty("description", "getDescription")
            ?: invokeStringMethod(springAiCallback, "getDescription", "description").orEmpty()
    }

    override val inputSchema: String by lazy {
        readToolDefinitionProperty("inputSchema", "getInputSchema")
            ?: invokeStringMethod(springAiCallback, "getInputSchema", "inputSchema")
            ?: super.inputSchema
    }

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        val method = callMethod
            ?: throw RuntimeException("Tool call failed: 'call' method not found on ${springAiCallback::class.java}")
        return try {
            val jsonArgs = objectMapper.writeValueAsString(arguments)
            method.invoke(springAiCallback, jsonArgs)
        } catch (e: Exception) {
            e.throwIfCancellation()
            throw RuntimeException("Tool call failed: ${e.message}", e)
        }
    }

    /** 원본 Spring AI ToolCallback을 반환한다 (필요 시 사용) */
    fun unwrap(): Any = springAiCallback

    private fun readToolDefinitionProperty(vararg methodNames: String): String? {
        val definition = toolDefinition ?: return null
        return invokeStringMethod(definition, *methodNames)
    }

    private fun invokeStringMethod(target: Any, vararg methodNames: String): String? {
        for (methodName in methodNames) {
            val value = runCatching {
                val method = target::class.java.getMethod(methodName)
                method.invoke(target) as? String
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    companion object {
        private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
