package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.impl.defaultTransientErrorClassifier
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.SlaMetrics
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.routing.AgentModeResolver
import com.arc.reactor.agent.routing.ModelRouter
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.cache.CacheMetricsRecorder
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpToolAvailabilityChecker
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.QueryRouter
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
 * ChatClient 가용성에 조건적인 AgentExecutor 빈.
 *
 * 컴포넌트 스캔이 자동 설정 순서(@AutoConfigureAfter) 적용 전에
 * 이 클래스를 가져가는 것을 방지하기 위해 @Configuration을 붙이지 않는다.
 * ArcReactorAutoConfiguration의 @Import를 통해서만 처리된다.
 */
class ArcReactorExecutorConfiguration {

    /**
     * 에이전트 실행기 빈.
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
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>,
        systemPromptPostProcessorProvider: ObjectProvider<SystemPromptPostProcessor>,
        toolOutputSanitizerProvider: ObjectProvider<ToolOutputSanitizer>,
        queryRouterProvider: ObjectProvider<QueryRouter>,
        mcpToolAvailabilityCheckerProvider: ObjectProvider<McpToolAvailabilityChecker>,
        modelRouterProvider: ObjectProvider<ModelRouter>,
        agentModeResolverProvider: ObjectProvider<AgentModeResolver>,
        slaMetricsProvider: ObjectProvider<SlaMetrics>,
        costCalculatorProvider: ObjectProvider<CostCalculator>,
        cacheMetricsRecorderProvider: ObjectProvider<CacheMetricsRecorder>
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
        tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() },
        systemPromptPostProcessor = systemPromptPostProcessorProvider.ifAvailable,
        toolOutputSanitizer = toolOutputSanitizerProvider.ifAvailable,
        queryRouter = queryRouterProvider.ifAvailable,
        mcpToolAvailabilityChecker = mcpToolAvailabilityCheckerProvider.ifAvailable,
        modelRouter = modelRouterProvider.ifAvailable,
        agentModeResolver = agentModeResolverProvider.ifAvailable,
        slaMetrics = slaMetricsProvider.ifAvailable,
        costCalculator = costCalculatorProvider.ifAvailable,
        cacheMetricsRecorder = cacheMetricsRecorderProvider.ifAvailable
    )
}
