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
    fun `should register RedisSemanticResponseCache when redis and embedding beans exist`() {
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
    fun `should fall back to CaffeineResponseCache when redis is unreachable`() {
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
    fun `should fall back to CaffeineResponseCache when semantic deps are missing`() {
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
