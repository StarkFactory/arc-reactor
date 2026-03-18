package com.arc.reactor.agent.impl

import mu.KotlinLogging
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

private val logger = KotlinLogging.logger {}

/**
 * Manual/Streaming ReAct 루프가 공유하는 유틸리티.
 */
internal object ReActLoopUtils {

    /** 도구당 보수적 토큰 추정치 (이름 + 설명 + JSON 스키마). */
    const val TOKENS_PER_TOOL_DEFINITION = 200

    /** 도구 에러 감지 시 LLM에게 tool_call 재시도를 유도하는 힌트 메시지. */
    const val TOOL_ERROR_RETRY_HINT =
        "The previous tool call returned an error. " +
            "Analyze the error message, fix the parameters, " +
            "and retry with a corrected tool call. " +
            "Do NOT respond with text."

    /**
     * 도구 응답 중 에러가 있으면 재시도 힌트 UserMessage를 주입합니다.
     *
     * LLM이 도구 에러를 보고 텍스트 응답("다시 시도하겠습니다")을 생성하는 대신
     * 실제 tool_call을 생성하도록 유도합니다. 이전 iteration의 hint는 제거하여 누적을 방지합니다.
     */
    fun injectToolErrorRetryHint(
        toolResponses: List<ToolResponseMessage.ToolResponse>,
        messages: MutableList<Message>
    ) {
        // 이전 iteration의 retry hint는 항상 제거 — 성공 후에도 stale hint가 남지 않도록
        messages.removeAll { it is UserMessage && it.text == TOOL_ERROR_RETRY_HINT }

        val hasError = toolResponses.any { it.responseData().startsWith("Error:") }
        if (hasError) {
            logger.debug { "Tool error detected, injecting retry hint" }
            messages.add(UserMessage(TOOL_ERROR_RETRY_HINT))
        }
    }
}
