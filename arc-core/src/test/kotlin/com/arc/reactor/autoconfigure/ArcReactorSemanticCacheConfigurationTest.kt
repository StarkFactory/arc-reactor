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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * R266: [ArcReactorSemanticCacheConfiguration] R265 KDoc 활성화 매트릭스를 실제
 * Spring 컨텍스트에서 검증하는 통합 테스트.
 *
 * R265는 KDoc로 매트릭스를 명시했고, R266은 그 매트릭스를 ApplicationContextRunner로
 * 검증하여 미래 변경이 매트릭스를 깨면 즉시 차단되도록 한다 (R263 → R264 → R265 →
 * R266 4단계 진단 인프라 패턴).
 *
 * ## 검증 시나리오
 *
 * R265 클래스 KDoc의 3개 매트릭스 표를 다음과 같이 매핑한다:
 *
 * | KDoc 매트릭스 | 테스트 클래스 |
 * |---|---|
 * | 1번 표 (클래스 활성화 3축) | [ClassActivation] |
 * | 2번 표 (Redis fallback 결정 트리) | [RedisCacheBeanResolution] |
 * | 3번 표 (cacheMetricsRecorder MeterRegistry 자동 활성화) | [CacheMetricsRecorderActivation] |
 *
 * ## Redis classpath 가정
 *
 * 클래스 KDoc 1번 표의 `@ConditionalOnClass(["StringRedisTemplate", "EmbeddingModel"])`
 * 조건은 테스트 classpath에 두 클래스가 항상 존재하므로 별도 검증 생략 (production
 * dependencies에 컴파일타임 의존). 이 클래스가 스킵되는 시나리오는 직접 테스트할 수 없다.
 */
class ArcReactorSemanticCacheConfigurationTest {

    /** AgentProperties + ObjectMapper만 등록한 베이스 contextRunner. */
    private val baseContextRunner = ApplicationContextRunner()
        .withUserConfiguration(ArcReactorSemanticCacheConfiguration::class.java)
        .withBean(AgentProperties::class.java, { AgentProperties() })
        .withBean(ObjectMapper::class.java, { ObjectMapper() })

    @Nested
    inner class ClassActivation {

        @Test
        fun `R266 cache semantic enabled 미설정 시 클래스가 스킵되어야 한다`() {
            baseContextRunner.run { context ->
                assertFalse(
                    context.containsBean("redisSemanticResponseCache")
                ) { "enabled 프로퍼티 없으면 빈 미등록" }
                assertFalse(
                    context.containsBean("cacheMetricsRecorder")
                ) { "enabled 프로퍼티 없으면 메트릭 레코더도 미등록" }
            }
        }

        @Test
        fun `R266 cache semantic enabled false 시 클래스가 스킵되어야 한다`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=false")
                .run { context ->
                    assertFalse(
                        context.containsBean("redisSemanticResponseCache")
                    ) { "enabled=false → 클래스 스킵" }
                }
        }

        @Test
        fun `R266 enabled true이지만 Redis EmbeddingModel 빈 모두 없으면 빈 미등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .run { context ->
                    // 클래스는 평가되었지만 @ConditionalOnBean 미충족
                    assertFalse(
                        context.containsBean("redisSemanticResponseCache")
                    ) { "Redis/EmbeddingModel 빈 없으면 @ConditionalOnBean으로 차단" }

                    // cacheMetricsRecorder는 상위 클래스 활성화로 등록되어야 함
                    assertTrue(
                        context.containsBean("cacheMetricsRecorder")
                    ) { "클래스 활성화 시 cacheMetricsRecorder는 항상 등록 (NoOp fallback)" }
                }
        }
    }

    @Nested
    inner class RedisCacheBeanResolution {

        /** Redis 프로브 성공 (hasKey가 정상 반환). */
        private fun healthyRedisTemplate(): StringRedisTemplate {
            val redis = mockk<StringRedisTemplate>(relaxed = true)
            every { redis.hasKey(any<String>()) } returns false
            return redis
        }

        /** Redis 프로브 실패 (hasKey가 throw). */
        private fun brokenRedisTemplate(): StringRedisTemplate {
            val redis = mockk<StringRedisTemplate>(relaxed = true)
            every { redis.hasKey(any<String>()) } throws
                RuntimeException("simulated Redis connection failure")
            return redis
        }

        private fun stubEmbeddingModel(): EmbeddingModel = mockk(relaxed = true)

        @Test
        fun `R266 매트릭스 2번 표 4행 - Redis 정상 시 RedisSemanticResponseCache 등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(StringRedisTemplate::class.java, ::healthyRedisTemplate)
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .run { context ->
                    val cache = context.getBean("redisSemanticResponseCache", ResponseCache::class.java)
                    assertInstanceOf(RedisSemanticResponseCache::class.java, cache) {
                        "Redis 프로브 성공 → RedisSemanticResponseCache. " +
                            "actual=${cache::class.java.simpleName}"
                    }
                }
        }

        @Test
        fun `R266 매트릭스 2번 표 3행 - Redis 프로브 실패 시 silent CaffeineResponseCache fallback`() {
            // KDoc R265의 가장 위험한 silent 동작: WARN 로그 한 번 후 자동 fallback
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(StringRedisTemplate::class.java, ::brokenRedisTemplate)
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .run { context ->
                    val cache = context.getBean("redisSemanticResponseCache", ResponseCache::class.java)
                    assertInstanceOf(CaffeineResponseCache::class.java, cache) {
                        "Redis 프로브 실패 → CaffeineResponseCache fallback (R265 KDoc 매트릭스 2번 표 3행). " +
                            "actual=${cache::class.java.simpleName}"
                    }
                }
        }

        @Test
        fun `R266 매트릭스 2번 표 1행 - StringRedisTemplate 빈 없으면 빈 미등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .run { context ->
                    assertFalse(
                        context.containsBean("redisSemanticResponseCache")
                    ) { "StringRedisTemplate 미등록 → @ConditionalOnBean 차단" }
                }
        }

        @Test
        fun `R266 매트릭스 2번 표 2행 - EmbeddingModel 빈 없으면 빈 미등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(StringRedisTemplate::class.java, ::healthyRedisTemplate)
                .run { context ->
                    assertFalse(
                        context.containsBean("redisSemanticResponseCache")
                    ) { "EmbeddingModel 미등록 → @ConditionalOnBean 차단" }
                }
        }

        @Test
        fun `R266 fallback CaffeineResponseCache가 RedisSemanticResponseCache 인터페이스가 아님 검증`() {
            // 이 테스트는 fallback이 silent하다는 사실을 추가로 잠근다.
            // CaffeineResponseCache는 SemanticResponseCache 인터페이스를 구현하지 않으므로
            // 의미적 검색 기능을 제공하지 않는다.
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(StringRedisTemplate::class.java, ::brokenRedisTemplate)
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .run { context ->
                    val cache = context.getBean("redisSemanticResponseCache", ResponseCache::class.java)
                    assertFalse(
                        cache is com.arc.reactor.cache.SemanticResponseCache
                    ) {
                        "fallback Caffeine은 SemanticResponseCache 미구현 — 의미적 검색 비활성, " +
                            "운영자가 R238 DoctorDiagnostics로만 발견 가능"
                    }
                }
        }
    }

    @Nested
    inner class CacheMetricsRecorderActivation {

        private fun stubRedis(): StringRedisTemplate {
            val redis = mockk<StringRedisTemplate>(relaxed = true)
            every { redis.hasKey(any<String>()) } returns false
            return redis
        }

        private fun stubEmbeddingModel(): EmbeddingModel = mockk(relaxed = true)

        @Test
        fun `R266 매트릭스 3번 표 - MeterRegistry 미등록 시 NoOpCacheMetricsRecorder`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .run { context ->
                    val recorder = context.getBean(CacheMetricsRecorder::class.java)
                    assertInstanceOf(NoOpCacheMetricsRecorder::class.java, recorder) {
                        "MeterRegistry 미등록 → NoOp recorder. " +
                            "actual=${recorder::class.java.simpleName}"
                    }
                }
        }

        @Test
        fun `R266 매트릭스 3번 표 - MeterRegistry 등록 시 MicrometerCacheMetricsRecorder`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { context ->
                    val recorder = context.getBean(CacheMetricsRecorder::class.java)
                    assertInstanceOf(MicrometerCacheMetricsRecorder::class.java, recorder) {
                        "MeterRegistry 등록 → Micrometer recorder. " +
                            "actual=${recorder::class.java.simpleName}"
                    }
                }
        }

        @Test
        fun `R266 R264 자매 패턴 검증 - cacheMetricsRecorder는 enabled 프로퍼티와 무관`() {
            // R264 EvaluationMetricsConfiguration의 micrometerEvaluationMetricsCollector와
            // 동일한 패턴 — MeterRegistry만으로 활성화, 별도 enabled 프로퍼티 없음
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { context ->
                    val recorder = context.getBean(CacheMetricsRecorder::class.java)
                    assertInstanceOf(MicrometerCacheMetricsRecorder::class.java, recorder) {
                        "별도 enable 프로퍼티 없이 MeterRegistry만으로 Micrometer 활성화 (R264 자매 패턴)"
                    }
                }
        }
    }

    @Nested
    inner class CompleteScenarios {

        private fun healthyRedis(): StringRedisTemplate {
            val redis = mockk<StringRedisTemplate>(relaxed = true)
            every { redis.hasKey(any<String>()) } returns false
            return redis
        }

        private fun stubEmbeddingModel(): EmbeddingModel = mockk(relaxed = true)

        @Test
        fun `R266 production 정상 시나리오 - 모든 의존성 충족 + MeterRegistry`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true")
                .withBean(StringRedisTemplate::class.java, ::healthyRedis)
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { context ->
                    val cache = context.getBean("redisSemanticResponseCache", ResponseCache::class.java)
                    val recorder = context.getBean(CacheMetricsRecorder::class.java)

                    assertInstanceOf(RedisSemanticResponseCache::class.java, cache) {
                        "production 정상: RedisSemanticResponseCache"
                    }
                    assertInstanceOf(MicrometerCacheMetricsRecorder::class.java, recorder) {
                        "production 정상: MicrometerCacheMetricsRecorder"
                    }
                    assertTrue(cache is com.arc.reactor.cache.SemanticResponseCache) {
                        "RedisSemanticResponseCache는 SemanticResponseCache 인터페이스 구현"
                    }
                }
        }

        @Test
        fun `R266 운영자 의도와 silent fallback 갭 - 시맨틱 의도했으나 Redis 장애로 Caffeine`() {
            // 가장 중요한 운영 시나리오: 운영자가 의도한 동작과 실제 결과의 갭
            val brokenRedis = mockk<StringRedisTemplate>(relaxed = true)
            every { brokenRedis.hasKey(any<String>()) } throws
                RuntimeException("Redis cluster temporary outage during app startup")

            baseContextRunner
                .withPropertyValues("arc.reactor.cache.semantic.enabled=true") // 운영자 의도: 시맨틱
                .withBean(StringRedisTemplate::class.java, { brokenRedis })
                .withBean(EmbeddingModel::class.java, ::stubEmbeddingModel)
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .run { context ->
                    val cache = context.getBean("redisSemanticResponseCache", ResponseCache::class.java)

                    // 실제 결과: Caffeine fallback (의도와 다름!)
                    assertInstanceOf(CaffeineResponseCache::class.java, cache) {
                        "운영자는 시맨틱 캐시를 의도했으나 silent fallback. " +
                            "이는 R238 DoctorDiagnostics로만 발견 가능"
                    }
                    assertFalse(cache is com.arc.reactor.cache.SemanticResponseCache) {
                        "fallback은 의미적 검색 기능 없음 → 비용 절감 효과 손실"
                    }
                }
        }
    }
}
