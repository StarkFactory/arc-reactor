package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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

    /**
     * Add a message to a session's conversation memory with user ownership.
     *
     * Associates the session with a userId for per-user session isolation.
     * Default implementation delegates to [addMessage] without userId for backward compatibility.
     *
     * @param sessionId Session identifier
     * @param role Message role (user, assistant, system, tool)
     * @param content Message content
     * @param userId Owner of the session
     */
    fun addMessage(sessionId: String, role: String, content: String, userId: String) {
        addMessage(sessionId, role, content)
    }

    /**
     * List all session summaries.
     *
     * Returns metadata for all active sessions, ordered by most recent activity.
     * Default implementation returns an empty list for backward compatibility
     * with existing custom implementations.
     *
     * @return List of session summaries, ordered by lastActivity descending
     */
    fun listSessions(): List<SessionSummary> = emptyList()

    /**
     * List session summaries owned by a specific user.
     *
     * When authentication is enabled, this filters sessions by userId.
     * Default implementation falls back to [listSessions] for backward compatibility.
     *
     * @param userId Owner user ID
     * @return List of session summaries for the user, ordered by lastActivity descending
     */
    fun listSessionsByUserId(userId: String): List<SessionSummary> = listSessions()

    /**
     * Get the owner userId of a session.
     *
     * Used for ownership verification in delete/get operations.
     * Default implementation returns null (no ownership tracking).
     *
     * @param sessionId Session identifier
     * @return userId of the session owner, or null if unknown
     */
    fun getSessionOwner(sessionId: String): String? = null
}

/**
 * Summary information for a conversation session.
 *
 * Provides metadata without loading the full message history.
 *
 * @param sessionId Unique session identifier
 * @param messageCount Total messages in the session
 * @param lastActivity Timestamp of the most recent message
 * @param preview Truncated first user message (for display)
 */
data class SessionSummary(
    val sessionId: String,
    val messageCount: Int,
    val lastActivity: Instant,
    val preview: String
)

/**
 * In-Memory Conversation Memory Implementation
 *
 * Simple memory implementation that stores messages in a mutable list.
 * Automatically evicts oldest messages when exceeding the maximum limit.
 *
 * ## Characteristics
 * - Thread-safe via ReentrantReadWriteLock
 * - FIFO eviction when full
 * - Token-aware history truncation
 * - Not persistent (lost on restart)
 *
 * @param maxMessages Maximum messages to retain (default: 50)
 */
class InMemoryConversationMemory(
    private val maxMessages: Int = 50,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : ConversationMemory {

    private val messages = mutableListOf<Message>()
    private val lock = ReentrantReadWriteLock()

    override fun add(message: Message) = lock.write {
        messages.add(message)
        while (messages.size > maxMessages) {
            messages.removeFirst()
        }
    }

    override fun getHistory(): List<Message> = lock.read {
        messages.toList()
    }

    override fun clear() = lock.write {
        messages.clear()
    }

    override fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message> = lock.read {
        var totalTokens = 0
        val result = mutableListOf<Message>()

        for (message in messages.reversed()) {
            val tokens = tokenEstimator.estimate(message.content)
            if (totalTokens + tokens > maxTokens) {
                break
            }
            result.add(message)
            totalTokens += tokens
        }

        result.reversed()
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

    private val sessionOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun get(sessionId: String): ConversationMemory? = sessions.getIfPresent(sessionId)

    override fun getOrCreate(sessionId: String): ConversationMemory {
        val memory = sessions.get(sessionId) { InMemoryConversationMemory() }
        sessions.cleanUp() // Ensure maximumSize eviction is applied promptly
        return memory
    }

    override fun remove(sessionId: String) {
        sessions.invalidate(sessionId)
        sessionOwners.remove(sessionId)
    }

    override fun clear() {
        sessions.invalidateAll()
        sessionOwners.clear()
    }

    override fun addMessage(sessionId: String, role: String, content: String, userId: String) {
        sessionOwners.putIfAbsent(sessionId, userId)
        addMessage(sessionId, role, content)
    }

    override fun listSessions(): List<SessionSummary> {
        return sessions.asMap().map { (sessionId, memory) ->
            val history = memory.getHistory()
            SessionSummary(
                sessionId = sessionId,
                messageCount = history.size,
                lastActivity = history.lastOrNull()?.timestamp ?: Instant.now(),
                preview = extractPreview(history)
            )
        }.sortedByDescending { it.lastActivity }
    }

    override fun listSessionsByUserId(userId: String): List<SessionSummary> {
        val userSessionIds = sessionOwners.entries
            .filter { it.value == userId }
            .map { it.key }
            .toSet()

        return sessions.asMap()
            .filter { it.key in userSessionIds }
            .map { (sessionId, memory) ->
                val history = memory.getHistory()
                SessionSummary(
                    sessionId = sessionId,
                    messageCount = history.size,
                    lastActivity = history.lastOrNull()?.timestamp ?: Instant.now(),
                    preview = extractPreview(history)
                )
            }.sortedByDescending { it.lastActivity }
    }

    override fun getSessionOwner(sessionId: String): String? = sessionOwners[sessionId]
}

internal const val PREVIEW_MAX_LENGTH = 50

/**
 * Extract a preview string from conversation history.
 * Uses the first user message, truncated to [PREVIEW_MAX_LENGTH] characters.
 */
internal fun extractPreview(history: List<Message>): String {
    val content = history.firstOrNull { it.role == MessageRole.USER }?.content
        ?: return "Empty conversation"
    return if (content.length > PREVIEW_MAX_LENGTH) {
        content.take(PREVIEW_MAX_LENGTH) + "..."
    } else {
        content
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
