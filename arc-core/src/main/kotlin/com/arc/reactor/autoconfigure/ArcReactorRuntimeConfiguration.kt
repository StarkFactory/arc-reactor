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
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ArcReactorRuntimeConfiguration {

    /**
     * Chat Model Provider (multi-LLM runtime selection)
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
     * Response Filter Chain — applies post-processing filters to agent responses.
     *
     * Collects all [ResponseFilter] beans and chains them by order.
     * Automatically includes [MaxLengthResponseFilter] if `response.max-length > 0`.
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
     * Response Cache — caches agent responses for identical requests.
     *
     * Only created when `arc.reactor.cache.enabled=true`.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "arc.reactor.cache", name = ["enabled"], havingValue = "true")
    fun responseCache(properties: AgentProperties): ResponseCache {
        val cacheProps = properties.cache
        return CaffeineResponseCache(
            maxSize = cacheProps.maxSize,
            ttlMinutes = cacheProps.ttlMinutes
        )
    }

    /**
     * Fallback Strategy — graceful degradation to alternative LLM models on failure.
     *
     * Only created when `arc.reactor.fallback.enabled=true`.
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
     * Circuit Breaker Registry — manages named circuit breakers for LLM and MCP calls.
     *
     * Only created when `arc.reactor.circuit-breaker.enabled=true`.
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
