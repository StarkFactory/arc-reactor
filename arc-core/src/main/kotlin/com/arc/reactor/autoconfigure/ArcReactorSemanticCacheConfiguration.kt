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
            logger.info { "CacheMetricsRecorder: Micrometer 레지스트리 감지, 메트릭 활성화" }
            return MicrometerCacheMetricsRecorder(registry)
        }
        logger.info { "CacheMetricsRecorder: Micrometer 레지스트리 없음, NoOp 사용" }
        return NoOpCacheMetricsRecorder()
    }

    private fun isRedisAvailable(redisTemplate: StringRedisTemplate): Boolean {
        return try {
            // 타임아웃으로 감싸서 Redis 미응답 시 앱 시작 60초 멈춤 방지
            val future = java.util.concurrent.ForkJoinPool.commonPool().submit<Boolean> {
                redisTemplate.hasKey("__arc:redis:availability__")
            }
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            logger.warn(e) { "시맨틱 캐시 선택 중 Redis 연결 프로브 실패" }
            false
        }
    }
}
