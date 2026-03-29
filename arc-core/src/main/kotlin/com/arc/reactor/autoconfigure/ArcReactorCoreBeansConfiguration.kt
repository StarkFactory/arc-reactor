package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.MicrometerAgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.cache.CacheMetricsRecorder
import com.arc.reactor.cache.NoOpCacheMetricsRecorder
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.feedback.InMemoryFeedbackStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleStore
import com.arc.reactor.hook.impl.FeedbackMetadataCaptureHook
import com.arc.reactor.mcp.InMemoryMcpSecurityPolicyStore
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpSecurityPolicyProvider
import com.arc.reactor.mcp.McpSecurityPolicyStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.persona.InMemoryPersonaStore
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.InMemoryPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolSelector
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import io.micrometer.core.instrument.MeterRegistry

private val logger = KotlinLogging.logger {}

@Configuration
class ArcReactorCoreBeansConfiguration {

    /**
     * 도구 선택기 — 전략 기반 선택.
     *
     * - `all` (기본값): 필터링 없이 모든 도구를 반환
     * - `keyword`: 키워드 기반 카테고리 매칭
     * - `semantic`: 임베딩 기반 코사인 유사도 (EmbeddingModel 필요)
     */
    @Bean
    @ConditionalOnMissingBean
    fun toolSelector(
        properties: AgentProperties,
        embeddingModel: ObjectProvider<EmbeddingModel>
    ): ToolSelector {
        val strategy = properties.toolSelection.strategy.lowercase()
        if (strategy == "semantic") {
            val model = embeddingModel.ifAvailable
            if (model != null) {
                logger.info { "Using SemanticToolSelector (threshold=${properties.toolSelection.similarityThreshold})" }
                return SemanticToolSelector(
                    embeddingModel = model,
                    similarityThreshold = properties.toolSelection.similarityThreshold,
                    maxResults = properties.toolSelection.maxResults
                )
            }
            logger.warn {
                "SemanticToolSelector requested but no EmbeddingModel found, " +
                    "falling back to AllToolSelector"
            }
        }
        return AllToolSelector()
    }

    @Bean
    @ConditionalOnMissingBean
    fun memoryStore(meterRegistryProvider: ObjectProvider<MeterRegistry>): MemoryStore =
        InMemoryMemoryStore(meterRegistry = meterRegistryProvider.ifAvailable)

    @Bean
    @ConditionalOnMissingBean
    fun personaStore(): PersonaStore = InMemoryPersonaStore()

    @Bean
    @ConditionalOnMissingBean
    fun promptTemplateStore(): PromptTemplateStore = InMemoryPromptTemplateStore()

    @Bean
    @ConditionalOnMissingBean
    fun mcpServerStore(): McpServerStore = InMemoryMcpServerStore()

    @Bean
    @ConditionalOnMissingBean
    fun mcpSecurityPolicyStore(): McpSecurityPolicyStore = InMemoryMcpSecurityPolicyStore()

    @Bean
    @ConditionalOnMissingBean
    fun mcpSecurityPolicyProvider(
        properties: AgentProperties,
        mcpSecurityPolicyStore: McpSecurityPolicyStore
    ): McpSecurityPolicyProvider = McpSecurityPolicyProvider(
        defaultConfig = McpSecurityConfig(
            allowedServerNames = properties.mcp.security.allowedServerNames,
            maxToolOutputLength = properties.mcp.security.maxToolOutputLength
        ),
        store = mcpSecurityPolicyStore
    )

    @Bean
    @ConditionalOnMissingBean
    fun adminAuditStore(): AdminAuditStore = InMemoryAdminAuditStore()

    /**
     * 피드백 메타데이터 캡처 훅 (arc.reactor.feedback.enabled=true일 때만 활성화)
     *
     * 실행 메타데이터를 캐시하여 피드백 제출 시 runId를 통해
     * query, response, toolsUsed, durationMs를 자동 보강할 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.feedback", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun feedbackMetadataCaptureHook(): FeedbackMetadataCaptureHook = FeedbackMetadataCaptureHook()

    /**
     * 시작 정보 로거 (프로바이더, URL, 기능 플래그를 시작 시 로깅)
     */
    @Bean
    @ConditionalOnMissingBean
    fun startupInfoLogger(
        environment: Environment,
        chatModelProvider: ChatModelProvider,
        authProperties: com.arc.reactor.auth.AuthProperties
    ): StartupInfoLogger = StartupInfoLogger(environment, chatModelProvider, authProperties)

    /**
     * 도구 라우팅 설정 검증기 (시작 시 tool-routing.yml 유효성 검증)
     *
     * 등록된 도구 목록을 주입받아 preferredTools 크로스체크도 수행한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun toolRoutingValidationInitializer(
        toolCallbacks: ObjectProvider<List<com.arc.reactor.tool.ToolCallback>>
    ): ToolRoutingValidationInitializer =
        ToolRoutingValidationInitializer(toolCallbacks)

    /**
     * 에러 메시지 리졸버 (기본: 영문 메시지)
     */
    @Bean
    @ConditionalOnMissingBean
    fun errorMessageResolver(): ErrorMessageResolver = DefaultErrorMessageResolver()

    /**
     * 에이전트 메트릭 (MeterRegistry가 사용 가능할 때 Micrometer 기반).
     *
     * @Primary로 표시하여 MeterRegistry와 폴백 NoOp 빈이 모두
     * 존재할 때, 빈 등록 순서에 관계없이 이 Micrometer 구현이
     * intra-class bean definition processing order.
     */
    @Bean
    @Primary
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(AgentMetrics::class)
    fun micrometerAgentMetrics(registry: MeterRegistry): AgentMetrics = MicrometerAgentMetrics(registry)

    /**
     * 에이전트 메트릭 (기본: MeterRegistry가 없을 때 no-op)
     */
    @Bean
    @ConditionalOnMissingBean
    fun agentMetrics(): AgentMetrics = NoOpAgentMetrics()

    /**
     * LLM 요청 비용 계산기 — 모델별 토큰 가격 기반 USD 비용 산출.
     */
    @Bean
    @ConditionalOnMissingBean
    fun costCalculator(): CostCalculator = CostCalculator()

    /**
     * 토큰 추정기 (기본: 문자 유형 인식 휴리스틱)
     */
    @Bean
    @ConditionalOnMissingBean
    fun tokenEstimator(): TokenEstimator = DefaultTokenEstimator()

    /**
     * 대화 관리자 (대화 이력 생명주기 관리)
     */
    @Bean
    @ConditionalOnMissingBean
    fun conversationManager(
        memoryStore: MemoryStore,
        properties: AgentProperties,
        summaryStore: ObjectProvider<com.arc.reactor.memory.summary.ConversationSummaryStore>,
        summaryService: ObjectProvider<com.arc.reactor.memory.summary.ConversationSummaryService>,
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>
    ): ConversationManager = DefaultConversationManager(
        memoryStore = memoryStore,
        properties = properties,
        summaryStore = summaryStore.ifAvailable,
        summaryService = summaryService.ifAvailable,
        tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() }
    )

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleInvalidationBus(): OutputGuardRuleInvalidationBus = OutputGuardRuleInvalidationBus()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleEvaluator(): OutputGuardRuleEvaluator = OutputGuardRuleEvaluator()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleStore(): OutputGuardRuleStore = InMemoryOutputGuardRuleStore()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleAuditStore(): OutputGuardRuleAuditStore = InMemoryOutputGuardRuleAuditStore()

    /**
     * 컨텍스트 인식 도구 필터 — 인텐트, 채널, 역할 기반 도구 필터링.
     * 사용자는 커스텀 [com.arc.reactor.tool.filter.ToolFilter] 빈으로 재정의할 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean(com.arc.reactor.tool.filter.ToolFilter::class)
    fun toolFilter(properties: AgentProperties): com.arc.reactor.tool.filter.ToolFilter {
        return if (properties.toolFilter.enabled) {
            logger.info { "ContextAwareToolFilter 활성화" }
            com.arc.reactor.tool.filter.ContextAwareToolFilter(properties.toolFilter)
        } else {
            com.arc.reactor.tool.filter.NoOpToolFilter()
        }
    }

    /**
     * A2A 에이전트 카드 프로바이더 — 등록된 도구와 페르소나에서 카드를 자동 생성.
     * 사용자는 커스텀 [com.arc.reactor.a2a.AgentCardProvider] 빈으로 재정의할 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.a2a", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun agentCardProvider(
        properties: AgentProperties,
        personaStore: PersonaStore,
        tools: ObjectProvider<List<com.arc.reactor.tool.ToolCallback>>
    ): com.arc.reactor.a2a.AgentCardProvider {
        val toolList = tools.ifAvailable ?: emptyList()
        logger.info { "A2A AgentCardProvider 활성화 (tools=${toolList.size})" }
        return com.arc.reactor.a2a.DefaultAgentCardProvider(
            properties = properties.a2a,
            tools = toolList,
            personaStore = personaStore
        )
    }

    /**
     * 캐시 메트릭 레코더 폴백 (기본: NoOp).
     *
     * SemanticCacheConfiguration이 비활성일 때(Redis/EmbeddingModel 없는 환경)
     * CacheMetricsRecorder 빈이 등록되지 않아 NPE가 발생하는 것을 방지한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cacheMetricsRecorder(): CacheMetricsRecorder = NoOpCacheMetricsRecorder()

    /**
     * 피드백 저장소 폴백 (기본: InMemory).
     *
     * JDBC가 없는 환경에서도 FeedbackStore 빈이 존재하도록 보장한다.
     * 운영 환경에서는 JdbcFeedbackStore가 @Primary로 이 빈을 대체한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun feedbackStore(): FeedbackStore = InMemoryFeedbackStore()
}
