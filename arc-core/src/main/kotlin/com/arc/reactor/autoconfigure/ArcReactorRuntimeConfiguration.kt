package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
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
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * 프로바이더 자동 설정의 ChatModel에 의존하는 런타임 빈.
 *
 * 컴포넌트 스캔이 자동 설정 순서(@AutoConfigureAfter) 적용 전에
 * 이 클래스를 가져가는 것을 방지하기 위해 @Configuration을 붙이지 않는다.
 * ArcReactorAutoConfiguration의 @Import를 통해서만 처리된다.
 */
class ArcReactorRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(VerifiedSourcesResponseFilter::class)
    fun verifiedSourcesResponseFilter(): ResponseFilter = VerifiedSourcesResponseFilter()

    /**
     * ChatClient — 기본 프로바이더를 선택하여 다중 ChatModel 모호성을 해소한다.
     * 자동 설정 컨텍스트에 등록하여 @ConditionalOnBean(ChatClient)이
     * agentExecutor 같은 하위 빈에서 동작하도록 한다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatModel::class)
    fun chatClient(
        chatModels: Map<String, ChatModel>,
        properties: AgentProperties
    ): ChatClient {
        val defaultBeanName = chatModels.keys.firstOrNull { beanName ->
            ChatModelProvider.resolveProviderName(beanName) == properties.llm.defaultProvider
        } ?: chatModels.keys.firstOrNull()
        val resolvedBeanName = checkNotNull(defaultBeanName) {
            "No ChatModel bean found. Configure at least one Spring AI provider."
        }
        val chatModel = checkNotNull(chatModels[resolvedBeanName]) {
            "Resolved ChatModel bean '$resolvedBeanName' is missing from chatModels map."
        }
        return ChatClient.builder(chatModel).build()
    }

    /**
     * Chat Model Provider (멀티 LLM 런타임 선택)
     */
    @Bean
    @ConditionalOnMissingBean
    fun chatModelProvider(
        chatModels: Map<String, ChatModel>,
        properties: AgentProperties
    ): ChatModelProvider {
        val providerMap = chatModels.map { (beanName, model) ->
            ChatModelProvider.resolveProviderName(beanName) to model
        }.toMap()
        return ChatModelProvider(providerMap, properties.llm.defaultProvider)
    }

    /**
     * 응답 필터 체인 — 에이전트 응답에 후처리 필터를 적용한다.
     *
     * 모든 [ResponseFilter] 빈을 수집하여 순서대로 체인한다.
     * `response.max-length > 0`이면 [MaxLengthResponseFilter]를 자동 포함한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun responseFilterChain(
        properties: AgentProperties,
        filters: ObjectProvider<ResponseFilter>
    ): ResponseFilterChain {
        val allFilters = mutableListOf<ResponseFilter>()
        filters.forEach { allFilters.add(it) }
        // MaxLengthResponseFilter is only added for explicit response.maxLength config.
        // boundaries.outputMaxChars is handled by checkOutputBoundary() in the executor
        // to avoid double truncation.
        if (properties.response.maxLength > 0) {
            val hasMaxLength = allFilters.any { it is MaxLengthResponseFilter }
            if (!hasMaxLength) {
                allFilters.add(MaxLengthResponseFilter(properties.response.maxLength))
            }
        }
        return ResponseFilterChain(allFilters)
    }

    /**
     * 응답 캐시 — 동일한 요청에 대해 에이전트 응답을 캐시한다.
     *
     * `arc.reactor.cache.enabled=true`일 때만 생성된다.
     */
    @Bean
    @ConditionalOnMissingBean(ResponseCache::class)
    @ConditionalOnProperty(prefix = "arc.reactor.cache", name = ["enabled"], havingValue = "true")
    fun responseCache(properties: AgentProperties): ResponseCache {
        val cacheProps = properties.cache
        return CaffeineResponseCache(
            maxSize = cacheProps.maxSize,
            ttlMinutes = cacheProps.ttlMinutes
        )
    }

    /**
     * 폴백 전략 — 실패 시 대체 LLM 모델로 장애 완화한다.
     *
     * `arc.reactor.fallback.enabled=true`일 때만 생성된다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "arc.reactor.fallback", name = ["enabled"], havingValue = "true")
    fun fallbackStrategy(
        properties: AgentProperties,
        chatModelProvider: ChatModelProvider,
        agentMetrics: AgentMetrics
    ): FallbackStrategy {
        return ModelFallbackStrategy(
            fallbackModels = properties.fallback.models,
            chatModelProvider = chatModelProvider,
            agentMetrics = agentMetrics
        )
    }

    /**
     * 서킷 브레이커 레지스트리 — LLM 및 MCP 호출을 위한 명명된 서킷 브레이커를 관리한다.
     *
     * `arc.reactor.circuit-breaker.enabled=true`일 때만 생성된다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "arc.reactor.circuit-breaker", name = ["enabled"], havingValue = "true")
    fun circuitBreakerRegistry(
        properties: AgentProperties,
        agentMetrics: AgentMetrics
    ): CircuitBreakerRegistry {
        val cb = properties.circuitBreaker
        return CircuitBreakerRegistry(
            failureThreshold = cb.failureThreshold,
            resetTimeoutMs = cb.resetTimeoutMs,
            halfOpenMaxCalls = cb.halfOpenMaxCalls,
            agentMetrics = agentMetrics
        )
    }
}
