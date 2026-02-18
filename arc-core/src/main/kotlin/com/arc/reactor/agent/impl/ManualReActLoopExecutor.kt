package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.hook.model.HookContext
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

internal class ManualReActLoopExecutor(
    private val messageTrimmer: ConversationMessageTrimmer,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val buildRequestSpec: (
        ChatClient,
        String,
        List<Message>,
        ChatOptions,
        List<Any>
    ) -> ChatClient.ChatClientRequestSpec,
    private val callWithRetry: suspend (suspend () -> org.springframework.ai.chat.model.ChatResponse?) -> org.springframework.ai.chat.model.ChatResponse?,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions,
    private val validateAndRepairResponse: suspend (
        String,
        ResponseFormat,
        AgentCommand,
        TokenUsage?,
        List<String>
    ) -> AgentResult,
    private val recordTokenUsage: (TokenUsage) -> Unit
) {

    suspend fun execute(
        command: AgentCommand,
        activeChatClient: ChatClient,
        systemPrompt: String,
        initialTools: List<Any>,
        conversationHistory: List<Message>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>?,
        maxToolCalls: Int
    ): AgentResult {
        var totalToolCalls = 0
        var activeTools = initialTools
        var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
        var totalTokenUsage: TokenUsage? = null

        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        while (true) {
            messageTrimmer.trim(messages, systemPrompt)

            val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
            val chatResponse = callWithRetry {
                runInterruptible { requestSpec.call().chatResponse() }
            }

            totalTokenUsage = accumulateTokenUsage(chatResponse, totalTokenUsage)
            chatResponse?.metadata?.usage?.let { usage ->
                recordTokenUsage(
                    TokenUsage(
                        promptTokens = usage.promptTokens.toInt(),
                        completionTokens = usage.completionTokens.toInt(),
                        totalTokens = usage.totalTokens.toInt()
                    )
                )
            }

            val assistantOutput = chatResponse?.results?.firstOrNull()?.output
            val pendingToolCalls = assistantOutput?.toolCalls.orEmpty()
            if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                return validateAndRepairResponse(
                    assistantOutput?.text.orEmpty(),
                    command.responseFormat,
                    command,
                    totalTokenUsage,
                    ArrayList(toolsUsed)
                )
            }

            val assistantMessage = requireNotNull(assistantOutput) {
                "Assistant output is required when tool calls are present"
            }
            messages.add(assistantMessage)

            val totalToolCallsCounter = AtomicInteger(totalToolCalls)
            val toolResponses = toolCallOrchestrator.executeInParallel(
                pendingToolCalls,
                activeTools,
                hookContext,
                toolsUsed,
                totalToolCallsCounter,
                maxToolCalls,
                allowedTools
            )
            totalToolCalls = totalToolCallsCounter.get()

            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            if (totalToolCalls >= maxToolCalls) {
                logger.info { "maxToolCalls reached ($totalToolCalls/$maxToolCalls), final answer" }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
            }
        }
    }

    private fun accumulateTokenUsage(
        chatResponse: org.springframework.ai.chat.model.ChatResponse?,
        previous: TokenUsage?
    ): TokenUsage? {
        val usage = chatResponse?.metadata?.usage ?: return previous
        val current = TokenUsage(
            promptTokens = usage.promptTokens.toInt(),
            completionTokens = usage.completionTokens.toInt(),
            totalTokens = usage.totalTokens.toInt()
        )
        return previous?.let {
            TokenUsage(
                promptTokens = it.promptTokens + current.promptTokens,
                completionTokens = it.completionTokens + current.completionTokens,
                totalTokens = it.totalTokens + current.totalTokens
            )
        } ?: current
    }
}
