package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import java.time.Instant

/**
 * 대화 메모리 인터페이스
 *
 * Multi-turn 대화를 위한 컨텍스트 관리.
 */
interface ConversationMemory {
    /**
     * 메시지 추가
     */
    fun add(message: Message)

    /**
     * 대화 기록 조회
     */
    fun getHistory(): List<Message>

    /**
     * 대화 기록 초기화
     */
    fun clear()

    /**
     * 토큰 제한에 맞게 기록 가져오기
     */
    fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message>
}

/**
 * 세션 ID 기반 메모리 저장소
 */
interface MemoryStore {
    fun get(sessionId: String): ConversationMemory?
    fun getOrCreate(sessionId: String): ConversationMemory
    fun remove(sessionId: String)
    fun clear()
}

/**
 * 인메모리 대화 메모리 구현
 */
class InMemoryConversationMemory(
    private val maxMessages: Int = 50
) : ConversationMemory {

    private val messages = mutableListOf<Message>()

    override fun add(message: Message) {
        messages.add(message)
        // 최대 개수 초과 시 오래된 메시지 제거
        while (messages.size > maxMessages) {
            messages.removeFirst()
        }
    }

    override fun getHistory(): List<Message> = messages.toList()

    override fun clear() {
        messages.clear()
    }

    override fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message> {
        var totalTokens = 0
        val result = mutableListOf<Message>()

        // 최신 메시지부터 역순으로
        for (message in messages.reversed()) {
            val tokens = message.content.length / 4  // 대략적 토큰 추정
            if (totalTokens + tokens > maxTokens) {
                break
            }
            result.add(0, message)
            totalTokens += tokens
        }

        return result
    }
}

/**
 * 인메모리 메모리 저장소
 */
class InMemoryMemoryStore(
    private val maxSessions: Int = 1000
) : MemoryStore {

    private val sessions = LinkedHashMap<String, ConversationMemory>()

    override fun get(sessionId: String): ConversationMemory? = sessions[sessionId]

    override fun getOrCreate(sessionId: String): ConversationMemory {
        return sessions.getOrPut(sessionId) {
            // LRU eviction
            if (sessions.size >= maxSessions) {
                sessions.remove(sessions.keys.first())
            }
            InMemoryConversationMemory()
        }
    }

    override fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }

    override fun clear() {
        sessions.clear()
    }
}

// Extension functions
fun ConversationMemory.addUserMessage(content: String) {
    add(Message(MessageRole.USER, content))
}

fun ConversationMemory.addAssistantMessage(content: String) {
    add(Message(MessageRole.ASSISTANT, content))
}

fun ConversationMemory.addSystemMessage(content: String) {
    add(Message(MessageRole.SYSTEM, content))
}
