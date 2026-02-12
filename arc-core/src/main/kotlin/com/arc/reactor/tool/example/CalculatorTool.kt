package com.arc.reactor.tool.example

import com.arc.reactor.tool.ToolCallback

/**
 * Calculator tool (example)
 *
 * Called by the LLM when it needs to perform math calculations.
 * Implement the ToolCallback interface and register with @Component to auto-connect to the agent.
 *
 * ## How to create your own tool
 * 1. Implement the ToolCallback interface
 * 2. Define name, description, and inputSchema
 * 3. Write the actual logic in the call() method
 * 4. Add @Component for auto-registration
 *
 * This class is an example and is not annotated with @Component.
 * To use it, register it as a bean manually or add @Component.
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
