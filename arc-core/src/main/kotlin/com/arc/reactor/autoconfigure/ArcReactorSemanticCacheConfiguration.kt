package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.cache.CacheMetricsRecorder
import com.arc.reactor.cache.MicrometerCacheMetricsRecorder
import com.arc.reactor.cache.NoOpCacheMetricsRecorder
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.cache.impl.RedisSemanticResponseCache
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis 시맨틱 캐시 자동 설정.
 *
 * 다음 조건이 모두 충족될 때만 활성화된다:
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
@ConditionalOnProperty(
    prefix = "arc.reactor.cache.semantic", name = ["enabled"], havingValue = "true"
)
class ArcReactorSemanticCacheConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    @Primary
    @ConditionalOnBean(StringRedisTemplate::class, EmbeddingModel::class)
    @ConditionalOnMissingBean(name = ["redisSemanticResponseCache"])
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

    /**
     * 캐시 메트릭 레코더 빈을 등록한다.
     *
     * [MeterRegistry]가 존재하면 [MicrometerCacheMetricsRecorder]를,
     * 없으면 [NoOpCacheMetricsRecorder]를 반환한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cacheMetricsRecorder(
        meterRegistryProvider: ObjectProvider<MeterRegistry>
    ): CacheMetricsRecorder {
        val registry = meterRegistryProvider.ifAvailable
        if (registry != null) {
            logger.info { "CacheMetricsRecorder: Micrometer registry detected, enabling metrics" }
            return MicrometerCacheMetricsRecorder(registry)
        }
        logger.info { "CacheMetricsRecorder: No Micrometer registry, using NoOp" }
        return NoOpCacheMetricsRecorder()
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
