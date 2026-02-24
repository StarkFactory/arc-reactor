package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.approval.AlwaysApprovePolicy
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.approval.ToolNameApprovalPolicy
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.hook.impl.FeedbackMetadataCaptureHook
import com.arc.reactor.mcp.InMemoryMcpServerStore
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

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
    fun postgresRequirementMarker(environment: Environment): Any {
        val postgresRequired = environment.getProperty("arc.reactor.postgres.required", Boolean::class.java, true)
        if (!postgresRequired) {
            return Any()
        }

        val url = environment.getProperty("spring.datasource.url")?.trim().orEmpty()
        if (url.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL. Set spring.datasource.url (jdbc:postgresql://...)"
            )
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL JDBC URL. Current spring.datasource.url='$url'"
            )
        }
        return Any()
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
     * Users can override with custom [ToolApprovalPolicy] bean.
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

        // Static mode: approval list is fixed at startup.
        val toolNames = (staticToolNames + properties.toolPolicy.writeToolNames)
        return if (toolNames.isNotEmpty()) ToolNameApprovalPolicy(toolNames) else AlwaysApprovePolicy()
    }

    /**
     * Feedback Metadata Capture Hook (only when feedback feature is enabled)
     *
     * Caches execution metadata so that feedback submissions can auto-enrich
     * with query, response, toolsUsed, and durationMs via runId.
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
        authProperties: ObjectProvider<com.arc.reactor.auth.AuthProperties>
    ): StartupInfoLogger = StartupInfoLogger(environment, chatModelProvider, authProperties.ifAvailable)

    /**
     * Error Message Resolver (default: English messages)
     */
    @Bean
    @ConditionalOnMissingBean
    fun errorMessageResolver(): ErrorMessageResolver = DefaultErrorMessageResolver()

    /**
     * Agent Metrics (default: no-op)
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
}
