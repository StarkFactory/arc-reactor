package com.arc.reactor.tool.example

import com.arc.reactor.tool.ToolCallback

/**
 * 계산기 도구 (예시)
 *
 * LLM이 수학 계산이 필요할 때 이 도구를 호출합니다.
 * ToolCallback 인터페이스를 구현하고 @Component로 등록하면 자동으로 에이전트에 연결됩니다.
 *
 * ## 자신만의 도구를 만들려면?
 * 1. ToolCallback 인터페이스를 구현합니다
 * 2. name, description, inputSchema를 정의합니다
 * 3. call() 메서드에 실제 로직을 작성합니다
 * 4. @Component를 붙이면 자동 등록됩니다
 *
 * 이 클래스는 예시이므로 @Component가 붙어있지 않습니다.
 * 사용하려면 직접 빈으로 등록하거나 @Component를 추가하세요.
 */
class CalculatorTool : ToolCallback {

    override val name = "calculator"

    override val description = "Perform basic math operations: add, subtract, multiply, divide"

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["add", "subtract", "multiply", "divide"],
                  "description": "The math operation to perform"
                },
                "a": {
                  "type": "number",
                  "description": "First number"
                },
                "b": {
                  "type": "number",
                  "description": "Second number"
                }
              },
              "required": ["operation", "a", "b"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val operation = arguments["operation"] as? String
            ?: return "Error: 'operation' parameter is required"
        val a = (arguments["a"] as? Number)?.toDouble()
            ?: return "Error: 'a' parameter is required"
        val b = (arguments["b"] as? Number)?.toDouble()
            ?: return "Error: 'b' parameter is required"

        return when (operation) {
            "add" -> "$a + $b = ${a + b}"
            "subtract" -> "$a - $b = ${a - b}"
            "multiply" -> "$a * $b = ${a * b}"
            "divide" -> {
                if (b == 0.0) "Error: Division by zero"
                else "$a / $b = ${a / b}"
            }
            else -> "Error: Unknown operation '$operation'. Use: add, subtract, multiply, divide"
        }
    }
}
