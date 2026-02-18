package com.arc.reactor.mcp

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP Tool Callback Wrapper
 *
 * Wraps an MCP tool as an Arc Reactor ToolCallback for unified tool handling.
 *
 * ## How It Works
 * 1. Receives tool call arguments as a Map
 * 2. Converts to MCP CallToolRequest
 * 3. Invokes tool via MCP client
 * 4. Extracts and returns text content
 *
 * @param client The MCP client connection
 * @param name Tool identifier
 * @param description Tool description for LLM
 * @param mcpInputSchema JSON Schema for tool parameters (optional)
 */
class McpToolCallback(
    private val client: McpSyncClient,
    override val name: String,
    override val description: String,
    private val mcpInputSchema: McpSchema.JsonSchema?,
    private val maxOutputLength: Int = 50_000
) : ToolCallback {

    override val inputSchema: String
        get() = mcpInputSchema?.let {
            try {
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it)
            } catch (e: Exception) {
                """{"type":"object","properties":{}}"""
            }
        } ?: """{"type":"object","properties":{}}"""

    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val request = McpSchema.CallToolRequest(name, arguments)
            val result = client.callTool(request)

            // Convert result to string and enforce output length limit
            val output = result.content().joinToString("\n") { content ->
                when (content) {
                    is McpSchema.TextContent -> content.text()
                    is McpSchema.ImageContent -> "[Image: ${content.mimeType()}]"
                    is McpSchema.EmbeddedResource -> "[Resource: ${content.resource().uri()}]"
                    else -> content.toString()
                }
            }

            if (output.length > maxOutputLength) {
                logger.warn { "MCP tool '$name' output truncated: ${output.length} -> $maxOutputLength chars" }
                output.take(maxOutputLength) + "\n[TRUNCATED: output exceeded $maxOutputLength characters]"
            } else {
                output
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to call MCP tool: $name" }
            "Error: ${e.message}"
        }
    }
}
