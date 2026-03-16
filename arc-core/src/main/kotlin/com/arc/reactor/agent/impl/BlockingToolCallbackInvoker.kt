package com.arc.reactor.agent.impl

import com.arc.reactor.tool.ToolCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 블로킹 통합 지점에서 suspend [ToolCallback]을 실행하는 invoker.
 *
 * Spring AI의 ToolCallback.call() 메서드가 블로킹(non-suspend) API이므로,
 * runBlocking(Dispatchers.IO)을 사용하여 suspend 함수를 호출한다.
 * 각 도구 호출에 개별 타임아웃을 적용하여 무기한 대기를 방지한다.
 *
 * @param fallbackTimeoutMs 도구에 개별 타임아웃이 없을 때 사용할 폴백 타임아웃 (밀리초)
 * @see ArcToolCallbackAdapter 이 invoker를 사용하여 도구 호출
 */
internal class BlockingToolCallbackInvoker(
    private val fallbackTimeoutMs: Long
) {

    /**
     * 타임아웃을 적용하여 도구를 블로킹 방식으로 호출한다.
     *
     * @param toolCallback 호출할 도구 콜백
     * @param arguments 도구에 전달할 인자
     * @return 도구 실행 결과 문자열
     */
    fun invokeWithTimeout(toolCallback: ToolCallback, arguments: Map<String, Any?>): String {
        val timeoutMs = resolveTimeoutMs(toolCallback)
        return runBlocking(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                toolCallback.call(arguments)?.toString().orEmpty()
            }
        }
    }

    /** 도구 타임아웃 에러 메시지를 생성한다. */
    fun timeoutErrorMessage(toolCallback: ToolCallback): String {
        val timeoutMs = resolveTimeoutMs(toolCallback)
        return "Tool '${toolCallback.name}' timed out after ${timeoutMs}ms"
    }

    /** 도구별 타임아웃이 있으면 사용하고, 없으면 폴백 타임아웃을 사용한다. 최소 1ms. */
    private fun resolveTimeoutMs(toolCallback: ToolCallback): Long {
        return (toolCallback.timeoutMs ?: fallbackTimeoutMs).coerceAtLeast(1)
    }
}
