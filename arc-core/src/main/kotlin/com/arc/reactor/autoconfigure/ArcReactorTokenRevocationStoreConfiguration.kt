package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.InMemoryTokenRevocationStore
import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.RedisTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStoreType
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate

private val logger = KotlinLogging.logger {}

/**
 * Token revocation store selection with safe fallback.
 *
 * Behavior:
 * - `memory` (default) -> in-memory store
 * - `jdbc` -> JDBC store if JdbcTemplate exists, otherwise in-memory fallback
 * - `redis` -> Redis store when template exists and connectivity probe succeeds,
 *   otherwise in-memory fallback
 */
@Configuration
class ArcReactorTokenRevocationStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(TokenRevocationStore::class)
    fun tokenRevocationStore(
        authProperties: AuthProperties,
        jdbcTemplate: ObjectProvider<JdbcTemplate>,
        redisTemplate: ObjectProvider<StringRedisTemplate>,
        environment: Environment
    ): TokenRevocationStore {
        return when (authProperties.tokenRevocationStore) {
            TokenRevocationStoreType.MEMORY -> InMemoryTokenRevocationStore()

            TokenRevocationStoreType.JDBC -> {
                val jdbc = jdbcTemplate.ifAvailable
                if (jdbc != null) {
                    JdbcTokenRevocationStore(jdbc)
                } else {
                    logger.warn {
                        "token-revocation-store=jdbc requested but JdbcTemplate is unavailable; " +
                            "falling back to in-memory token revocation store"
                    }
                    InMemoryTokenRevocationStore()
                }
            }

            TokenRevocationStoreType.REDIS -> {
                val redis = redisTemplate.ifAvailable
                if (redis != null && isRedisAvailable(redis)) {
                    val keyPrefix = environment.getProperty(
                        "arc.reactor.auth.token-revocation-redis-key-prefix",
                        "arc:auth:revoked"
                    )
                    RedisTokenRevocationStore(redis, keyPrefix = keyPrefix)
                } else {
                    logger.warn {
                        "token-revocation-store=redis requested but Redis is unavailable; " +
                            "falling back to in-memory token revocation store"
                    }
                    InMemoryTokenRevocationStore()
                }
            }
        }
    }

    private fun isRedisAvailable(redisTemplate: StringRedisTemplate): Boolean {
        return try {
            redisTemplate.hasKey("__arc:redis:availability__")
            true
        } catch (e: Exception) {
            logger.warn(e) { "Redis connectivity probe failed during token revocation store selection" }
            false
        }
    }
}
