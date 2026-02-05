package com.arc.reactor.tool

/**
 * Tool Callback 추상화
 *
 * Spring AI의 ToolCallback을 추상화하여 프레임워크 독립성 확보.
 * 실제 구현은 Spring AI의 ToolCallback을 래핑.
 */
interface ToolCallback {
    /** Tool 이름 */
    val name: String

    /** Tool 설명 */
    val description: String

    /** Tool 실행 */
    suspend fun call(arguments: Map<String, Any?>): Any?
}

/**
 * Tool Definition
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * Tool Parameter
 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean = true
)

/**
 * Spring AI ToolCallback을 래핑하는 어댑터
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
