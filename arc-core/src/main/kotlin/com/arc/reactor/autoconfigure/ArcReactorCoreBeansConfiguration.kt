package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.MicrometerAgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.approval.AlwaysApprovePolicy
import com.arc.reactor.approval.InMemoryPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.approval.ToolNameApprovalPolicy
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
import com.arc.reactor.policy.tool.DynamicToolApprovalPolicy
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import com.arc.reactor.prompt.InMemoryPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolSelector
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import io.micrometer.core.instrument.MeterRegistry

private val logger = KotlinLogging.logger {}
private val tenantIdPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val LEGACY_AUTH_ENABLED_PROPERTY = "arc.reactor.auth.enabled"

@Configuration
class ArcReactorCoreBeansConfiguration {

    /**
     * Tool Selector — strategy-based selection.
     *
     * - `all` (default): returns all tools without filtering
     * - `keyword`: keyword-based category matching
     * - `semantic`: embedding-based cosine similarity (requires EmbeddingModel)
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
    fun memoryStore(): MemoryStore = InMemoryMemoryStore()

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

    @Bean
    fun runtimePreflightMarker(
        environment: Environment,
        authProperties: com.arc.reactor.auth.AuthProperties
    ): Any {
        validateLegacyAuthToggle(environment)
        validateDefaultTenantId(authProperties.defaultTenantId)
        validatePostgresRequirement(environment)
        warnAboutHealthProbeAccess(environment)
        return Any()
    }

    private fun validatePostgresRequirement(environment: Environment) {
        val postgresRequired = environment.getProperty("arc.reactor.postgres.required", Boolean::class.java, true)
        if (!postgresRequired) {
            return
        }

        val url = environment.getProperty("spring.datasource.url")?.trim().orEmpty()
        if (url.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL when arc.reactor.postgres.required=true. " +
                    "Set spring.datasource.url (jdbc:postgresql://...) or disable this check only for local " +
                    "non-production runs by setting arc.reactor.postgres.required=false."
            )
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL JDBC URL. Current spring.datasource.url='$url'"
            )
        }
        val username = environment.getProperty("spring.datasource.username")?.trim().orEmpty()
        if (username.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires spring.datasource.username when arc.reactor.postgres.required=true. " +
                    "Set SPRING_DATASOURCE_USERNAME."
            )
        }
        val password = environment.getProperty("spring.datasource.password")?.trim().orEmpty()
        if (password.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires spring.datasource.password when arc.reactor.postgres.required=true. " +
                    "Set SPRING_DATASOURCE_PASSWORD."
            )
        }
    }

    private fun validateLegacyAuthToggle(environment: Environment) {
        val rawValue = environment.getProperty(LEGACY_AUTH_ENABLED_PROPERTY)?.trim() ?: return
        val enabled = rawValue.toBooleanStrictOrNull()
            ?: throw BeanInitializationException(
                "$LEGACY_AUTH_ENABLED_PROPERTY is no longer used and only accepts true/false if present. " +
                    "Current value='$rawValue'. Remove this property and keep only arc.reactor.auth.jwt-secret."
            )
        if (!enabled) {
            throw BeanInitializationException(
                "$LEGACY_AUTH_ENABLED_PROPERTY=false is no longer supported. " +
                    "Authentication is always required in Arc Reactor runtime. " +
                    "Remove $LEGACY_AUTH_ENABLED_PROPERTY and configure arc.reactor.auth.jwt-secret."
            )
        }
        logger.warn {
            "$LEGACY_AUTH_ENABLED_PROPERTY=true is redundant and has no effect. " +
                "Remove this property and keep arc.reactor.auth.jwt-secret only."
        }
    }

    private fun validateDefaultTenantId(defaultTenantId: String) {
        val normalized = defaultTenantId.trim()
        if (!tenantIdPattern.matches(normalized)) {
            throw BeanInitializationException(
                "Invalid arc.reactor.auth.default-tenant-id='$defaultTenantId'. " +
                    "Use 1-64 chars: letters, numbers, hyphen, underscore."
            )
        }
    }

    private fun warnAboutHealthProbeAccess(environment: Environment) {
        val probesEnabled = environment.getProperty(
            "management.endpoint.health.probes.enabled",
            Boolean::class.java,
            true
        )
        val publicHealth = environment.getProperty(
            "arc.reactor.auth.public-actuator-health",
            Boolean::class.java,
            true
        )
        if (probesEnabled && !publicHealth) {
            logger.warn {
                "Health probes are enabled but arc.reactor.auth.public-actuator-health=false. " +
                    "Unauthenticated liveness/readiness probes may fail with 401. " +
                    "Set ARC_REACTOR_AUTH_PUBLIC_ACTUATOR_HEALTH=true when using unauthenticated probes."
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyStore(properties: AgentProperties): ToolPolicyStore =
        InMemoryToolPolicyStore(initial = ToolPolicy.fromProperties(properties.toolPolicy))

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyProvider(properties: AgentProperties, toolPolicyStore: ToolPolicyStore): ToolPolicyProvider =
        ToolPolicyProvider(properties = properties.toolPolicy, store = toolPolicyStore)

    @Bean
    @ConditionalOnMissingBean
    fun ragIngestionPolicyStore(properties: AgentProperties): RagIngestionPolicyStore =
        InMemoryRagIngestionPolicyStore(initial = RagIngestionPolicy.fromProperties(properties.rag.ingestion))

    @Bean
    @ConditionalOnMissingBean
    fun toolExecutionPolicyEngine(toolPolicyProvider: ToolPolicyProvider): ToolExecutionPolicyEngine =
        ToolExecutionPolicyEngine(toolPolicyProvider)

    @Bean
    @ConditionalOnMissingBean
    fun ragIngestionPolicyProvider(
        properties: AgentProperties,
        ragIngestionPolicyStore: RagIngestionPolicyStore
    ): RagIngestionPolicyProvider = RagIngestionPolicyProvider(
        properties = properties.rag.ingestion,
        store = ragIngestionPolicyStore
    )

    /**
     * Tool Approval Policy — only created when HITL is enabled.
     * 사용자는 커스텀 [ToolApprovalPolicy] 빈으로 재정의할 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun toolApprovalPolicy(
        properties: AgentProperties,
        toolExecutionPolicyEngine: ToolExecutionPolicyEngine
    ): ToolApprovalPolicy {
        val staticToolNames = properties.approval.toolNames

        // Dynamic tool policy enabled: allow write-tool approval list to change at runtime.
        if (properties.toolPolicy.dynamic.enabled) {
            return DynamicToolApprovalPolicy(
                staticToolNames = staticToolNames,
                toolExecutionPolicyEngine = toolExecutionPolicyEngine
            )
        }

        // Static mode: approval list is fixed at startup, but argument-aware safety rules still apply.
        val toolNames = (staticToolNames + properties.toolPolicy.writeToolNames)
        return if (toolNames.isNotEmpty()) {
            DynamicToolApprovalPolicy(
                staticToolNames = staticToolNames,
                toolExecutionPolicyEngine = toolExecutionPolicyEngine
            )
        } else {
            AlwaysApprovePolicy()
        }
    }

    @Bean
    @ConditionalOnMissingBean(PendingApprovalStore::class)
    @ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() == 0")
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun pendingApprovalStore(properties: AgentProperties): PendingApprovalStore =
        InMemoryPendingApprovalStore(defaultTimeoutMs = properties.approval.timeoutMs)

    /**
     * Feedback Metadata Capture Hook (only when feedback feature is enabled)
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
     * Startup Info Logger (logs provider, URLs, feature flags on startup)
     */
    @Bean
    @ConditionalOnMissingBean
    fun startupInfoLogger(
        environment: Environment,
        chatModelProvider: ChatModelProvider,
        authProperties: com.arc.reactor.auth.AuthProperties
    ): StartupInfoLogger = StartupInfoLogger(environment, chatModelProvider, authProperties)

    /**
     * Error Message Resolver (default: English messages)
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
    fun micrometerAgentMetrics(registry: MeterRegistry): AgentMetrics = MicrometerAgentMetrics(registry)

    /**
     * 에이전트 메트릭 (기본: MeterRegistry가 없을 때 no-op)
     */
    @Bean
    @ConditionalOnMissingBean
    fun agentMetrics(): AgentMetrics = NoOpAgentMetrics()

    /**
     * Token Estimator (default: character-type-aware heuristic)
     */
    @Bean
    @ConditionalOnMissingBean
    fun tokenEstimator(): TokenEstimator = DefaultTokenEstimator()

    /**
     * Conversation Manager (manages conversation history lifecycle)
     */
    @Bean
    @ConditionalOnMissingBean
    fun conversationManager(
        memoryStore: MemoryStore,
        properties: AgentProperties,
        summaryStore: ObjectProvider<com.arc.reactor.memory.summary.ConversationSummaryStore>,
        summaryService: ObjectProvider<com.arc.reactor.memory.summary.ConversationSummaryService>
    ): ConversationManager = DefaultConversationManager(
        memoryStore = memoryStore,
        properties = properties,
        summaryStore = summaryStore.ifAvailable,
        summaryService = summaryService.ifAvailable
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
}
