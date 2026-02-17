package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.reactive.asFlow

private val logger = KotlinLogging.logger {}

internal data class StreamingLoopResult(
    val success: Boolean,
    val collectedContent: String,
    val lastIterationContent: String
)

internal class StreamingReActLoopExecutor(
    private val messageTrimmer: ConversationMessageTrimmer,
    private val toolCallOrchestrator: ToolCallOrchestrator,
    private val buildRequestSpec: (
        ChatClient,
        String,
        List<Message>,
        ChatOptions,
        List<Any>
    ) -> ChatClient.ChatClientRequestSpec,
    private val callWithRetry: suspend (
        suspend () -> Flux<org.springframework.ai.chat.model.ChatResponse>
    ) -> Flux<org.springframework.ai.chat.model.ChatResponse>,
    private val buildChatOptions: (AgentCommand, Boolean) -> ChatOptions
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
        maxToolCalls: Int,
        emit: suspend (String) -> Unit
    ): StreamingLoopResult {
        var activeTools = initialTools
        var chatOptions = buildChatOptions(command, activeTools.isNotEmpty())
        var totalToolCalls = 0
        var lastIterationContent = ""
        val collectedContent = StringBuilder()

        val messages = mutableListOf<Message>()
        if (conversationHistory.isNotEmpty()) {
            messages.addAll(conversationHistory)
        }
        messages.add(MediaConverter.buildUserMessage(command.userPrompt, command.media))

        while (true) {
            val currentIterationContent = StringBuilder()
            messageTrimmer.trim(messages, systemPrompt)

            val requestSpec = buildRequestSpec(activeChatClient, systemPrompt, messages, chatOptions, activeTools)
            val flux = callWithRetry { requestSpec.stream().chatResponse() }
            var pendingToolCalls: List<AssistantMessage.ToolCall> = emptyList()
            val currentChunkText = StringBuilder()

            flux.asFlow().collect { chunk ->
                val text = chunk.result.output.text
                if (!text.isNullOrEmpty()) {
                    emit(text)
                    currentChunkText.append(text)
                    collectedContent.append(text)
                    currentIterationContent.append(text)
                }
                val chunkToolCalls = chunk.result.output.toolCalls
                if (!chunkToolCalls.isNullOrEmpty()) {
                    pendingToolCalls = chunkToolCalls
                }
            }

            lastIterationContent = currentIterationContent.toString()
            if (pendingToolCalls.isEmpty() || activeTools.isEmpty()) {
                return StreamingLoopResult(
                    success = true,
                    collectedContent = collectedContent.toString(),
                    lastIterationContent = lastIterationContent
                )
            }

            val assistantMsg = AssistantMessage.builder()
                .content(currentChunkText.toString())
                .toolCalls(pendingToolCalls)
                .build()
            messages.add(assistantMsg)

            for (toolCall in pendingToolCalls) {
                emit(StreamEventMarker.toolStart(toolCall.name()))
            }

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

            for (toolCall in pendingToolCalls) {
                emit(StreamEventMarker.toolEnd(toolCall.name()))
            }

            messages.add(
                ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build()
            )

            if (totalToolCalls >= maxToolCalls) {
                logger.info { "maxToolCalls reached in streaming ($totalToolCalls/$maxToolCalls), final answer" }
                activeTools = emptyList()
                chatOptions = buildChatOptions(command, false)
            }
        }
    }
}
