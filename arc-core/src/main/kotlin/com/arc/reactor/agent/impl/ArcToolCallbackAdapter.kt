package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
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
 * ## R246: Evaluation 메트릭 자동 기록
 *
 * [evaluationCollector]가 주입되면(기본 no-op) 예외 발생 시 [ExecutionStage.TOOL_CALL]
 * + 예외 클래스 이름으로 [EvaluationMetricsCollector.recordError]를 호출한다.
 * `TimeoutCancellationException`과 일반 `Exception` 모두 기록되며, `CancellationException`은
 * 기록 없이 재throw (코루틴 협력적 취소 원칙 유지).
 *
 * @param arcCallback 래핑할 Arc Reactor ToolCallback
 * @param fallbackToolTimeoutMs 도구에 개별 타임아웃이 없을 때 사용할 폴백 타임아웃 (밀리초)
 * @param evaluationCollector R246: 예외 발생 시 메트릭을 기록할 수집기 (기본 no-op)
 * @see ToolCallOrchestrator 이 어댑터를 통해 도구를 실행
 * @see BlockingToolCallbackInvoker 블로킹 컨텍스트에서 suspend 콜백 호출
 */
internal class ArcToolCallbackAdapter(
    val arcCallback: ToolCallback,
    fallbackToolTimeoutMs: Long = 15_000,
    private val evaluationCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector
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
        // R254: JSON 파싱 실패를 PARSING stage 메트릭에 자동 기록
        val parsedArguments = parseToolArguments(toolInput, evaluationCollector)
        return try {
            // Spring AI 콜백 API가 블로킹이므로 도구 수준 타임아웃을 적용하여 무기한 대기를 방지
            blockingInvoker.invokeWithTimeout(arcCallback, parsedArguments)
        } catch (e: TimeoutCancellationException) {
            // R246: 타임아웃도 TOOL_CALL 예외로 기록 (운영 관점에서 중요한 실패 유형)
            evaluationCollector.recordError(ExecutionStage.TOOL_CALL, e)
            val timeoutMessage = blockingInvoker.timeoutErrorMessage(arcCallback)
            logger.warn { timeoutMessage }
            "Error: $timeoutMessage"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // R246: TOOL_CALL stage로 런타임 예외 자동 기록
            evaluationCollector.recordError(ExecutionStage.TOOL_CALL, e)
            logger.error(e) { "도구 콜백 실행 실패: '${arcCallback.name}'" }
            "Error: 도구 '${arcCallback.name}' 실행 중 오류가 발생했습니다 (${e.javaClass.simpleName})"
        }
    }
}
