package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TokenRevocationStoreTest {

    @Test
    fun `should mark token as revoked until expiration`() {
        val store = InMemoryTokenRevocationStore()
        val tokenId = "jti-test-1"
        val expiresAt = Instant.now().plusSeconds(60)

        store.revoke(tokenId, expiresAt)

        assertTrue(store.isRevoked(tokenId)) {
            "Revoked token should be reported as revoked before expiration"
        }
    }

    @Test
    fun `should auto-expire revoked token entries`() {
        val store = InMemoryTokenRevocationStore()
        val tokenId = "jti-test-2"
        val expiredAt = Instant.now().minusSeconds(1)

        store.revoke(tokenId, expiredAt)

        assertFalse(store.isRevoked(tokenId)) {
            "Expired revoked token entry should no longer be treated as revoked"
        }
    }

    @Test
    fun `should not store already-expired tokens`() {
        val store = InMemoryTokenRevocationStore()
        store.revoke("expired", Instant.now().minusSeconds(10))

        assertFalse(store.isRevoked("expired")) {
            "Already-expired token should not be stored"
        }
        assertTrue(store.size() == 0) {
            "Store should be empty after rejecting expired token"
        }
    }

    @Test
    fun `should purge expired entries when capacity reached`() {
        val store = InMemoryTokenRevocationStore()
        // Add expired and valid tokens
        store.revoke("expired-1", Instant.now().minusSeconds(1))
        store.revoke("valid-1", Instant.now().plusSeconds(3600))

        store.purgeExpired()

        assertTrue(store.size() == 1) {
            "Only valid token should remain after purge"
        }
        assertTrue(store.isRevoked("valid-1")) {
            "Valid token should still be revoked after purge"
        }
    }
}
