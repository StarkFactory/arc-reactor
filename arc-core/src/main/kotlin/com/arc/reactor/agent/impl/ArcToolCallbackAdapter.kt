package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import mu.KotlinLogging
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

private val logger = KotlinLogging.logger {}

/**
 * Arc Reactor의 [ToolCallback]을 Spring AI의 ToolCallback으로 래핑하는 어댑터.
 *
 * 프레임워크에 종속되지 않는 ToolCallback 인터페이스를 Spring AI의 도구 호출 시스템에
 * 연결하여 ChatClient.tools()와의 통합을 가능하게 한다.
 *
 * Spring AI의 콜백 API가 블로킹이므로 [BlockingToolCallbackInvoker]를 사용하여
 * suspend 함수를 runBlocking(Dispatchers.IO)으로 실행한다.
 * 도구별 타임아웃을 적용하여 무기한 대기를 방지한다.
 *
 * @param arcCallback 래핑할 Arc Reactor ToolCallback
 * @param fallbackToolTimeoutMs 도구에 개별 타임아웃이 없을 때 사용할 폴백 타임아웃 (밀리초)
 * @see ToolCallOrchestrator 이 어댑터를 통해 도구를 실행
 * @see BlockingToolCallbackInvoker 블로킹 컨텍스트에서 suspend 콜백 호출
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

    /**
     * 도구를 동기적으로 호출한다.
     *
     * Spring AI 콜백 API는 블로킹이므로, 도구 수준 타임아웃을 적용하여
     * 무기한 대기를 방지한다. 에러 시 "Error: ..." 문자열을 반환한다.
     */
    override fun call(toolInput: String): String {
        val parsedArguments = parseToolArguments(toolInput)
        return try {
            // Spring AI 콜백 API가 블로킹이므로 도구 수준 타임아웃을 적용하여 무기한 대기를 방지
            blockingInvoker.invokeWithTimeout(arcCallback, parsedArguments)
        } catch (e: TimeoutCancellationException) {
            val timeoutMessage = blockingInvoker.timeoutErrorMessage(arcCallback)
            logger.warn { timeoutMessage }
            "Error: $timeoutMessage"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error(e) { "Tool callback execution failed for '${arcCallback.name}'" }
            "Error: Tool '${arcCallback.name}' execution failed: ${e.message.orEmpty()}"
        }
    }
}
