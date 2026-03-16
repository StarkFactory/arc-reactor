package com.arc.reactor.autoconfigure

import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.cache.impl.RedisSemanticResponseCache
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 의미론적 캐시 자동 설정에 대한 테스트.
 *
 * 의미론적 캐시의 조건부 빈 등록을 검증합니다.
 */
class SemanticCacheAutoConfigurationTest {

    private val baseRunner = ApplicationContextRunner()
        .withPropertyValues(
            "arc.reactor.postgres.required=false",
            "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough",
            "arc.reactor.cache.enabled=true",
            "arc.reactor.cache.semantic.enabled=true"
        )
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    @Test
    fun `redis and embedding beans exist일 때 register RedisSemanticResponseCache해야 한다`() {
        baseRunner
            .withUserConfiguration(AvailableSemanticCacheDepsConfig::class.java)
            .run { context ->
                val cache = context.getBean(ResponseCache::class.java)
                assertInstanceOf(RedisSemanticResponseCache::class.java, cache) {
                    "RedisSemanticResponseCache should be selected as primary ResponseCache"
                }
            }
    }

    @Test
    fun `redis is unreachable일 때 fall back to CaffeineResponseCache해야 한다`() {
        baseRunner
            .withUserConfiguration(UnavailableSemanticCacheDepsConfig::class.java)
            .run { context ->
                val cache = context.getBean(ResponseCache::class.java)
                assertInstanceOf(CaffeineResponseCache::class.java, cache) {
                    "Unreachable Redis should fall back to CaffeineResponseCache"
                }
            }
    }

    @Test
    fun `semantic deps are missing일 때 fall back to CaffeineResponseCache해야 한다`() {
        baseRunner.run { context ->
            val cache = context.getBean(ResponseCache::class.java)
            assertInstanceOf(CaffeineResponseCache::class.java, cache) {
                "CaffeineResponseCache should be used when redis/embedding deps are unavailable"
            }
        }
    }
}

@Configuration
private class AvailableSemanticCacheDepsConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } returns false
        return template
    }

    @Bean
    fun embeddingModel(): EmbeddingModel = mockk(relaxed = true)

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
}

@Configuration
private class UnavailableSemanticCacheDepsConfig {

    @Bean
    fun stringRedisTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>(relaxed = true)
        every { template.hasKey(any()) } throws RuntimeException("redis down")
        return template
    }

    @Bean
    fun embeddingModel(): EmbeddingModel = mockk(relaxed = true)

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
}
