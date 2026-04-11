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
 *
 * R288 보안 강화: `arc.reactor.auth.token-revocation-store-strict=true` 설정 시
 * backend 미가용 시 silent fallback 대신 [BeanCreationException]을 throw하여 startup 실패.
 * 운영자가 즉시 인지하고 조치할 수 있어 revoked tokens가 silent하게 영속성 없이
 * 재검증되는 보안 회귀를 방지한다.
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
                    handleBackendUnavailable(
                        backend = "jdbc",
                        reason = "JdbcTemplate bean이 ApplicationContext에 없음",
                        strict = authProperties.tokenRevocationStoreStrict
                    )
                }
            }

            TokenRevocationStoreType.REDIS -> {
                val redisStore = buildRedisStoreOrNull(beanFactory, environment)
                if (redisStore != null) {
                    redisStore
                } else {
                    handleBackendUnavailable(
                        backend = "redis",
                        reason = "RedisTemplate 미가용 또는 연결 probe 실패 또는 인스턴스화 실패",
                        strict = authProperties.tokenRevocationStoreStrict
                    )
                }
            }
        }
    }

    /**
     * R288: backend 미가용 시 strict 모드면 fail-fast, 아니면 in-memory fallback.
     *
     * fail-fast 모드는 운영자가 의도한 영속성 backend가 사용 불가능할 때 즉시 startup을
     * 실패시켜 silent 보안 회귀(revoked tokens가 restart 시 모두 revalidate)를 방지한다.
     *
     * @throws org.springframework.beans.factory.BeanCreationException strict 모드일 때
     */
    private fun handleBackendUnavailable(
        backend: String,
        reason: String,
        strict: Boolean
    ): TokenRevocationStore {
        if (strict) {
            val message = "token-revocation-store=$backend requested but backend is unavailable: " +
                "$reason. tokenRevocationStoreStrict=true 설정으로 fail-fast — silent in-memory " +
                "fallback은 보안 회귀 위험(restart 시 revoked tokens 모두 revalidate)으로 차단됨. " +
                "backend를 복구하거나 strict=false로 변경하여 dev 모드로 진행하세요."
            logger.error { message }
            throw org.springframework.beans.factory.BeanCreationException(message)
        }
        logger.warn {
            "token-revocation-store=$backend requested but backend is unavailable: $reason. " +
                "Falling back to in-memory token revocation store. " +
                "WARNING: revoked tokens will not survive restart. " +
                "Set arc.reactor.auth.token-revocation-store-strict=true to fail-fast in production."
        }
        return InMemoryTokenRevocationStore()
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
            logger.warn(e) { "Redis 토큰 폐기 저장소 인스턴스화 실패" }
            null
        }
    }

    private fun isRedisAvailable(redisTemplate: Any): Boolean {
        return try {
            val hasKeyMethod = redisTemplate.javaClass.methods.firstOrNull { method ->
                method.name == "hasKey" && method.parameterCount == 1
            } ?: return false
            // 타임아웃으로 감싸서 Redis 미응답 시 앱 시작 60초 멈춤 방지
            val future = java.util.concurrent.ForkJoinPool.commonPool().submit<Any?> {
                hasKeyMethod.invoke(redisTemplate, "__arc:redis:probe__")
            }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            logger.warn(e) { "토큰 폐기 저장소 선택 중 Redis 연결 프로브 실패" }
            false
        }
    }

    companion object {
        private const val STRING_REDIS_TEMPLATE_CLASS =
            "org.springframework.data.redis.core.StringRedisTemplate"
        private const val REDIS_STORE_CLASS = "com.arc.reactor.auth.RedisTokenRevocationStore"
    }
}
