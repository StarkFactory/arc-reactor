package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.cache.impl.RedisSemanticResponseCache
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis semantic cache auto-configuration.
 *
 * Activated only when:
 * - `arc.reactor.cache.enabled=true`
 * - `arc.reactor.cache.semantic.enabled=true`
 * - Redis and embedding classes are present
 */
@Configuration
@ConditionalOnClass(
    name = [
        "org.springframework.data.redis.core.StringRedisTemplate",
        "org.springframework.ai.embedding.EmbeddingModel"
    ]
)
@ConditionalOnBean(StringRedisTemplate::class, EmbeddingModel::class)
@ConditionalOnProperty(prefix = "arc.reactor.cache", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "arc.reactor.cache.semantic", name = ["enabled"], havingValue = "true")
class ArcReactorSemanticCacheConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    @Primary
    fun redisSemanticResponseCache(
        properties: AgentProperties,
        redisTemplate: StringRedisTemplate,
        embeddingModel: EmbeddingModel,
        objectMapper: ObjectMapper
    ): ResponseCache {
        val cacheProps = properties.cache
        val semanticProps = cacheProps.semantic
        if (!isRedisAvailable(redisTemplate)) {
            logger.warn {
                "Semantic cache enabled but Redis is unavailable; " +
                    "falling back to CaffeineResponseCache"
            }
            return CaffeineResponseCache(
                ttlMinutes = cacheProps.ttlMinutes,
                maxSize = cacheProps.maxSize
            )
        }
        return RedisSemanticResponseCache(
            redisTemplate = redisTemplate,
            embeddingModel = embeddingModel,
            objectMapper = objectMapper,
            ttlMinutes = cacheProps.ttlMinutes,
            similarityThreshold = semanticProps.similarityThreshold,
            maxCandidates = semanticProps.maxCandidates,
            maxEntriesPerScope = semanticProps.maxEntriesPerScope,
            keyPrefix = semanticProps.keyPrefix
        )
    }

    private fun isRedisAvailable(redisTemplate: StringRedisTemplate): Boolean {
        return try {
            redisTemplate.hasKey("__arc:redis:availability__")
            true
        } catch (e: Exception) {
            logger.warn(e) { "Redis connectivity probe failed during semantic cache selection" }
            false
        }
    }
}
