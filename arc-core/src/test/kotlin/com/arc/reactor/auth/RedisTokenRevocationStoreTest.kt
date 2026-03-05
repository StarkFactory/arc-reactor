package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant

class RedisTokenRevocationStoreTest {

    @Test
    fun `revoke should write key with ttl`() {
        val redisTemplate = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        val store = RedisTokenRevocationStore(redisTemplate)

        store.revoke("jti-1", Instant.now().plusSeconds(120))

        verify(exactly = 1) {
            valueOps.set(match { it == "arc:auth:revoked:jti-1" }, "1", any<java.time.Duration>())
        }
    }

    @Test
    fun `revoke should skip already expired token`() {
        val redisTemplate = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        val store = RedisTokenRevocationStore(redisTemplate)

        store.revoke("jti-expired", Instant.now().minusSeconds(5))

        verify(exactly = 0) { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `isRevoked should check redis key existence`() {
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.hasKey("arc:auth:revoked:jti-hit") } returns true
        every { redisTemplate.hasKey("arc:auth:revoked:jti-miss") } returns false
        val store = RedisTokenRevocationStore(redisTemplate)

        assertTrue(store.isRevoked("jti-hit")) {
            "Redis key presence must be treated as revoked token"
        }
        assertFalse(store.isRevoked("jti-miss")) {
            "Missing Redis key must be treated as non-revoked token"
        }
    }
}
