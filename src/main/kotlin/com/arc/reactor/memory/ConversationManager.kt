package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MessageRole
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

private val logger = KotlinLogging.logger {}

/**
 * 대화 히스토리 생명주기를 관리합니다.
 *
 * - MemoryStore에서 대화 기록 로드
 * - 에이전트 실행 후 대화 기록 저장
 * - Arc Reactor ↔ Spring AI 메시지 변환
 */
interface ConversationManager {

    /**
     * 커맨드의 대화 히스토리를 Spring AI 메시지 목록으로 로드합니다.
     * conversationHistory가 직접 제공되면 이를 우선 사용하고,
     * 없으면 sessionId로 MemoryStore에서 조회합니다.
     */
    fun loadHistory(command: AgentCommand): List<Message>

    /**
     * 성공적인 에이전트 실행 결과를 MemoryStore에 저장합니다.
     * 실패한 결과는 저장하지 않습니다.
     */
    fun saveHistory(command: AgentCommand, result: AgentResult)

    /**
     * 스트리밍 결과를 MemoryStore에 저장합니다.
     */
    fun saveStreamingHistory(command: AgentCommand, content: String)
}

/**
 * MemoryStore 기반 기본 구현.
 */
class DefaultConversationManager(
    private val memoryStore: MemoryStore?,
    private val properties: AgentProperties
) : ConversationManager {

    override fun loadHistory(command: AgentCommand): List<Message> {
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { toSpringAiMessage(it) }
        }

        val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()

        return memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2)
            .map { toSpringAiMessage(it) }
    }

    override fun saveHistory(command: AgentCommand, result: AgentResult) {
        if (!result.success) return
        val sessionId = command.metadata["sessionId"]?.toString() ?: return
        if (memoryStore == null) return

        try {
            memoryStore.addMessage(sessionId = sessionId, role = "user", content = command.userPrompt)
            if (result.content != null) {
                memoryStore.addMessage(sessionId = sessionId, role = "assistant", content = result.content)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save conversation history for session $sessionId" }
        }
    }

    override fun saveStreamingHistory(command: AgentCommand, content: String) {
        val sessionId = command.metadata["sessionId"]?.toString() ?: return
        if (memoryStore == null) return

        try {
            memoryStore.addMessage(sessionId = sessionId, role = "user", content = command.userPrompt)
            if (content.isNotEmpty()) {
                memoryStore.addMessage(sessionId = sessionId, role = "assistant", content = content)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save streaming conversation history for session $sessionId" }
        }
    }

    companion object {
        fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
            return when (msg.role) {
                MessageRole.USER -> UserMessage(msg.content)
                MessageRole.ASSISTANT -> AssistantMessage(msg.content)
                MessageRole.SYSTEM -> SystemMessage(msg.content)
                MessageRole.TOOL -> ToolResponseMessage.builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("", "tool", msg.content)))
                    .build()
            }
        }
    }
}
