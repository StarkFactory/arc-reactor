package com.arc.reactor.agent.impl

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions

internal class PromptRequestSpecBuilder {

    fun create(
        activeChatClient: ChatClient,
        systemPrompt: String,
        messages: List<Message>,
        chatOptions: ChatOptions,
        tools: List<Any>
    ): ChatClient.ChatClientRequestSpec {
        var spec = activeChatClient.prompt()
        if (systemPrompt.isNotBlank()) spec = spec.system(systemPrompt)
        spec = spec.messages(messages)
        spec = spec.options(chatOptions)
        if (tools.isNotEmpty()) {
            val (callbacks, annotatedTools) = tools.partition { it is org.springframework.ai.tool.ToolCallback }
            if (annotatedTools.isNotEmpty()) {
                spec = spec.tools(*annotatedTools.toTypedArray())
            }
            if (callbacks.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                spec = spec.toolCallbacks(callbacks as List<org.springframework.ai.tool.ToolCallback>)
            }
        }
        return spec
    }
}
