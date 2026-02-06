package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant

/**
 * Conversation Memory Interface
 *
 * Manages conversation history for multi-turn interactions with the AI agent.
 * Enables context retention across multiple exchanges.
 *
 * ## Features
 * - Message storage and retrieval
 * - Token-aware history truncation
 * - Automatic oldest message eviction
 *
 * ## Example Usage
 * ```kotlin
 * val memory = InMemoryConversationMemory(maxMessages = 50)
 *
 * // Add messages
 * memory.addUserMessage("Hello!")
 * memory.addAssistantMessage("Hi! How can I help?")
 *
 * // Get history for next LLM call
 * val history = memory.getHistoryWithinTokenLimit(maxTokens = 4000)
 * ```
 *
 * @see MemoryStore for session-based memory management
 * @see InMemoryConversationMemory for default implementation
 */
interface ConversationMemory {
    /**
     * Add a message to the conversation history.
     *
     * @param message The message to add
     */
    fun add(message: Message)

    /**
     * Get the complete conversation history.
     *
     * @return All messages in chronological order
     */
    fun getHistory(): List<Message>

    /**
     * Clear all messages from the conversation history.
     */
    fun clear()

    /**
     * Get conversation history within a token limit.
     *
     * Returns the most recent messages that fit within the specified token budget.
     * Useful for staying within LLM context window limits.
     *
     * @param maxTokens Maximum approximate token count
     * @return Most recent messages within the token limit
     */
    fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message>
}

/**
 * Session-Based Memory Store Interface
 *
 * Manages multiple conversation memories indexed by session ID.
 * Enables concurrent users with isolated conversation histories.
 *
 * ## Example Usage
 * ```kotlin
 * val store = InMemoryMemoryStore(maxSessions = 1000)
 *
 * // Get or create session memory
 * val memory = store.getOrCreate("user-123-session-456")
 * memory.addUserMessage("Hello!")
 *
 * // Add message via store (convenience method)
 * store.addMessage("user-123-session-456", "assistant", "Hi there!")
 *
 * // Clean up old session
 * store.remove("old-session-id")
 * ```
 *
 * @see InMemoryMemoryStore for default implementation
 */
interface MemoryStore {
    /**
     * Get conversation memory for a session.
     *
     * @param sessionId Unique session identifier
     * @return ConversationMemory if exists, null otherwise
     */
    fun get(sessionId: String): ConversationMemory?

    /**
     * Get existing or create new conversation memory for a session.
     *
     * @param sessionId Unique session identifier
     * @return Existing or newly created ConversationMemory
     */
    fun getOrCreate(sessionId: String): ConversationMemory

    /**
     * Remove conversation memory for a session.
     *
     * @param sessionId Session to remove
     */
    fun remove(sessionId: String)

    /**
     * Clear all session memories.
     */
    fun clear()

    /**
     * Add a message to a session's conversation memory.
     *
     * Convenience method that creates the session if it doesn't exist.
     *
     * @param sessionId Session identifier
     * @param role Message role (user, assistant, system, tool)
     * @param content Message content
     */
    fun addMessage(sessionId: String, role: String, content: String) {
        val memory = getOrCreate(sessionId)
        val messageRole = when (role.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            "tool" -> MessageRole.TOOL
            else -> MessageRole.USER
        }
        memory.add(Message(messageRole, content))
    }
}

/**
 * In-Memory Conversation Memory Implementation
 *
 * Simple memory implementation that stores messages in a mutable list.
 * Automatically evicts oldest messages when exceeding the maximum limit.
 *
 * ## Characteristics
 * - Fast O(1) add operations
 * - FIFO eviction when full
 * - Approximate token counting (chars / 4)
 * - Not persistent (lost on restart)
 *
 * @param maxMessages Maximum messages to retain (default: 50)
 */
class InMemoryConversationMemory(
    private val maxMessages: Int = 50,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : ConversationMemory {

    private val messages = mutableListOf<Message>()

    override fun add(message: Message) {
        messages.add(message)
        // Evict oldest messages when exceeding max count
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

        // Iterate in reverse from newest messages
        for (message in messages.reversed()) {
            val tokens = tokenEstimator.estimate(message.content)
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
 * In-Memory Memory Store Implementation
 *
 * Session-based memory store using ConcurrentHashMap for thread-safe access.
 *
 * ## Characteristics
 * - LRU eviction when session limit reached
 * - Thread-safe concurrent access
 * - Not persistent (lost on restart)
 *
 * ## For Production
 * Consider implementing MemoryStore with:
 * - Redis for distributed sessions
 * - PostgreSQL for persistence
 * - Custom TTL for automatic cleanup
 *
 * @param maxSessions Maximum concurrent sessions (default: 1000)
 */
class InMemoryMemoryStore(
    private val maxSessions: Int = 1000
) : MemoryStore {

    private val sessions = Caffeine.newBuilder()
        .maximumSize(maxSessions.toLong())
        .build<String, ConversationMemory>()

    override fun get(sessionId: String): ConversationMemory? = sessions.getIfPresent(sessionId)

    override fun getOrCreate(sessionId: String): ConversationMemory {
        val memory = sessions.get(sessionId) { InMemoryConversationMemory() }
        sessions.cleanUp()
        return memory
    }

    override fun remove(sessionId: String) {
        sessions.invalidate(sessionId)
    }

    override fun clear() {
        sessions.invalidateAll()
    }
}

// Extension Functions for ConversationMemory

/**
 * Add a user message to the conversation.
 *
 * @param content Message content
 */
fun ConversationMemory.addUserMessage(content: String) {
    add(Message(MessageRole.USER, content))
}

/**
 * Add an assistant message to the conversation.
 *
 * @param content Message content
 */
fun ConversationMemory.addAssistantMessage(content: String) {
    add(Message(MessageRole.ASSISTANT, content))
}

/**
 * Add a system message to the conversation.
 *
 * @param content Message content
 */
fun ConversationMemory.addSystemMessage(content: String) {
    add(Message(MessageRole.SYSTEM, content))
}
