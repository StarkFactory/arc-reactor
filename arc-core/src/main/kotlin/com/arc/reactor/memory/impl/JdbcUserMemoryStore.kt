package com.arc.reactor.memory.impl

import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * [UserMemoryStore]의 JDBC 기반 구현체.
 *
 * 사용자 기억을 관계형 데이터베이스의 `user_memories` 테이블에 영구 저장한다.
 * Facts와 Preferences는 유연성을 위해 JSON 텍스트 칼럼으로 직렬화된다.
 * 최근 토픽은 줄바꿈으로 구분된 목록으로 저장된다.
 *
 * ## 테이블 DDL (PostgreSQL / H2 호환)
 * ```sql
 * CREATE TABLE IF NOT EXISTS user_memories (
 *     user_id       VARCHAR(255) PRIMARY KEY,
 *     facts         TEXT         NOT NULL DEFAULT '{}',
 *     preferences   TEXT         NOT NULL DEFAULT '{}',
 *     recent_topics TEXT         NOT NULL DEFAULT '',
 *     updated_at    TIMESTAMP    NOT NULL
 * );
 * ```
 *
 * [autoCreateTable]이 true(기본값)이면 첫 쓰기 시 자동 생성된다.
 */
class JdbcUserMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val tableName: String = "user_memories",
    private val autoCreateTable: Boolean = true
) : UserMemoryStore {

    private val objectMapper = jacksonObjectMapper()
    @Volatile
    private var tableInitialized = false

    init {
        if (autoCreateTable) {
            initTable()
        }
    }

    override suspend fun get(userId: String): UserMemory? {
        return try {
            withContext(Dispatchers.IO) {
                val results = jdbcTemplate.query(
                    "SELECT user_id, facts, preferences, recent_topics, updated_at FROM $tableName WHERE user_id = ?",
                    { rs: ResultSet, _: Int -> rs.toUserMemory() },
                    userId
                )
                results.firstOrNull()
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to load user memory for $userId" }
            null
        }
    }

    /**
     * 사용자 기억을 저장한다. ON CONFLICT로 upsert 시맨틱을 적용한다.
     * 이미 존재하는 사용자면 전체 필드를 갱신한다.
     */
    override suspend fun save(userId: String, memory: UserMemory) {
        val factsJson = objectMapper.writeValueAsString(memory.facts)
        val prefsJson = objectMapper.writeValueAsString(memory.preferences)
        val topicsText = memory.recentTopics.joinToString("\n")
        val now = Timestamp.from(Instant.now())

        withContext(Dispatchers.IO) {
            jdbcTemplate.update(
                """
                INSERT INTO $tableName (user_id, facts, preferences, recent_topics, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET facts = EXCLUDED.facts,
                    preferences = EXCLUDED.preferences,
                    recent_topics = EXCLUDED.recent_topics,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                userId, factsJson, prefsJson, topicsText, now
            )
        }
    }

    override suspend fun delete(userId: String) {
        withContext(Dispatchers.IO) {
            jdbcTemplate.update("DELETE FROM $tableName WHERE user_id = ?", userId)
        }
    }

    /** 팩트를 읽기-수정-쓰기로 업데이트한다. 기존 기억이 없으면 새로 생성. */
    override suspend fun updateFact(userId: String, key: String, value: String) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        save(userId, existing.copy(facts = existing.facts + (key to value), updatedAt = Instant.now()))
    }

    /** 선호도를 읽기-수정-쓰기로 업데이트한다. 기존 기억이 없으면 새로 생성. */
    override suspend fun updatePreference(userId: String, key: String, value: String) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        save(userId, existing.copy(preferences = existing.preferences + (key to value), updatedAt = Instant.now()))
    }

    /** 최근 토픽을 추가하고 takeLast로 최대 수를 제한한다. */
    override suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int) {
        val existing = get(userId) ?: UserMemory(userId = userId)
        val updated = (existing.recentTopics + topic).takeLast(maxTopics)
        save(userId, existing.copy(recentTopics = updated, updatedAt = Instant.now()))
    }

    /** ResultSet을 UserMemory로 매핑한다. JSON 파싱 실패에 안전. */
    private fun ResultSet.toUserMemory(): UserMemory {
        val factsJson = getString("facts").orEmpty().ifBlank { "{}" }
        val prefsJson = getString("preferences").orEmpty().ifBlank { "{}" }
        val topicsText = getString("recent_topics").orEmpty()
        val topics = if (topicsText.isBlank()) emptyList() else topicsText.split("\n").filter { it.isNotBlank() }

        return UserMemory(
            userId = getString("user_id"),
            facts = objectMapper.readValue(factsJson),
            preferences = objectMapper.readValue(prefsJson),
            recentTopics = topics,
            updatedAt = getTimestamp("updated_at")?.toInstant() ?: Instant.now()
        )
    }

    /** 테이블이 존재하지 않으면 자동으로 생성한다. 실패 시 경고 로그만 남긴다. */
    private fun initTable() {
        if (tableInitialized) return
        try {
            jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    user_id       VARCHAR(255) PRIMARY KEY,
                    facts         TEXT         NOT NULL DEFAULT '{}',
                    preferences   TEXT         NOT NULL DEFAULT '{}',
                    recent_topics TEXT         NOT NULL DEFAULT '',
                    updated_at    TIMESTAMP    NOT NULL
                )
                """.trimIndent()
            )
            tableInitialized = true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to auto-create $tableName table. Please create it manually." }
        }
    }
}
