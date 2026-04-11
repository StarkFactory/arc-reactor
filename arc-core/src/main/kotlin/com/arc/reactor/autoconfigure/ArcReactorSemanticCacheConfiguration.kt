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
 *
 * ## R265: 활성화 매트릭스 (R264 패턴 확장)
 *
 * 이 자동 구성에는 **세 가지 silent 동작**이 있다. 운영자가 의도한 결과와 실제 동작이 다를 수
 * 있는 케이스를 명시적으로 잠그기 위해 R265에서 활성화 매트릭스를 KDoc에 추가한다.
 *
 * ### 1. 클래스 활성화 조건 (3축 결합)
 *
 * | `cache.semantic.enabled` | Redis classpath | EmbeddingModel classpath | 클래스 평가 | `redisSemanticResponseCache` 빈 | `cacheMetricsRecorder` 빈 |
 * |---|---|---|---|---|---|
 * | ❌ false (또는 미설정) | — | — | ❌ skip | ❌ 미등록 | ❌ 미등록 |
 * | ✅ true | ❌ 없음 | — | ❌ skip | ❌ 미등록 | ❌ 미등록 |
 * | ✅ true | ✅ 있음 | ❌ 없음 | ❌ skip | ❌ 미등록 | ❌ 미등록 |
 * | ✅ true | ✅ 있음 | ✅ 있음 | ✅ 평가 | (다음 표 참조) | (다음 표 참조) |
 *
 * **클래스 평가 경로 (4번째 행)에 들어와야만** 빈 정의가 평가되며, 그 안에서 `@ConditionalOnBean`이
 * 추가로 체크된다. classpath 조건이 컴파일타임 의존성에 묶여 있어 production에서는 거의 항상
 * 충족되지만, 테스트 환경에서는 명시적으로 제어해야 한다.
 *
 * ### 2. `redisSemanticResponseCache` 빈 결정 트리 (Redis fallback 핵심)
 *
 * 클래스가 활성화된 후, 빈 자체는 다음 결정 트리를 따른다:
 *
 * | StringRedisTemplate 빈 | EmbeddingModel 빈 | Redis 연결 프로브 | 결과 빈 종류 | 의도한 동작? |
 * |---|---|---|---|---|
 * | ❌ 없음 | — | — | ❌ 빈 미등록 | ✅ |
 * | ✅ 있음 | ❌ 없음 | — | ❌ 빈 미등록 | ✅ |
 * | ✅ 있음 | ✅ 있음 | ❌ 실패/타임아웃 | **`CaffeineResponseCache`** ⚠️ | ⚠️ silent fallback |
 * | ✅ 있음 | ✅ 있음 | ✅ 성공 | `RedisSemanticResponseCache` | ✅ |
 *
 * **3행이 가장 미묘한 silent 동작**이다. `cache.semantic.enabled=true`로 설정해서 Redis 시맨틱
 * 캐시를 의도한 운영자가, Redis 연결 실패 시 자동으로 `CaffeineResponseCache`(프로세스 로컬,
 * 의미적 검색 없음)로 fallback된다는 사실을 모를 수 있다. WARN 로그가 한 번 출력되지만 운영
 * 대시보드에 노출되지 않으면 놓치기 쉽다.
 *
 * **운영자 가이드**:
 * - **DoctorDiagnostics Response Cache 섹션(R238)**이 결과 빈 종류를 진단한다. fallback 발생 시
 *   `cache tier`가 "caffeine"으로 표시되어 의미적 검색 비활성화 경고(WARN)가 함께 노출된다.
 * - 5초 타임아웃은 [isRedisAvailable]에서 `ForkJoinPool.commonPool().submit(...).get(5, SECONDS)`로
 *   강제된다. 이는 Redis 미응답 시 앱 시작이 60초 이상 차단되지 않도록 하기 위함.
 *
 * ### 3. `cacheMetricsRecorder` 빈 결정 트리 (R264 EvaluationMetrics와 동일 패턴)
 *
 * | `MeterRegistry` 빈 | 결과 빈 | 동작 |
 * |---|---|---|
 * | ❌ 없음 | `NoOpCacheMetricsRecorder` | 메트릭 무시 |
 * | ✅ 있음 | `MicrometerCacheMetricsRecorder` | Micrometer 등록 |
 *
 * 이 빈 또한 R264 [EvaluationMetricsConfiguration]의 collector 결정과 마찬가지로 `MeterRegistry`
 * 존재만으로 자동 활성화되며, 별도 enabled 프로퍼티가 없다. 즉 `cache.semantic.enabled=true`로
 * 시맨틱 캐시를 켰다면 Micrometer가 있을 때 메트릭은 자동으로 노출된다.
 *
 * ### 변경 시 주의 (잠금 사항)
 *
 * 다음 변경은 활성화 매트릭스를 깬다 — 의도된 변경이라면 KDoc 표 + 관련 테스트도 함께 갱신해야 한다:
 *
 * 1. [redisSemanticResponseCache]에 `@ConditionalOnProperty` 추가 → 매트릭스 1번 표 4행이 분기됨
 * 2. [isRedisAvailable] 타임아웃 5초 변경 → app 시작 시간 영향, KDoc 명시 필요
 * 3. Redis 프로브 실패 시 `CaffeineResponseCache` fallback 제거 (예외 throw 또는 빈 미등록) →
 *    매트릭스 2번 표 3행 변경, R238 DoctorDiagnostics Response Cache 진단도 영향
 * 4. [cacheMetricsRecorder]에 `@ConditionalOnProperty` 추가 → R264 패턴과 어긋남
 *
 * @see EvaluationMetricsConfiguration R264 활성화 매트릭스 (자매 패턴)
 * @see com.arc.reactor.diagnostics.DoctorDiagnostics R238 Response Cache 진단 섹션
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

    /**
     * Redis 시맨틱 응답 캐시 빈.
     *
     * R265 잠금: Redis 연결 프로브 실패 시 **silent하게** [CaffeineResponseCache]로 fallback한다
     * (WARN 로그 출력). 운영자는 [com.arc.reactor.diagnostics.DoctorDiagnostics]의 Response Cache
     * 섹션(R238)에서 결과 tier가 "caffeine"인지 확인하여 fallback 발생 여부를 진단할 수 있다.
     *
     * 클래스 KDoc의 활성화 매트릭스(2번 표) 3행 참조.
     */
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
     *
     * R265 명시: 이 빈은 R264 [EvaluationMetricsConfiguration]의 `micrometerEvaluationMetricsCollector`와
     * 동일한 패턴 — 별도 enabled 프로퍼티 없이 [MeterRegistry] 존재만으로 Micrometer 구현체가
     * 활성화된다. 클래스 KDoc 활성화 매트릭스 3번 표 참조.
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
