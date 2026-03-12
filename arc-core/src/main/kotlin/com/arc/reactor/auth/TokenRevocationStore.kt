package com.arc.reactor.auth

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores revoked JWT token IDs (`jti`) until their original expiration.
 */
interface TokenRevocationStore {
    fun revoke(tokenId: String, expiresAt: Instant)
    fun isRevoked(tokenId: String): Boolean
}

class InMemoryTokenRevocationStore : TokenRevocationStore {
    private val revoked = ConcurrentHashMap<String, Instant>()
    private val maxEntries = 10_000

    override fun revoke(tokenId: String, expiresAt: Instant) {
        if (expiresAt <= Instant.now()) return
        if (revoked.size >= maxEntries) purgeExpired()
        revoked[tokenId] = expiresAt
    }

    override fun isRevoked(tokenId: String): Boolean {
        val expiresAt = revoked[tokenId] ?: return false
        if (expiresAt <= Instant.now()) {
            revoked.remove(tokenId, expiresAt)
            return false
        }
        return true
    }

    internal fun purgeExpired() {
        val now = Instant.now()
        revoked.entries.removeIf { it.value <= now }
    }

    internal fun size(): Int = revoked.size
}

/**
 * JDBC-backed revoked token store for multi-instance deployments.
 *
 * Requires Flyway migration `V31__create_token_revocations.sql`.
 */
class JdbcTokenRevocationStore(
    private val jdbcTemplate: JdbcTemplate
) : TokenRevocationStore {

    override fun revoke(tokenId: String, expiresAt: Instant) {
        if (expiresAt <= Instant.now()) {
            return
        }
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

        if (expiresAt <= Instant.now()) {
            jdbcTemplate.update("DELETE FROM auth_token_revocations WHERE token_id = ?", tokenId)
            return false
        }
        return true
    }
}

/**
 * Redis-backed revoked token store for multi-instance deployments.
 */
class RedisTokenRevocationStore(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String = "arc:auth:revoked"
) : TokenRevocationStore {

    init {
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }

    override fun revoke(tokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(), expiresAt)
        if (ttl.isNegative || ttl.isZero) {
            return
        }
        redisTemplate.opsForValue().set(redisKey(tokenId), "1", ttl)
    }

    override fun isRevoked(tokenId: String): Boolean {
        return redisTemplate.hasKey(redisKey(tokenId)) == true
    }

    private fun redisKey(tokenId: String): String = "$keyPrefix:$tokenId"
}
