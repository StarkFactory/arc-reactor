package com.arc.reactor.agent.impl

import mu.KotlinLogging
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions

private val logger = KotlinLogging.logger {}

/**
 * Manual/Streaming ReAct 루프가 공유하는 유틸리티.
 */
internal object ReActLoopUtils {

    /** 도구 에러 응답의 표준 접두사. ToolCallback 규약: throw 대신 "Error: ..." 문자열 반환. */
    const val TOOL_ERROR_PREFIX = "Error:"

    /** 도구당 보수적 토큰 추정치 (이름 + 설명 + JSON 스키마). */
    const val TOKENS_PER_TOOL_DEFINITION = 200

    /**
     * 도구 에러 후 LLM이 텍스트 응답(tool_call 없음)을 반환했을 때 재시도할 최대 횟수.
     * 무한 루프 방지를 위해 반드시 제한한다.
     */
    const val MAX_TEXT_RETRIES_AFTER_TOOL_ERROR = 2

    /** 도구 에러 감지 시 LLM에게 tool_call 재시도를 유도하는 힌트 메시지 (영/한 병기). */
    const val TOOL_ERROR_RETRY_HINT =
        "The previous tool call returned an error. " +
            "Analyze the error message, fix the parameters, " +
            "and retry with a corrected tool call. " +
            "Do NOT respond with text.\n" +
            "이전 도구 호출에서 에러가 발생했습니다. " +
            "에러 메시지를 분석하고 파라미터를 수정한 후 " +
            "올바른 도구 호출로 재시도하세요. " +
            "텍스트로 응답하지 마세요."

    /**
     * 도구 에러 후 LLM이 텍스트만 반환했을 때 주입하는 강화 힌트.
     * UserMessage보다 SystemMessage가 LLM 준수율이 높다.
     */
    private const val TOOL_ERROR_FORCE_RETRY_HINT =
        "IMPORTANT: You just responded with text instead of a tool call. " +
            "You MUST make a tool call to retry. Do NOT explain or apologize — " +
            "directly call the tool with corrected parameters.\n" +
            "중요: 방금 텍스트로 응답했습니다. 반드시 도구 호출로 재시도하세요. " +
            "설명이나 사과 없이 수정된 파라미터로 도구를 직접 호출하세요."

    /** 도구 응답에 에러가 포함되어 있는지 확인한다. */
    fun hasToolError(toolResponses: List<ToolResponseMessage.ToolResponse>): Boolean =
        toolResponses.any { it.responseData().startsWith(TOOL_ERROR_PREFIX) }

    /** Google GenAI 프로바이더일 때 Tool 응답을 JSON으로 정규화해야 하는지 판단합니다. */
    fun shouldNormalizeToolResponses(chatOptions: ChatOptions): Boolean =
        chatOptions is GoogleGenAiChatOptions

    /** maxToolCalls 도달 시 LLM에게 최종 답변을 요청하는 SystemMessage를 생성합니다. */
    fun buildMaxToolCallsMessage(totalToolCalls: Int, maxToolCalls: Int): SystemMessage =
        SystemMessage(
            "Tool call limit reached ($totalToolCalls/$maxToolCalls). " +
                "Summarize the results you have so far and provide your best answer. " +
                "Do not request additional tool calls."
        )

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
        cleanupRetryHints(messages)

        if (hasToolError(toolResponses)) {
            logger.debug { "Tool error detected, injecting retry hint" }
            messages.add(UserMessage(TOOL_ERROR_RETRY_HINT))
        }
    }

    /**
     * 도구 에러 후 LLM이 텍스트만 반환한 경우, 강화 힌트(SystemMessage)를 주입하고 루프 계속 여부를 반환한다.
     *
     * @param messages 현재 메시지 리스트
     * @param textRetryCount 현재까지 텍스트 재시도 횟수
     * @return true이면 루프를 계속해야 함 (강화 힌트 주입 완료), false이면 루프 종료
     */
    fun injectForceRetryHintIfNeeded(
        messages: MutableList<Message>,
        textRetryCount: Int
    ): Boolean {
        if (textRetryCount >= MAX_TEXT_RETRIES_AFTER_TOOL_ERROR) {
            logger.info {
                "Text retry limit reached ($textRetryCount/$MAX_TEXT_RETRIES_AFTER_TOOL_ERROR), " +
                    "accepting text response"
            }
            return false
        }
        cleanupRetryHints(messages)
        logger.info {
            "Tool error followed by text response, " +
                "injecting force-retry hint (attempt ${textRetryCount + 1}/$MAX_TEXT_RETRIES_AFTER_TOOL_ERROR)"
        }
        messages.add(SystemMessage(TOOL_ERROR_FORCE_RETRY_HINT))
        return true
    }

    /** 이전 iteration의 retry hint(UserMessage, SystemMessage 모두)를 제거한다. */
    private fun cleanupRetryHints(messages: MutableList<Message>) {
        messages.removeAll {
            (it is UserMessage && it.text == TOOL_ERROR_RETRY_HINT) ||
                (it is SystemMessage && it.text == TOOL_ERROR_FORCE_RETRY_HINT)
        }
    }
}
