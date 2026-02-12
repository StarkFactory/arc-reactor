package com.arc.reactor.tool

/**
 * Tool Callback Interface
 *
 * Framework-agnostic abstraction for tool execution.
 * Provides independence from Spring AI's ToolCallback while maintaining compatibility.
 *
 * ## Purpose
 * - Decouple tool implementations from Spring AI specifics
 * - Enable testing without Spring AI dependencies
 * - Support both local tools and MCP (Model Context Protocol) tools
 *
 * ## Example Implementation
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
 * @see SpringAiToolCallbackAdapter for wrapping Spring AI tools
 * @see ToolDefinition for tool metadata
 */
interface ToolCallback {
    /** Unique tool identifier used by the LLM to invoke this tool */
    val name: String

    /** Human-readable description of what this tool does (shown to LLM) */
    val description: String

    /**
     * JSON Schema describing the tool's input parameters.
     * Used by the LLM to generate correct tool call arguments.
     *
     * Override this to specify parameters:
     * ```kotlin
     * override val inputSchema: String get() = """
     *   {"type":"object","properties":{"location":{"type":"string","description":"City name"}},"required":["location"]}
     * """
     * ```
     *
     * Default: empty object (no parameters).
     */
    val inputSchema: String
        get() = """{"type":"object","properties":{}}"""

    /**
     * Per-tool timeout in milliseconds. When set, overrides the global
     * `arc.reactor.concurrency.tool-call-timeout-ms` for this tool only.
     * Null means use the global default.
     */
    val timeoutMs: Long?
        get() = null

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments Key-value pairs of tool parameters (parsed from LLM's JSON)
     * @return Tool execution result (will be converted to string for LLM)
     */
    suspend fun call(arguments: Map<String, Any?>): Any?
}

/**
 * Spring AI ToolCallback Adapter
 *
 * Wraps Spring AI's ToolCallback to provide framework-agnostic access.
 * Uses reflection to maintain loose coupling with Spring AI classes.
 *
 * ## Usage
 * ```kotlin
 * val springTool: org.springframework.ai.tool.ToolCallback = ...
 * val arcTool = SpringAiToolCallbackAdapter(springTool)
 *
 * // Use through Arc Reactor's interface
 * val result = arcTool.call(mapOf("query" to "test"))
 * ```
 *
 * @param springAiCallback The Spring AI ToolCallback instance to wrap
 */
class SpringAiToolCallbackAdapter(
    private val springAiCallback: Any  // org.springframework.ai.tool.ToolCallback
) : ToolCallback {

    private val nameMethod by lazy {
        runCatching { springAiCallback::class.java.getMethod("getName") }.getOrNull()
    }

    private val descriptionMethod by lazy {
        runCatching { springAiCallback::class.java.getMethod("getDescription") }.getOrNull()
    }

    private val callMethod by lazy {
        runCatching { springAiCallback::class.java.getMethod("call", String::class.java) }.getOrNull()
    }

    override val name: String by lazy {
        try {
            nameMethod?.invoke(springAiCallback) as? String ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    override val description: String by lazy {
        try {
            descriptionMethod?.invoke(springAiCallback) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        val method = callMethod
            ?: throw RuntimeException("Tool call failed: 'call' method not found on ${springAiCallback::class.java}")
        return try {
            val jsonArgs = objectMapper.writeValueAsString(arguments)
            method.invoke(springAiCallback, jsonArgs)
        } catch (e: Exception) {
            throw RuntimeException("Tool call failed: ${e.message}", e)
        }
    }

    /** Returns the original Spring AI ToolCallback (if needed) */
    fun unwrap(): Any = springAiCallback

    companion object {
        private val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
