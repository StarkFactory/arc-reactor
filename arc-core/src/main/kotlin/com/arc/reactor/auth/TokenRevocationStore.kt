package com.arc.reactor.auth

import java.time.Instant
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

    override fun revoke(tokenId: String, expiresAt: Instant) {
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
}
