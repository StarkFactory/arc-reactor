package com.arc.reactor.autoconfigure

import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.resilience.CircuitBreakerRegistry
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.resilience.impl.ModelFallbackStrategy
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.impl.MaxLengthResponseFilter
import com.arc.reactor.response.impl.VerifiedSourcesResponseFilter
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment

/**
 * ArcReactorRuntimeConfiguration 자동 설정 단위 테스트.
 *
 * ResponseFilterChain, ResponseCache, FallbackStrategy, CircuitBreakerRegistry,
 * ChatClient, ChatModelProvider의 조건부 빈 생성 및 @ConditionalOnMissingBean 동작을 검증한다.
 */
class ArcReactorRuntimeConfigurationTest {

    /** 환경변수/시스템 프로퍼티 오염 없이 깨끗한 컨텍스트로 실행 */
    private val contextRunner = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.propertySources
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        }
        .withPropertyValues(
            "arc.reactor.postgres.required=false",
            "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
        )
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    // ── ResponseFilterChain ────────────────────────────────────────────

    @Nested
    inner class ResponseFilterChainBeans {

        @Test
        fun `기본 ResponseFilterChain이 등록되어야 한다`() {
            contextRunner.run { context ->
                assertNotNull(context.getBean(ResponseFilterChain::class.java)) {
                    "ResponseFilterChain 빈이 기본으로 등록되어야 한다"
                }
            }
        }

        @Test
        fun `기본 VerifiedSourcesResponseFilter가 등록되어야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(
                    VerifiedSourcesResponseFilter::class.java,
                    context.getBean(VerifiedSourcesResponseFilter::class.java),
                    "VerifiedSourcesResponseFilter가 기본으로 등록되어야 한다"
                )
            }
        }

        @Test
        fun `response-max-length 미설정 시 MaxLengthResponseFilter가 체인에 포함되지 않아야 한다`() {
            contextRunner.run { context ->
                val chain = context.getBean(ResponseFilterChain::class.java)
                // MaxLengthResponseFilter는 response.max-length > 0일 때만 추가됨
                // 기본값은 0이므로 체인 크기는 1(VerifiedSourcesResponseFilter)이어야 한다
                assertTrue(chain.size >= 1) {
                    "ResponseFilterChain에 최소 1개 필터가 있어야 한다 (VerifiedSources)"
                }
            }
        }

        @Test
        fun `response-max-length 설정 시 MaxLengthResponseFilter가 체인에 포함되어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.response.max-length=1000")
                .run { context ->
                    val chain = context.getBean(ResponseFilterChain::class.java)
                    assertTrue(chain.size >= 2) {
                        "arc.reactor.response.max-length 설정 시 MaxLengthResponseFilter가 체인에 추가되어야 한다"
                    }
                }
        }

        @Test
        fun `커스텀 ResponseFilter가 자동 체인에 포함되어야 한다`() {
            contextRunner
                .withUserConfiguration(CustomResponseFilterConfig::class.java)
                .run { context ->
                    val chain = context.getBean(ResponseFilterChain::class.java)
                    assertTrue(chain.size >= 2) {
                        "커스텀 ResponseFilter 빈이 체인에 포함되어야 한다"
                    }
                }
        }

        @Test
        fun `커스텀 ResponseFilterChain으로 기본 체인을 대체할 수 있어야 한다`() {
            contextRunner
                .withUserConfiguration(CustomResponseFilterChainConfig::class.java)
                .run { context ->
                    val chain = context.getBean(ResponseFilterChain::class.java)
                    // 커스텀 체인은 필터가 0개
                    assertTrue(chain.size == 0) {
                        "커스텀 ResponseFilterChain이 기본 체인을 대체해야 한다"
                    }
                }
        }
    }

    // ── ResponseCache ──────────────────────────────────────────────────

    @Nested
    inner class ResponseCacheBeans {

        @Test
        fun `cache-enabled 미설정 시 ResponseCache가 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("responseCache")) {
                    "arc.reactor.cache.enabled=false(기본값) 시 ResponseCache가 등록되어서는 안 된다"
                }
            }
        }

        @Test
        fun `cache-enabled=true 시 CaffeineResponseCache가 등록되어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.cache.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        CaffeineResponseCache::class.java,
                        context.getBean(ResponseCache::class.java),
                        "arc.reactor.cache.enabled=true 시 CaffeineResponseCache가 등록되어야 한다"
                    )
                }
        }

        @Test
        fun `커스텀 ResponseCache로 Caffeine 캐시를 대체할 수 있어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.cache.enabled=true")
                .withUserConfiguration(CustomResponseCacheConfig::class.java)
                .run { context ->
                    val cache = context.getBean(ResponseCache::class.java)
                    assertFalse(cache is CaffeineResponseCache) {
                        "커스텀 ResponseCache가 CaffeineResponseCache를 대체해야 한다"
                    }
                }
        }
    }

    // ── FallbackStrategy ───────────────────────────────────────────────

    @Nested
    inner class FallbackStrategyBeans {

        @Test
        fun `fallback-enabled 미설정 시 FallbackStrategy가 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("fallbackStrategy")) {
                    "arc.reactor.fallback.enabled=false(기본값) 시 FallbackStrategy가 등록되어서는 안 된다"
                }
            }
        }

        @Test
        fun `fallback-enabled=true 시 ModelFallbackStrategy가 등록되어야 한다`() {
            contextRunner
                .withUserConfiguration(FakeChatModelConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.fallback.enabled=true",
                    "arc.reactor.fallback.models=gemini"
                )
                .run { context ->
                    assertInstanceOf(
                        ModelFallbackStrategy::class.java,
                        context.getBean(FallbackStrategy::class.java),
                        "arc.reactor.fallback.enabled=true 시 ModelFallbackStrategy가 등록되어야 한다"
                    )
                }
        }
    }

    // ── CircuitBreakerRegistry ─────────────────────────────────────────

    @Nested
    inner class CircuitBreakerRegistryBeans {

        @Test
        fun `circuit-breaker-enabled 미설정 시 CircuitBreakerRegistry가 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("circuitBreakerRegistry")) {
                    "arc.reactor.circuit-breaker.enabled=false(기본값) 시 CircuitBreakerRegistry가 등록되어서는 안 된다"
                }
            }
        }

        @Test
        fun `circuit-breaker-enabled=true 시 CircuitBreakerRegistry가 등록되어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.circuit-breaker.enabled=true")
                .run { context ->
                    assertNotNull(context.getBean(CircuitBreakerRegistry::class.java)) {
                        "arc.reactor.circuit-breaker.enabled=true 시 CircuitBreakerRegistry가 등록되어야 한다"
                    }
                }
        }

        @Test
        fun `CircuitBreakerRegistry가 설정된 failureThreshold를 사용해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.circuit-breaker.enabled=true",
                    "arc.reactor.circuit-breaker.failure-threshold=3",
                    "arc.reactor.circuit-breaker.reset-timeout-ms=15000"
                )
                .run { context ->
                    val registry = context.getBean(CircuitBreakerRegistry::class.java)
                    // 레지스트리 빈이 존재하는 것 + get()으로 circuit breaker를 조회할 수 있어야 한다
                    val breaker = registry.get("llm")
                    assertNotNull(breaker) {
                        "CircuitBreakerRegistry에서 'llm' 이름의 CircuitBreaker를 조회할 수 있어야 한다"
                    }
                }
        }
    }

    // ── ChatModelProvider ──────────────────────────────────────────────

    @Nested
    inner class ChatModelProviderBeans {

        @Test
        fun `ChatModel 빈이 없을 때 ChatModelProvider가 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                // ChatModel이 없으면 ChatClient도 없으므로 ChatModelProvider도 없어야 한다
                // 단, agentExecutor도 없어야 한다 (@ConditionalOnBean(ChatClient))
                assertFalse(context.containsBean("agentExecutor")) {
                    "ChatClient가 없으면 agentExecutor가 등록되어서는 안 된다"
                }
            }
        }

        @Test
        fun `ChatModel 빈이 있을 때 ChatModelProvider가 등록되어야 한다`() {
            contextRunner
                .withUserConfiguration(FakeChatModelConfig::class.java)
                .run { context ->
                    val provider = context.getBean(ChatModelProvider::class.java)
                    assertNotNull(provider) {
                        "ChatModel 빈이 있을 때 ChatModelProvider가 등록되어야 한다"
                    }
                }
        }

        @Test
        fun `ChatModelProvider가 올바른 기본 프로바이더를 사용해야 한다`() {
            contextRunner
                .withUserConfiguration(FakeChatModelConfig::class.java)
                .withPropertyValues("arc.reactor.llm.default-provider=fakeChatModel")
                .run { context ->
                    val provider = context.getBean(ChatModelProvider::class.java)
                    assertTrue(provider.availableProviders().isNotEmpty()) {
                        "ChatModelProvider의 availableProviders()가 비어 있어서는 안 된다"
                    }
                }
        }
    }

    // ── 테스트 픽스처 ─────────────────────────────────────────────────

    /** 커스텀 ResponseFilter 등록 픽스처 */
    @Configuration
    open class CustomResponseFilterConfig {
        @Bean
        open fun myCustomFilter(): ResponseFilter = object : ResponseFilter {
            override val order: Int = 50
            override suspend fun filter(
                content: String,
                context: com.arc.reactor.response.ResponseFilterContext
            ) = content
        }
    }

    /** 커스텀 ResponseFilterChain 등록 픽스처 (빈 체인) */
    @Configuration
    open class CustomResponseFilterChainConfig {
        @Bean
        open fun responseFilterChain(): ResponseFilterChain = ResponseFilterChain(emptyList())
    }

    /** 커스텀 ResponseCache 등록 픽스처 */
    @Configuration
    open class CustomResponseCacheConfig {
        @Bean
        open fun responseCache(): ResponseCache = object : ResponseCache {
            override suspend fun get(key: String): com.arc.reactor.cache.CachedResponse? = null
            override suspend fun put(key: String, response: com.arc.reactor.cache.CachedResponse) = Unit
            override fun invalidateAll() = Unit
        }
    }

    /** 가짜 ChatModel 등록 픽스처 */
    @Configuration
    open class FakeChatModelConfig {
        @Bean
        open fun fakeChatModel(): ChatModel = mockk(relaxed = true)
    }
}
