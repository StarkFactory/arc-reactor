package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 영구 대화 저장소
 *
 * 관계형 데이터베이스(PostgreSQL, H2 등)에 대화 이력을 저장한다.
 * `conversation_messages` 테이블이 필요하다 — Flyway migration V1 참조.
 *
 * ## 주요 기능
 * - 서버 재시작 후에도 영속성 유지
 * - 세션별 최대 메시지 제한 (FIFO 퇴거)
 * - TTL 기반 세션 정리
 * - 데이터베이스 트랜잭션을 통한 스레드 안전성
 *
 * ## 사용 방법
 * `spring-boot-starter-jdbc`와 DataSource가 classpath에 존재하면
 * [ArcReactorAutoConfiguration]에 의해 자동 설정된다.
 *
 * @see MemoryStore 인터페이스 계약
 * @see ConversationMemory 세션별 메모리 인터페이스
 */
class JdbcMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val maxMessagesPerSession: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator(),
    /**
     * R276: addMessage의 INSERT + evictOldMessages DELETE를 단일 트랜잭션으로 묶기 위한
     * TransactionTemplate. null이면 backward-compat을 위해 트랜잭션 없이 실행
     * (테스트나 단순 환경용). 운영 환경에서는 [JdbcMemoryStoreConfiguration]에서 주입한다.
     */
    private val transactionTemplate: TransactionTemplate? = null
) : MemoryStore {

    override fun get(sessionId: String): ConversationMemory? {
        val messages = loadMessages(sessionId)
        return if (messages.isEmpty()) null else JdbcConversationMemory(sessionId, messages)
    }

    override fun getOrCreate(sessionId: String): ConversationMemory {
        val messages = loadMessages(sessionId)
        return JdbcConversationMemory(sessionId, messages)
    }

    override fun remove(sessionId: String) {
        jdbcTemplate.update("DELETE FROM conversation_messages WHERE session_id = ?", sessionId)
    }

    override fun clear() {
        jdbcTemplate.update("DELETE FROM conversation_messages")
    }

    override fun addMessage(sessionId: String, role: String, content: String) {
        // R276: INSERT + evictOldMessages DELETE를 단일 트랜잭션으로 묶어 atomicity 보장.
        // 이전에는 두 statement가 별개 autocommit으로 실행되어 INSERT 후 DELETE 사이에
        // 다른 connection이 동일 세션에 INSERT를 수행하는 race window가 존재했다.
        // PostgreSQL 같은 고동시성 환경에서는 evict 결과가 의도와 다를 수 있다.
        executeInTransaction {
            jdbcTemplate.update(
                "INSERT INTO conversation_messages (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                sessionId, role, content, Instant.now().toEpochMilli()
            )
            // 세션당 최대 메시지 수 초과 시 FIFO 퇴거
            evictOldMessages(sessionId)
        }
    }

    override fun addMessage(sessionId: String, role: String, content: String, userId: String) {
        // R276: INSERT + evictOldMessages DELETE를 단일 트랜잭션으로 묶어 atomicity 보장.
        executeInTransaction {
            jdbcTemplate.update(
                "INSERT INTO conversation_messages (session_id, role, content, timestamp, user_id) VALUES (?, ?, ?, ?, ?)",
                sessionId, role, content, Instant.now().toEpochMilli(), userId
            )
            // 세션당 최대 메시지 수 초과 시 FIFO 퇴거
            evictOldMessages(sessionId)
        }
    }

    /**
     * R276: TransactionTemplate이 제공되면 트랜잭션 안에서 실행하고, 없으면 직접 실행.
     * `transactionTemplate.execute`는 콜백 결과를 반환하지만 여기서는 Unit이라 무시.
     */
    private inline fun executeInTransaction(crossinline block: () -> Unit) {
        if (transactionTemplate != null) {
            transactionTemplate.execute {
                block()
                null
            }
        } else {
            block()
        }
    }

    override fun listSessions(): List<SessionSummary> {
        return jdbcTemplate.query(
            """
            SELECT s.session_id, s.message_count, s.last_activity,
                   (SELECT m.content FROM conversation_messages m
                    WHERE m.session_id = s.session_id AND m.role = 'user'
                    ORDER BY m.id ASC LIMIT 1) AS preview
            FROM (SELECT session_id, COUNT(*) AS message_count,
                         MAX(timestamp) AS last_activity
                  FROM conversation_messages
                  GROUP BY session_id) s
            ORDER BY s.last_activity DESC
            """.trimIndent()
        ) { rs: ResultSet, _: Int ->
            mapSessionSummary(rs)
        }
    }

    override fun listSessionsByUserId(userId: String): List<SessionSummary> {
        return jdbcTemplate.query(
            """
            SELECT s.session_id, s.message_count, s.last_activity,
                   (SELECT m.content FROM conversation_messages m
                    WHERE m.session_id = s.session_id AND m.role = 'user'
                    ORDER BY m.id ASC LIMIT 1) AS preview
            FROM (SELECT session_id, COUNT(*) AS message_count,
                         MAX(timestamp) AS last_activity
                  FROM conversation_messages
                  WHERE user_id = ?
                  GROUP BY session_id) s
            ORDER BY s.last_activity DESC
            """.trimIndent(),
            { rs: ResultSet, _: Int -> mapSessionSummary(rs) },
            userId
        )
    }

    override fun listSessionsByUserIdPaginated(
        userId: String,
        limit: Int,
        offset: Int
    ): PaginatedSessionResult {
        val total = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(DISTINCT session_id) FROM conversation_messages WHERE user_id = ?
            """.trimIndent(),
            Int::class.java,
            userId
        ) ?: 0

        val items = jdbcTemplate.query(
            """
            SELECT s.session_id, s.message_count, s.last_activity,
                   (SELECT m.content FROM conversation_messages m
                    WHERE m.session_id = s.session_id AND m.role = 'user'
                    ORDER BY m.id ASC LIMIT 1) AS preview
            FROM (SELECT session_id, COUNT(*) AS message_count,
                         MAX(timestamp) AS last_activity
                  FROM conversation_messages
                  WHERE user_id = ?
                  GROUP BY session_id) s
            ORDER BY s.last_activity DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs: ResultSet, _: Int -> mapSessionSummary(rs) },
            userId, limit, offset
        )

        return PaginatedSessionResult(items = items, total = total)
    }

    override fun getSessionOwner(sessionId: String): String? {
        return jdbcTemplate.query(
            "SELECT DISTINCT user_id FROM conversation_messages WHERE session_id = ? LIMIT 1",
            { rs: ResultSet, _: Int -> rs.getString("user_id") },
            sessionId
        ).firstOrNull()
    }

    private fun mapSessionSummary(rs: ResultSet): SessionSummary {
        val rawPreview = rs.getString("preview")
        return SessionSummary(
            sessionId = rs.getString("session_id"),
            messageCount = rs.getInt("message_count"),
            lastActivity = Instant.ofEpochMilli(rs.getLong("last_activity")),
            preview = truncatePreview(rawPreview)
        )
    }

    private fun truncatePreview(content: String?): String {
        if (content == null) return "Empty conversation"
        return if (content.length > PREVIEW_MAX_LENGTH) {
            content.take(PREVIEW_MAX_LENGTH) + "..."
        } else {
            content
        }
    }

    /**
     * 지정된 TTL보다 오래 비활성 상태인 세션을 일괄 삭제한다.
     *
     * R320 fix: 이전 구현은 단일 `DELETE ... WHERE session_id IN (?, ?, ...)`로
     * 만료 세션을 전부 바인딩했다. 수천 세션이 쌓이면 JDBC 드라이버의 bind parameter
     * 상한(드라이버별 1000~32,767)을 초과할 위험이 있고, PostgreSQL의 parse tree가
     * 거대해져 plan 품질이 저하된다. [CLEANUP_CHUNK_SIZE] 단위로 청크 DELETE하여
     * bind parameter 폭주와 쿼리 파싱 오버헤드를 제한한다.
     *
     * @param ttlMs 밀리초 단위 TTL
     * @return 삭제된 세션 수
     */
    fun cleanupExpiredSessions(ttlMs: Long): Int {
        val cutoff = Instant.now().toEpochMilli() - ttlMs
        val expiredSessions = jdbcTemplate.queryForList(
            """
            SELECT session_id FROM conversation_messages
            GROUP BY session_id
            HAVING MAX(timestamp) < ?
            """.trimIndent(),
            String::class.java,
            cutoff
        )

        if (expiredSessions.isEmpty()) return 0

        // R320 fix: CLEANUP_CHUNK_SIZE 단위로 청크 DELETE 수행
        var totalDeleted = 0
        expiredSessions.chunked(CLEANUP_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val deleted = jdbcTemplate.update(
                "DELETE FROM conversation_messages WHERE session_id IN ($placeholders)",
                *chunk.toTypedArray()
            )
            totalDeleted += deleted
        }
        logger.info {
            "만료 세션 ${expiredSessions.size}개 일괄 삭제 완료 " +
                "(chunk_size=$CLEANUP_CHUNK_SIZE, chunks=${(expiredSessions.size + CLEANUP_CHUNK_SIZE - 1) / CLEANUP_CHUNK_SIZE})"
        }
        return expiredSessions.size
    }

    private fun loadMessages(sessionId: String): List<Message> {
        return jdbcTemplate.query(
            "SELECT role, content, timestamp FROM conversation_messages WHERE session_id = ? ORDER BY id ASC",
            { rs: ResultSet, _: Int ->
                Message(
                    role = parseRole(rs.getString("role")),
                    content = rs.getString("content"),
                    timestamp = Instant.ofEpochMilli(rs.getLong("timestamp"))
                )
            },
            sessionId
        )
    }

    private fun evictOldMessages(sessionId: String) {
        jdbcTemplate.update(
            """
            DELETE FROM conversation_messages
            WHERE session_id = ?
              AND id NOT IN (
                  SELECT id FROM conversation_messages
                  WHERE session_id = ?
                  ORDER BY id DESC
                  LIMIT ?
              )
            """.trimIndent(),
            sessionId, sessionId, maxMessagesPerSession
        )
    }

    private fun parseRole(role: String): MessageRole {
        return when (role.lowercase()) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            "tool" -> MessageRole.TOOL
            else -> MessageRole.USER
        }
    }

    /**
     * 세션별 JDBC 기반 대화의 읽기 전용 뷰.
     */
    private inner class JdbcConversationMemory(
        private val sessionId: String,
        private val cachedMessages: List<Message>
    ) : ConversationMemory {

        override fun add(message: Message) {
            addMessage(sessionId, message.role.name.lowercase(), message.content)
        }

        override fun getHistory(): List<Message> = cachedMessages

        override fun clear() {
            remove(sessionId)
        }

        override fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message> {
            var totalTokens = 0
            val result = mutableListOf<Message>()

            for (message in cachedMessages.reversed()) {
                val tokens = tokenEstimator.estimate(message.content)
                if (totalTokens + tokens > maxTokens) break
                result.add(message)
                totalTokens += tokens
            }

            return result.reversed()
        }
    }
}
