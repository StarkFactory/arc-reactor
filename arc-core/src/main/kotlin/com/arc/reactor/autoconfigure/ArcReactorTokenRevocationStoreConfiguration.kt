package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.RedisTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Optional token revocation store backends.
 *
 * - memory (default): InMemoryTokenRevocationStore
 * - jdbc: JdbcTokenRevocationStore
 * - redis: RedisTokenRevocationStore
 */
class ArcReactorTokenRevocationStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(TokenRevocationStore::class)
    @ConditionalOnClass(JdbcTemplate::class)
    @ConditionalOnProperty(prefix = "arc.reactor.auth", name = ["token-revocation-store"], havingValue = "jdbc")
    fun jdbcTokenRevocationStore(jdbcTemplate: JdbcTemplate): TokenRevocationStore {
        return JdbcTokenRevocationStore(jdbcTemplate)
    }

    @Bean
    @ConditionalOnMissingBean(TokenRevocationStore::class)
    @ConditionalOnClass(StringRedisTemplate::class)
    @ConditionalOnProperty(prefix = "arc.reactor.auth", name = ["token-revocation-store"], havingValue = "redis")
    fun redisTokenRevocationStore(
        redisTemplate: StringRedisTemplate,
        environment: Environment
    ): TokenRevocationStore {
        val keyPrefix = environment.getProperty(
            "arc.reactor.auth.token-revocation-redis-key-prefix",
            "arc:auth:revoked"
        )
        return RedisTokenRevocationStore(redisTemplate, keyPrefix = keyPrefix)
    }
}
