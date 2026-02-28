package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.impl.defaultTransientErrorClassifier
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.resilience.CircuitBreakerRegistry
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.LocalToolFilter
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * AgentExecutor bean, conditional on ChatClient availability.
 *
 * NOT annotated with @Configuration to prevent component-scan from picking it up
 * before auto-configuration ordering (@AutoConfigureAfter) takes effect.
 * Processed exclusively via @Import from ArcReactorAutoConfiguration.
 */
class ArcReactorExecutorConfiguration {

    /**
     * Agent Executor
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatClient::class)
    fun agentExecutor(
        chatClient: ChatClient,
        chatModelProvider: ChatModelProvider,
        properties: AgentProperties,
        localTools: List<LocalTool>,
        localToolFilters: List<LocalToolFilter>,
        toolCallbacks: List<ToolCallback>,
        toolSelector: ToolSelector,
        requestGuard: RequestGuard?,
        hookExecutor: HookExecutor,
        memoryStore: MemoryStore,
        mcpManager: McpManager,
        errorMessageResolver: ErrorMessageResolver,
        agentMetrics: AgentMetrics,
        ragPipelineProvider: ObjectProvider<RagPipeline>,
        tokenEstimator: TokenEstimator,
        conversationManager: ConversationManager,
        toolApprovalPolicyProvider: ObjectProvider<ToolApprovalPolicy>,
        pendingApprovalStoreProvider: ObjectProvider<PendingApprovalStore>,
        responseFilterChain: ResponseFilterChain,
        circuitBreakerRegistryProvider: ObjectProvider<CircuitBreakerRegistry>,
        responseCacheProvider: ObjectProvider<ResponseCache>,
        fallbackStrategyProvider: ObjectProvider<FallbackStrategy>,
        outputGuardPipelineProvider: ObjectProvider<OutputGuardPipeline>,
        intentResolverProvider: ObjectProvider<IntentResolver>,
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>
    ): AgentExecutor = SpringAiAgentExecutor(
        chatClient = chatClient,
        chatModelProvider = chatModelProvider,
        properties = properties,
        localTools = localTools,
        localToolFilters = localToolFilters,
        toolCallbacks = toolCallbacks,
        toolSelector = toolSelector,
        guard = requestGuard,
        hookExecutor = hookExecutor,
        memoryStore = memoryStore,
        mcpToolCallbacks = { mcpManager.getAllToolCallbacks() },
        errorMessageResolver = errorMessageResolver,
        agentMetrics = agentMetrics,
        ragPipeline = ragPipelineProvider.ifAvailable,
        tokenEstimator = tokenEstimator,
        conversationManager = conversationManager,
        toolApprovalPolicy = toolApprovalPolicyProvider.ifAvailable,
        pendingApprovalStore = pendingApprovalStoreProvider.ifAvailable,
        responseFilterChain = if (properties.response.filtersEnabled) responseFilterChain else null,
        circuitBreaker = circuitBreakerRegistryProvider.ifAvailable?.get("llm"),
        responseCache = responseCacheProvider.ifAvailable,
        cacheableTemperature = properties.cache.cacheableTemperature,
        fallbackStrategy = fallbackStrategyProvider.ifAvailable,
        outputGuardPipeline = outputGuardPipelineProvider.ifAvailable,
        intentResolver = intentResolverProvider.ifAvailable,
        blockedIntents = properties.intent.blockedIntents,
        transientErrorClassifier = ::defaultTransientErrorClassifier,
        tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() }
    )
}
