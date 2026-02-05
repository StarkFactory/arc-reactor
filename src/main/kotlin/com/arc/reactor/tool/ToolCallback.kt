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
     * Execute the tool with the given arguments.
     *
     * @param arguments Key-value pairs of tool parameters (parsed from LLM's JSON)
     * @return Tool execution result (will be converted to string for LLM)
     */
    suspend fun call(arguments: Map<String, Any?>): Any?
}

/**
 * Tool Definition Metadata
 *
 * Describes a tool's interface for documentation and schema generation.
 *
 * @property name Tool identifier
 * @property description Tool purpose and usage
 * @property parameters List of input parameters
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * Tool Parameter Definition
 *
 * Describes a single parameter for a tool.
 *
 * @property name Parameter name (used as JSON key)
 * @property description Parameter purpose (shown to LLM)
 * @property type JSON Schema type (string, number, boolean, object, array)
 * @property required Whether the parameter is mandatory
 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean = true
)

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
    override val name: String
        get() {
            // Reflection으로 Spring AI ToolCallback의 name 접근
            return try {
                val method = springAiCallback::class.java.getMethod("getName")
                method.invoke(springAiCallback) as String
            } catch (e: Exception) {
                "unknown"
            }
        }

    override val description: String
        get() {
            return try {
                val method = springAiCallback::class.java.getMethod("getDescription")
                method.invoke(springAiCallback) as String
            } catch (e: Exception) {
                ""
            }
        }

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val method = springAiCallback::class.java.getMethod("call", String::class.java)
            val jsonArgs = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .writeValueAsString(arguments)
            method.invoke(springAiCallback, jsonArgs)
        } catch (e: Exception) {
            throw RuntimeException("Tool call failed: ${e.message}", e)
        }
    }

    /** 원본 Spring AI ToolCallback 반환 (필요시) */
    fun unwrap(): Any = springAiCallback
}
