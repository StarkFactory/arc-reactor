package com.arc.reactor.agent.impl

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Spring AI [ChatClient]의 요청 스펙([ChatClient.ChatClientRequestSpec])을 구성하는 빌더.
 *
 * 시스템 프롬프트, 메시지 히스토리, ChatOptions, 도구 목록을 조합하여
 * LLM에 전송할 완성된 요청 스펙을 생성한다.
 *
 * 도구는 두 종류로 분리하여 등록한다:
 * - **ToolCallback**: `toolCallbacks()`로 등록 (Spring AI 콜백 방식)
 * - **어노테이션 기반 도구**: `tools()`로 등록
 *
 * @see ChatOptionsFactory ChatOptions 생성
 * @see SystemPromptBuilder 시스템 프롬프트 조합
 * @see SpringAiAgentExecutor ReAct 루프에서 매 반복마다 호출
 */
internal class PromptRequestSpecBuilder {

    /**
     * LLM 호출을 위한 요청 스펙을 생성한다.
     *
     * @param activeChatClient 활성 ChatClient 인스턴스
     * @param systemPrompt 조합된 시스템 프롬프트
     * @param messages 대화 메시지 히스토리
     * @param chatOptions LLM 호출 옵션 (temperature, maxTokens 등)
     * @param tools 사용할 도구 목록 (ToolCallback + 어노테이션 기반 혼합)
     * @return 완성된 요청 스펙
     */
    fun create(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: List<Message>,
        chatOptions: ChatOptions,
        tools: List<Any>
    ): ChatClient.ChatClientRequestSpec {
        // ── 단계 1: 기본 스펙 구성 (시스템 프롬프트 + 메시지 + 옵션) ──
        var spec = activeChatClient.prompt()
        if (systemPrompt.isNotBlank()) spec = spec.system(systemPrompt)
        spec = spec.messages(messages)
        spec = spec.options(chatOptions)

        // ── 단계 2: 도구 등록 (ToolCallback과 어노테이션 도구 분리) ──
        if (tools.isNotEmpty()) {
            val (callbacks, annotatedTools) = tools.partition { it is org.springframework.ai.tool.ToolCallback }
            if (annotatedTools.isNotEmpty()) {
                spec = spec.tools(*annotatedTools.toTypedArray())
            }
            if (callbacks.isNotEmpty()) {
                spec = spec.toolCallbacks(callbacks.filterIsInstance<org.springframework.ai.tool.ToolCallback>())
            }
        }
        return spec
    }
}
