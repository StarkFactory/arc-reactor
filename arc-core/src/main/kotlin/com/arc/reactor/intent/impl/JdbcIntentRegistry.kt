package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 인텐트 레지스트리 — 영속적 인텐트 저장.
 *
 * 인텐트 정의를 `intent_definitions` 테이블에 저장한다.
 *
 * ## 테이블 스키마
 * ```sql
 * CREATE TABLE intent_definitions (
 *     name         VARCHAR(100) PRIMARY KEY,
 *     description  TEXT NOT NULL,
 *     examples     TEXT DEFAULT '[]',
 *     keywords     TEXT DEFAULT '[]',
 *     profile      TEXT DEFAULT '{}',
 *     enabled      BOOLEAN DEFAULT TRUE,
 *     created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
 * );
 * ```
 *
 * WHY: 운영 환경에서 인텐트 정의를 서버 재시작 이후에도 유지하기 위해
 * PostgreSQL에 영속 저장한다. 스냅샷 캐싱으로 빈번한 DB 조회를 방지한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see com.arc.reactor.intent.InMemoryIntentRegistry 인메모리 대안
 */
class JdbcIntentRegistry(
    private val jdbcTemplate: JdbcTemplate
) : IntentRegistry {

    /**
     * 메모리 내 스냅샷 캐시.
     * WHY: 인텐트 분류가 매 요청마다 실행되므로 DB 쿼리를 매번 하면 성능이 저하된다.
     * @Volatile로 선언하여 다른 스레드에서의 무효화가 즉시 보이도록 한다.
     */
    @Volatile
    private var snapshot: RegistrySnapshot? = null

    override fun list(): List<IntentDefinition> {
        return currentSnapshot().all
    }

    override fun listEnabled(): List<IntentDefinition> {
        return currentSnapshot().enabled
    }

    override fun get(intentName: String): IntentDefinition? {
        return currentSnapshot().byName[intentName]
    }

    /**
     * 인텐트를 저장한다. 기존 항목이 있으면 UPDATE, 없으면 INSERT를 수행한다.
     * 저장 후 스냅샷 캐시를 무효화하여 다음 조회 시 최신 데이터를 로딩한다.
     */
    override fun save(intent: IntentDefinition): IntentDefinition {
        val existing = queryByName(intent.name)
        val now = Instant.now()

        if (existing != null) {
            jdbcTemplate.update(
                """UPDATE intent_definitions
                   SET description = ?, examples = ?, keywords = ?, synonyms = ?,
                       keyword_weights = ?, negative_keywords = ?, profile = ?,
                       enabled = ?, updated_at = ?
                   WHERE name = ?""",
                intent.description,
                objectMapper.writeValueAsString(intent.examples),
                objectMapper.writeValueAsString(intent.keywords),
                objectMapper.writeValueAsString(intent.synonyms),
                objectMapper.writeValueAsString(intent.keywordWeights),
                objectMapper.writeValueAsString(intent.negativeKeywords),
                objectMapper.writeValueAsString(intent.profile),
                intent.enabled,
                java.sql.Timestamp.from(now),
                intent.name
            )
            invalidateSnapshot()
            return intent.copy(createdAt = existing.createdAt, updatedAt = now)
        }

        jdbcTemplate.update(
            """INSERT INTO intent_definitions
               (name, description, examples, keywords, synonyms, keyword_weights,
                negative_keywords, profile, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            intent.name,
            intent.description,
            objectMapper.writeValueAsString(intent.examples),
            objectMapper.writeValueAsString(intent.keywords),
            objectMapper.writeValueAsString(intent.synonyms),
            objectMapper.writeValueAsString(intent.keywordWeights),
            objectMapper.writeValueAsString(intent.negativeKeywords),
            objectMapper.writeValueAsString(intent.profile),
            intent.enabled,
            java.sql.Timestamp.from(intent.createdAt),
            java.sql.Timestamp.from(intent.updatedAt)
        )
        invalidateSnapshot()
        return intent
    }

    override fun delete(intentName: String) {
        jdbcTemplate.update("DELETE FROM intent_definitions WHERE name = ?", intentName)
        invalidateSnapshot()
    }

    /**
     * 현재 스냅샷을 반환한다. 캐시 미스 시 DB에서 로딩한다.
     * synchronized로 동시 로딩을 방지한다.
     */
    private fun currentSnapshot(): RegistrySnapshot {
        snapshot?.let { return it }
        return synchronized(this) {
            snapshot ?: loadSnapshot().also { snapshot = it }
        }
    }

    /** DB에서 전체 인텐트를 로딩하여 스냅샷을 구성한다. */
    private fun loadSnapshot(): RegistrySnapshot {
        val intents = jdbcTemplate.query(SELECT_ALL, ROW_MAPPER)
        return RegistrySnapshot(
            all = intents,
            enabled = intents.filter { it.enabled },
            byName = intents.associateBy { it.name }
        )
    }

    /** 이름으로 단일 인텐트를 직접 DB에서 조회한다 (캐시 우회). */
    private fun queryByName(intentName: String): IntentDefinition? {
        val results = jdbcTemplate.query(
            "$BASE_SELECT WHERE name = ?",
            ROW_MAPPER,
            intentName
        )
        return results.firstOrNull()
    }

    /** 스냅샷 캐시를 무효화한다. 다음 조회 시 DB에서 다시 로딩한다. */
    private fun invalidateSnapshot() {
        snapshot = null
    }

    companion object {
        private const val BASE_SELECT =
            "SELECT name, description, examples, keywords, synonyms, keyword_weights, " +
                "negative_keywords, profile, enabled, created_at, updated_at " +
                "FROM intent_definitions"

        private const val SELECT_ALL = "$BASE_SELECT ORDER BY name ASC"

        private val objectMapper = jacksonObjectMapper()

        /** ResultSet 행을 IntentDefinition으로 변환하는 RowMapper */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            IntentDefinition(
                name = rs.getString("name"),
                description = rs.getString("description"),
                examples = parseJsonList(rs.getString("examples")),
                keywords = parseJsonList(rs.getString("keywords")),
                synonyms = parseJsonMap(rs.getString("synonyms")),
                keywordWeights = parseJsonDoubleMap(rs.getString("keyword_weights")),
                negativeKeywords = parseJsonList(rs.getString("negative_keywords")),
                profile = parseProfile(rs.getString("profile")),
                enabled = rs.getBoolean("enabled"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }

        /** JSON 문자열을 문자열 리스트로 파싱. 실패 시 빈 리스트 반환. */
        private fun parseJsonList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "JSON 리스트 파싱 실패: $json" }
                emptyList()
            }
        }

        /** JSON 문자열을 Map<String, List<String>>으로 파싱. 실패 시 빈 맵 반환. */
        private fun parseJsonMap(json: String?): Map<String, List<String>> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "JSON 맵 파싱 실패: $json" }
                emptyMap()
            }
        }

        /** JSON 문자열을 Map<String, Double>로 파싱. 실패 시 빈 맵 반환. */
        private fun parseJsonDoubleMap(json: String?): Map<String, Double> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "JSON double 맵 파싱 실패: $json" }
                emptyMap()
            }
        }

        /** JSON 문자열을 IntentProfile로 파싱. 실패 시 기본 프로필 반환. */
        private fun parseProfile(json: String?): IntentProfile {
            if (json.isNullOrBlank()) return IntentProfile()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "인텐트 프로필 파싱 실패: $json" }
                IntentProfile()
            }
        }
    }

    /**
     * 레지스트리 스냅샷 — DB 데이터의 인메모리 캐시.
     * 전체 목록, 활성 목록, 이름별 조회를 모두 사전 계산하여 저장한다.
     */
    private data class RegistrySnapshot(
        val all: List<IntentDefinition>,
        val enabled: List<IntentDefinition>,
        val byName: Map<String, IntentDefinition>
    )
}
