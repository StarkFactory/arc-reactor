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
}
