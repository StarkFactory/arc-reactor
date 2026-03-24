package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 대화 메모리 인터페이스.
 *
 * AI 에이전트와의 멀티턴 대화에서 대화 이력을 관리한다.
 * 여러 교환에 걸친 컨텍스트 유지를 가능하게 한다.
 *
 * ## 주요 기능
 * - 메시지 저장 및 조회
 * - 토큰 인식 이력 잘림
 * - 오래된 메시지 자동 퇴거
 *
 * ## 사용 예시
 * ```kotlin
 * val memory = InMemoryConversationMemory(maxMessages = 50)
 *
 * // 메시지 추가
 * memory.addUserMessage("Hello!")
 * memory.addAssistantMessage("Hi! How can I help?")
 *
 * // 다음 LLM 호출을 위한 이력 조회
 * val history = memory.getHistoryWithinTokenLimit(maxTokens = 4000)
 * ```
 *
 * @see MemoryStore 세션 기반 메모리 관리
 * @see InMemoryConversationMemory 기본 구현체
 * @see JdbcMemoryStore 영속 JDBC 기반 구현체
 */
interface ConversationMemory {
    /**
     * 대화 이력에 메시지를 추가한다.
     *
     * @param message 추가할 메시지
     */
    fun add(message: Message)

    /**
     * 전체 대화 이력을 조회한다.
     *
     * @return 시간순으로 정렬된 모든 메시지
     */
    fun getHistory(): List<Message>

    /**
     * 대화 이력의 모든 메시지를 삭제한다.
     */
    fun clear()

    /**
     * 토큰 제한 내의 대화 이력을 조회한다.
     *
     * 지정된 토큰 예산 내에 들어가는 가장 최근 메시지를 반환한다.
     * LLM 컨텍스트 윈도우 제한을 준수하는 데 유용하다.
     *
     * @param maxTokens 최대 대략적 토큰 수
     * @return 토큰 제한 내의 가장 최근 메시지
     */
    fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message>
}

/**
 * 세션 기반 메모리 스토어 인터페이스.
 *
 * 세션 ID로 인덱싱된 여러 대화 메모리를 관리한다.
 * 격리된 대화 이력을 가진 동시 사용자를 지원한다.
 *
 * ## 사용 예시
 * ```kotlin
 * val store = InMemoryMemoryStore(maxSessions = 1000)
 *
 * // 세션 메모리를 가져오거나 생성
 * val memory = store.getOrCreate("user-123-session-456")
 * memory.addUserMessage("Hello!")
 *
 * // 스토어를 통해 메시지 추가 (편의 메서드)
 * store.addMessage("user-123-session-456", "assistant", "Hi there!")
 *
 * // 오래된 세션 정리
 * store.remove("old-session-id")
 * ```
 *
 * @see InMemoryMemoryStore 기본 구현체
 * @see JdbcMemoryStore 영속 JDBC 기반 구현체
 */
interface MemoryStore {
    /**
     * 세션의 대화 메모리를 조회한다.
     *
     * @param sessionId 고유 세션 식별자
     * @return ConversationMemory (있으면), 없으면 null
     */
    fun get(sessionId: String): ConversationMemory?

    /**
     * 기존 메모리를 가져오거나 새 대화 메모리를 생성한다.
     *
     * @param sessionId 고유 세션 식별자
     * @return 기존 또는 새로 생성된 ConversationMemory
     */
    fun getOrCreate(sessionId: String): ConversationMemory

    /**
     * 세션의 대화 메모리를 제거한다.
     *
     * @param sessionId 제거할 세션
     */
    fun remove(sessionId: String)

    /**
     * 모든 세션 메모리를 삭제한다.
     */
    fun clear()

    /**
     * 세션의 대화 메모리에 메시지를 추가한다.
     *
     * 세션이 존재하지 않으면 자동으로 생성하는 편의 메서드.
     *
     * @param sessionId 세션 식별자
     * @param role 메시지 역할 (user, assistant, system, tool)
     * @param content 메시지 내용
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
     * 사용자 소유권을 가진 세션 대화 메모리에 메시지를 추가한다.
     *
     * 사용자별 세션 격리를 위해 세션을 userId와 연결한다.
     * 하위 호환성을 위해 기본 구현은 userId 없이 [addMessage]에 위임한다.
     *
     * @param sessionId 세션 식별자
     * @param role 메시지 역할 (user, assistant, system, tool)
     * @param content 메시지 내용
     * @param userId 세션 소유자
     */
    fun addMessage(sessionId: String, role: String, content: String, userId: String) {
        addMessage(sessionId, role, content)
    }

    /**
     * 모든 세션 요약을 조회한다.
     *
     * 모든 활성 세션의 메타데이터를 반환하며 최근 활동 순으로 정렬된다.
     * 하위 호환성을 위해 기본 구현은 빈 목록을 반환한다.
     *
     * @return 세션 요약 목록, lastActivity 내림차순
     */
    fun listSessions(): List<SessionSummary> = emptyList()

    /**
     * 특정 사용자가 소유한 세션 요약을 조회한다.
     *
     * 구현체는 userId로 세션을 필터링해야 한다.
     * 하위 호환성을 위해 기본 구현은 [listSessions]로 폴백한다.
     *
     * @param userId 소유자 사용자 ID
     * @return 해당 사용자의 세션 요약 목록, lastActivity 내림차순
     */
    fun listSessionsByUserId(userId: String): List<SessionSummary> = listSessions()

    /**
     * 특정 사용자가 소유한 세션 요약을 페이지네이션하여 조회한다.
     *
     * DB 수준에서 LIMIT/OFFSET을 적용하여 대량 세션 시 불필요한 메모리 할당을 방지한다.
     * 하위 호환성을 위해 기본 구현은 [listSessionsByUserId]로 폴백하여 인메모리 슬라이싱한다.
     *
     * @param userId 소유자 사용자 ID
     * @param limit 반환할 최대 항목 수
     * @param offset 0 기반 시작 인덱스
     * @return 페이지네이션된 세션 요약 목록과 전체 개수
     */
    fun listSessionsByUserIdPaginated(
        userId: String,
        limit: Int,
        offset: Int
    ): PaginatedSessionResult {
        val all = listSessionsByUserId(userId)
        val safeOffset = offset.coerceAtLeast(0)
        val end = (safeOffset + limit).coerceAtMost(all.size)
        val items = if (safeOffset >= all.size) emptyList() else all.subList(safeOffset, end)
        return PaginatedSessionResult(items = items, total = all.size)
    }

    /**
     * 세션의 소유자 userId를 조회한다.
     *
     * 삭제/조회 작업에서 소유권 검증에 사용된다.
     * 기본 구현은 null을 반환한다 (소유권 추적 없음).
     *
     * @param sessionId 세션 식별자
     * @return 세션 소유자의 userId, 알 수 없으면 null
     */
    fun getSessionOwner(sessionId: String): String? = null
}

/**
 * 대화 세션의 요약 정보.
 *
 * 전체 메시지 이력을 로드하지 않고 메타데이터만 제공한다.
 *
 * @param sessionId 고유 세션 식별자
 * @param messageCount 세션 내 총 메시지 수
 * @param lastActivity 가장 최근 메시지의 시각
 * @param preview 첫 번째 사용자 메시지의 잘린 미리보기 (표시용)
 */
data class SessionSummary(
    val sessionId: String,
    val messageCount: Int,
    val lastActivity: Instant,
    val preview: String
)

/**
 * 페이지네이션된 세션 조회 결과.
 *
 * @param items 현재 페이지의 세션 요약 목록
 * @param total 페이지네이션 전 전체 세션 수
 */
data class PaginatedSessionResult(
    val items: List<SessionSummary>,
    val total: Int
)

/**
 * 인메모리 대화 메모리 구현체.
 *
 * 메시지를 가변 목록에 저장하는 단순한 메모리 구현체.
 * 최대 한도 초과 시 가장 오래된 메시지를 자동 퇴거한다.
 *
 * ## 특성
 * - ReentrantReadWriteLock으로 스레드 안전성 보장
 * - 가득 차면 FIFO 퇴거
 * - 토큰 인식 이력 잘림
 * - 비영속 (재시작 시 유실)
 *
 * @param maxMessages 유지할 최대 메시지 수 (기본값: 50)
 */
class InMemoryConversationMemory(
    private val maxMessages: Int = 50,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : ConversationMemory {

    private val messages = mutableListOf<Message>()
    /** 읽기/쓰기 분리 락. 다수의 동시 읽기를 허용하면서 쓰기는 배타적으로 보호. */
    private val lock = ReentrantReadWriteLock()

    override fun add(message: Message) = lock.write {
        messages.add(message)
        // 최대 한도 초과 시 가장 오래된 메시지부터 퇴거
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

    /**
     * 토큰 예산 내의 최근 메시지를 반환한다.
     * 역순(최신→오래된)으로 순회하며 토큰을 누적하고,
     * 예산을 초과하면 중단한다.
     */
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

        result.reversed()  // 시간순으로 복원
    }
}

/**
 * 인메모리 세션 기반 메모리 스토어 구현체.
 *
 * ConcurrentHashMap으로 스레드 안전한 동시 접근을 지원한다.
 *
 * ## 특성
 * - 세션 한도 도달 시 LRU 퇴거 (Caffeine 캐시)
 * - 스레드 안전한 동시 접근
 * - 비영속 (재시작 시 유실)
 *
 * ## 프로덕션 고려사항
 * 다음으로 MemoryStore를 구현하는 것을 고려하라:
 * - Redis: 분산 세션
 * - PostgreSQL: 영속성
 * - 커스텀 TTL: 자동 정리
 *
 * @param maxSessions 최대 동시 세션 수 (기본값: 1000)
 */
class InMemoryMemoryStore(
    private val maxSessions: Int = 1000
) : MemoryStore {

    /** 세션 ID → 소유자 userId 매핑 */
    private val sessionOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Caffeine 캐시를 사용하여 LRU 퇴거와 최대 크기 제한을 적용.
     * 세션 퇴거 시 소유자 매핑도 함께 정리한다.
     */
    private val sessions = Caffeine.newBuilder()
        .maximumSize(maxSessions.toLong())
        .removalListener<String, ConversationMemory> { key, _, _ ->
            if (key != null) sessionOwners.remove(key)
        }
        .build<String, ConversationMemory>()

    override fun get(sessionId: String): ConversationMemory? = sessions.getIfPresent(sessionId)

    override fun getOrCreate(sessionId: String): ConversationMemory {
        val memory = sessions.get(sessionId) { InMemoryConversationMemory() }
        sessions.cleanUp() // maximumSize 퇴거가 즉시 적용되도록 한다
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
        // 첫 메시지에서 세션 소유자를 기록 (putIfAbsent로 덮어쓰기 방지)
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

/** 미리보기의 최대 문자 수 */
internal const val PREVIEW_MAX_LENGTH = 50

/**
 * 대화 이력에서 미리보기 문자열을 추출한다.
 * 첫 번째 사용자 메시지를 [PREVIEW_MAX_LENGTH]자로 잘라 사용한다.
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

// ConversationMemory 확장 함수

/**
 * 대화에 사용자 메시지를 추가한다.
 *
 * @param content 메시지 내용
 */
fun ConversationMemory.addUserMessage(content: String) {
    add(Message(MessageRole.USER, content))
}

/**
 * 대화에 어시스턴트 메시지를 추가한다.
 *
 * @param content 메시지 내용
 */
fun ConversationMemory.addAssistantMessage(content: String) {
    add(Message(MessageRole.ASSISTANT, content))
}

/**
 * 대화에 시스템 메시지를 추가한다.
 *
 * @param content 메시지 내용
 */
fun ConversationMemory.addSystemMessage(content: String) {
    add(Message(MessageRole.SYSTEM, content))
}
