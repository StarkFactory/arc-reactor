package com.arc.reactor.auth

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant

/**
 * 폐기된 JWT 토큰 저장소 인터페이스
 *
 * 폐기된 JWT 토큰 ID(`jti`)를 원래 만료 시각까지 저장한다.
 * 토큰이 만료되면 자연적으로 무효화되므로 저장할 필요가 없다.
 *
 * @see InMemoryTokenRevocationStore 메모리 기반 구현체 (단일 인스턴스용)
 * @see JdbcTokenRevocationStore JDBC 기반 구현체 (다중 인스턴스용)
 * @see RedisTokenRevocationStore Redis 기반 구현체 (다중 인스턴스용, 자동 만료)
 */
interface TokenRevocationStore {
    /**
     * 토큰을 폐기한다.
     *
     * @param tokenId JWT 토큰 ID (jti claim)
     * @param expiresAt 토큰의 원래 만료 시각 (이 시각까지만 저장)
     */
    fun revoke(tokenId: String, expiresAt: Instant)

    /**
     * 토큰이 폐기되었는지 확인한다.
     *
     * @param tokenId JWT 토큰 ID (jti claim)
     * @return 폐기된 경우 true
     */
    fun isRevoked(tokenId: String): Boolean
}

/**
 * 메모리 기반 폐기 토큰 저장소
 *
 * Caffeine 캐시를 사용하여 스레드 안전하게 폐기된 토큰을 관리한다.
 * 최대 10,000개 엔트리를 유지하며, 토큰의 원래 만료 시각에 맞춰 자동 퇴출된다.
 *
 * 서버 재시작 시 폐기 정보가 유실되므로 단일 인스턴스 환경에만 적합하다.
 *
 * @see JdbcTokenRevocationStore 다중 인스턴스 환경용 JDBC 구현체
 * @see RedisTokenRevocationStore 다중 인스턴스 환경용 Redis 구현체
 */
class InMemoryTokenRevocationStore : TokenRevocationStore {

    /**
     * tokenId → 만료 시각 매핑.
     * 엔트리별 TTL을 토큰의 원래 만료 시각으로 설정하여 만료 시 자동 퇴출한다.
     */
    private val revoked = Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES.toLong())
        .expireAfter(object : Expiry<String, Instant> {
            override fun expireAfterCreate(key: String, value: Instant, currentTime: Long): Long {
                val ttl = Duration.between(Instant.now(), value)
                return if (ttl.isNegative) 0L else ttl.toNanos()
            }

            override fun expireAfterUpdate(
                key: String, value: Instant, currentTime: Long, currentDuration: Long
            ): Long = currentDuration

            override fun expireAfterRead(
                key: String, value: Instant, currentTime: Long, currentDuration: Long
            ): Long = currentDuration
        })
        .build<String, Instant>()

    override fun revoke(tokenId: String, expiresAt: Instant) {
        // 이미 만료된 토큰은 저장할 필요 없음
        if (expiresAt <= Instant.now()) return
        revoked.put(tokenId, expiresAt)
    }

    override fun isRevoked(tokenId: String): Boolean {
        return revoked.getIfPresent(tokenId) != null
    }

    /** 만료된 모든 엔트리를 정리한다 */
    internal fun purgeExpired() {
        revoked.cleanUp()
    }

    /** 현재 저장된 엔트리 수 (테스트용) */
    internal fun size(): Int = revoked.estimatedSize().toInt()

    companion object {
        private const val MAX_ENTRIES = 10_000
    }
}

/**
 * JDBC 기반 폐기 토큰 저장소
 *
 * 다중 인스턴스 배포에서 모든 인스턴스가 동일한 폐기 정보를 공유한다.
 * Flyway 마이그레이션 `V31__create_token_revocations.sql`이 필요하다.
 *
 * @param jdbcTemplate Spring JdbcTemplate
 *
 * @see InMemoryTokenRevocationStore 단일 인스턴스용 대안
 */
class JdbcTokenRevocationStore(
    private val jdbcTemplate: JdbcTemplate
) : TokenRevocationStore {

    override fun revoke(tokenId: String, expiresAt: Instant) {
        if (expiresAt <= Instant.now()) {
            return
        }
        // UPSERT: 기존 레코드가 있으면 업데이트, 없으면 삽입
        val updated = jdbcTemplate.update(
            "UPDATE auth_token_revocations SET expires_at = ?, revoked_at = ? WHERE token_id = ?",
            java.sql.Timestamp.from(expiresAt),
            java.sql.Timestamp.from(Instant.now()),
            tokenId
        )
        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO auth_token_revocations (token_id, expires_at, revoked_at) VALUES (?, ?, ?)",
                tokenId,
                java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(Instant.now())
            )
        }
    }

    override fun isRevoked(tokenId: String): Boolean {
        val expiresAt = jdbcTemplate.query(
            "SELECT expires_at FROM auth_token_revocations WHERE token_id = ?",
            { rs, _ -> rs.getTimestamp("expires_at").toInstant() },
            tokenId
        ).firstOrNull() ?: return false

        // 만료 시각이 지났으면 레코드 삭제 후 false 반환
        if (expiresAt <= Instant.now()) {
            jdbcTemplate.update("DELETE FROM auth_token_revocations WHERE token_id = ?", tokenId)
            return false
        }
        return true
    }
}

/**
 * Redis 기반 폐기 토큰 저장소
 *
 * Redis의 TTL 기능을 활용하여 토큰 만료 시 자동으로 키가 삭제된다.
 * 다중 인스턴스 배포에 적합하다.
 *
 * @param redisTemplate Spring Redis 템플릿
 * @param keyPrefix Redis 키 접두사 (기본값: "arc:auth:revoked")
 *
 * @see InMemoryTokenRevocationStore 단일 인스턴스용 대안
 * @see JdbcTokenRevocationStore JDBC 기반 대안
 */
class RedisTokenRevocationStore(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String = "arc:auth:revoked"
) : TokenRevocationStore {

    init {
        require(keyPrefix.isNotBlank()) { "keyPrefix는 비어있을 수 없다" }
    }

    override fun revoke(tokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(), expiresAt)
        // TTL이 0 이하이면 이미 만료된 토큰이므로 저장 불필요
        if (ttl.isNegative || ttl.isZero) {
            return
        }
        // Redis TTL: 토큰 만료 시 자동으로 키 삭제
        redisTemplate.opsForValue().set(redisKey(tokenId), "1", ttl)
    }

    override fun isRevoked(tokenId: String): Boolean {
        return redisTemplate.hasKey(redisKey(tokenId)) == true
    }

    /** Redis 키를 생성한다: "{접두사}:{토큰ID}" */
    private fun redisKey(tokenId: String): String = "$keyPrefix:$tokenId"
}
