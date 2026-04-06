package com.arc.reactor.settings

import mu.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 런타임 설정 — Admin이 DB로 기능 토글/파라미터를 실시간 변경할 수 있다.
 *
 * Redis 캐시(30초 TTL)로 DB 부하를 최소화한다.
 * Redis 장애 시 DB 직접 조회로 폴백한다.
 * DB에 값이 없으면 기존 환경변수/기본값이 그대로 사용된다 (하위 호환).
 *
 * Redis 키 패턴: arc:settings:{key}
 */
class RuntimeSettingsService(
    private val jdbc: JdbcTemplate,
    private val redisTemplate: StringRedisTemplate? = null,
    private val cacheTtlSeconds: Long = 30
) {
    private val cacheTtl = Duration.ofSeconds(cacheTtlSeconds)

    /** 문자열 설정을 가져온다. DB에 없으면 default 반환. */
    fun getString(key: String, default: String): String {
        return getFromCache(key) ?: default
    }

    /** 불리언 설정을 가져온다. */
    fun getBoolean(key: String, default: Boolean): Boolean {
        val value = getFromCache(key) ?: return default
        return value.equals("true", ignoreCase = true)
    }

    /** 정수 설정을 가져온다. */
    fun getInt(key: String, default: Int): Int {
        val value = getFromCache(key) ?: return default
        return value.toIntOrNull() ?: default
    }

    /** 실수 설정을 가져온다. */
    fun getDouble(key: String, default: Double): Double {
        val value = getFromCache(key) ?: return default
        return value.toDoubleOrNull() ?: default
    }

    /** 설정을 저장/갱신한다. */
    fun set(
        key: String, value: String, type: String = "STRING", category: String = "general",
        description: String? = null, updatedBy: String? = null
    ) {
        jdbc.update(
            """INSERT INTO runtime_settings (key, value, type, category, description, updated_by, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (key) DO UPDATE SET
                 value = EXCLUDED.value,
                 type = EXCLUDED.type,
                 category = EXCLUDED.category,
                 description = COALESCE(EXCLUDED.description, runtime_settings.description),
                 updated_by = EXCLUDED.updated_by,
                 updated_at = EXCLUDED.updated_at""",
            key, value, type, category, description, updatedBy,
            java.sql.Timestamp.from(Instant.now())
        )
        invalidateCache(key)
        logger.info { "런타임 설정 변경: key=$key, value=$value, by=$updatedBy" }
    }

    /** 설정을 삭제한다 (기본값으로 리셋). */
    fun delete(key: String) {
        jdbc.update("DELETE FROM runtime_settings WHERE key = ?", key)
        invalidateCache(key)
    }

    /** 전체 설정 목록을 조회한다. */
    fun list(): List<RuntimeSetting> {
        return jdbc.query(
            "SELECT * FROM runtime_settings ORDER BY category, key"
        ) { rs, _ ->
            RuntimeSetting(
                key = rs.getString("key"),
                value = rs.getString("value"),
                type = rs.getString("type"),
                category = rs.getString("category"),
                description = rs.getString("description"),
                updatedBy = rs.getString("updated_by"),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }
    }

    /** 캐시를 전체 무효화한다. */
    fun refreshCache() {
        try {
            val redis = redisTemplate ?: return
            redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("$KEY_PREFIX*").build()).use { cursor ->
                val keys = cursor.asSequence().toList()
                if (keys.isNotEmpty()) redis.delete(keys)
            }
            logger.info { "런타임 설정 Redis 캐시 전체 무효화" }
        } catch (e: Exception) {
            logger.warn(e) { "Redis 캐시 무효화 실패 — 무시" }
        }
    }

    private fun getFromCache(key: String): String? {
        // 1. Redis 캐시 조회
        try {
            val redis = redisTemplate
            if (redis != null) {
                val redisKey = "$KEY_PREFIX$key"
                val cached = redis.opsForValue().get(redisKey)
                if (cached != null) return if (cached == NULL_MARKER) null else cached
            }
        } catch (e: Exception) {
            logger.debug { "Redis 조회 실패, DB 폴백: key=$key" }
        }

        // 2. DB 조회
        val dbValue = try {
            jdbc.queryForObject(
                "SELECT value FROM runtime_settings WHERE key = ?",
                String::class.java, key
            )
        } catch (_: Exception) {
            null
        }

        // 3. Redis에 캐시 (null도 캐시하여 DB 히트 방지)
        try {
            redisTemplate?.opsForValue()?.set(
                "$KEY_PREFIX$key",
                dbValue ?: NULL_MARKER,
                cacheTtl
            )
        } catch (e: Exception) {
            logger.debug { "Redis 캐시 저장 실패: key=$key" }
        }

        return dbValue
    }

    private fun invalidateCache(key: String) {
        try {
            redisTemplate?.delete("$KEY_PREFIX$key")
        } catch (e: Exception) {
            logger.debug { "Redis 캐시 무효화 실패: key=$key" }
        }
    }

    companion object {
        /** Redis 키 접두사 (규격: arc:{domain}:{purpose}) */
        private const val KEY_PREFIX = "arc:settings:"
        /** null 값 캐시 마커 (DB에 키가 없음을 캐시) */
        private const val NULL_MARKER = "__NULL__"
    }
}

/** 런타임 설정 항목 */
data class RuntimeSetting(
    val key: String,
    val value: String,
    val type: String = "STRING",
    val category: String = "general",
    val description: String? = null,
    val updatedBy: String? = null,
    val updatedAt: Instant = Instant.now()
)
