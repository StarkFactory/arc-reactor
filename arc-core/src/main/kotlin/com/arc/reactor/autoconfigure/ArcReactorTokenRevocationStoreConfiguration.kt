package com.arc.reactor.autoconfigure

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.InMemoryTokenRevocationStore
import com.arc.reactor.auth.JdbcTokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.TokenRevocationStoreType
import mu.KotlinLogging
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

private val logger = KotlinLogging.logger {}

/**
 * 안전한 폴백을 가진 토큰 폐기 저장소 선택.
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
        beanFactory: ListableBeanFactory,
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
                val redisStore = buildRedisStoreOrNull(beanFactory, environment)
                if (redisStore != null) {
                    redisStore
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

    private fun buildRedisStoreOrNull(
        beanFactory: ListableBeanFactory,
        environment: Environment
    ): TokenRevocationStore? {
        val redisTemplate = resolveRedisTemplate(beanFactory) ?: return null
        if (!isRedisAvailable(redisTemplate)) return null
        val keyPrefix = environment.getProperty(
            "arc.reactor.auth.token-revocation-redis-key-prefix",
            "arc:auth:revoked"
        )
        return instantiateRedisStore(redisTemplate, keyPrefix)
    }

    private fun resolveRedisTemplate(beanFactory: ListableBeanFactory): Any? {
        val redisTemplateClass = runCatching { Class.forName(STRING_REDIS_TEMPLATE_CLASS) }.getOrNull()
            ?: return null
        return runCatching {
            beanFactory.getBeanProvider(redisTemplateClass).getIfAvailable()
        }.getOrNull()
    }

    private fun instantiateRedisStore(redisTemplate: Any, keyPrefix: String): TokenRevocationStore? {
        return try {
            val redisTemplateClass = Class.forName(STRING_REDIS_TEMPLATE_CLASS)
            val redisStoreClass = Class.forName(REDIS_STORE_CLASS)
            val ctor = redisStoreClass.getConstructor(redisTemplateClass, String::class.java)
            ctor.newInstance(redisTemplate, keyPrefix) as TokenRevocationStore
        } catch (e: Exception) {
            logger.warn(e) { "Redis token revocation store instantiation failed" }
            null
        }
    }

    private fun isRedisAvailable(redisTemplate: Any): Boolean {
        return try {
            val hasKeyMethod = redisTemplate.javaClass.methods.firstOrNull { method ->
                method.name == "hasKey" && method.parameterCount == 1
            } ?: return false
            hasKeyMethod.invoke(redisTemplate, "__arc:redis:probe__")
            true
        } catch (e: Exception) {
            logger.warn(e) { "Redis connectivity probe failed during token revocation store selection" }
            false
        }
    }

    companion object {
        private const val STRING_REDIS_TEMPLATE_CLASS =
            "org.springframework.data.redis.core.StringRedisTemplate"
        private const val REDIS_STORE_CLASS = "com.arc.reactor.auth.RedisTokenRevocationStore"
    }
}
