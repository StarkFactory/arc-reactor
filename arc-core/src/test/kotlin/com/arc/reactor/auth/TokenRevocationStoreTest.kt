package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * TokenRevocationStore에 대한 테스트.
 *
 * 토큰 폐기 저장소 인터페이스의 동작을 검증합니다.
 */
class TokenRevocationStoreTest {

    @Test
    fun `mark token as revoked until expiration해야 한다`() {
        val store = InMemoryTokenRevocationStore()
        val tokenId = "jti-test-1"
        val expiresAt = Instant.now().plusSeconds(60)

        store.revoke(tokenId, expiresAt)

        assertTrue(store.isRevoked(tokenId)) {
            "Revoked token should be reported as revoked before expiration"
        }
    }

    @Test
    fun `auto-expire revoked token entries해야 한다`() {
        val store = InMemoryTokenRevocationStore()
        val tokenId = "jti-test-2"
        val expiredAt = Instant.now().minusSeconds(1)

        store.revoke(tokenId, expiredAt)

        assertFalse(store.isRevoked(tokenId)) {
            "Expired revoked token entry should no longer be treated as revoked"
        }
    }

    @Test
    fun `not store already-expired tokens해야 한다`() {
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
    fun `capacity reached일 때 purge expired entries해야 한다`() {
        val store = InMemoryTokenRevocationStore()
        // expired and valid tokens를 추가합니다
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
