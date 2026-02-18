package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import mu.KotlinLogging
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

private val logger = KotlinLogging.logger {}

/**
 * Adapter that wraps Arc Reactor's ToolCallback as a Spring AI ToolCallback.
 *
 * Bridges the framework-agnostic ToolCallback interface to Spring AI's
 * tool calling system, enabling integration with ChatClient.tools().
 */
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback,
    fallbackToolTimeoutMs: Long = 15_000
) : org.springframework.ai.tool.ToolCallback {

    private val blockingInvoker = BlockingToolCallbackInvoker(fallbackToolTimeoutMs)
    private val toolDefinition = ToolDefinition.builder()
        .name(arcCallback.name)
        .description(arcCallback.description)
        .inputSchema(arcCallback.inputSchema)
        .build()

    override fun getToolDefinition(): ToolDefinition = toolDefinition

    override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()

    override fun call(toolInput: String): String {
        val parsedArguments = parseToolArguments(toolInput)
        return try {
            // Spring AI callback API is blocking; enforce tool-level timeout to avoid indefinite hangs.
            blockingInvoker.invokeWithTimeout(arcCallback, parsedArguments)
        } catch (e: TimeoutCancellationException) {
            val timeoutMessage = blockingInvoker.timeoutErrorMessage(arcCallback)
            logger.warn { timeoutMessage }
            throw RuntimeException(timeoutMessage, e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error(e) { "Tool callback execution failed for '${arcCallback.name}'" }
            throw RuntimeException(
                "Tool '${arcCallback.name}' execution failed: ${e.message.orEmpty()}",
                e
            )
        }
    }
}
