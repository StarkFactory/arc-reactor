package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.cache.ResponseCache
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.resilience.CircuitBreakerRegistry
import com.arc.reactor.resilience.FallbackStrategy
import com.arc.reactor.resilience.impl.ModelFallbackStrategy
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.impl.MaxLengthResponseFilter
import com.arc.reactor.auth.AdminInitializer
import com.arc.reactor.auth.AuthRateLimitFilter
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.InMemoryUserStore
import com.arc.reactor.auth.JdbcUserStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.UserStore
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.impl.defaultTransientErrorClassifier
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.impl.WebhookNotificationHook
import com.arc.reactor.hook.impl.WebhookProperties
import com.arc.reactor.hook.impl.WriteToolBlockHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.JdbcMcpServerStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.memory.JdbcMemoryStore
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.persona.InMemoryPersonaStore
import com.arc.reactor.persona.JdbcPersonaStore
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.InMemoryPromptTemplateStore
import com.arc.reactor.prompt.JdbcPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.approval.AlwaysApprovePolicy
import com.arc.reactor.approval.InMemoryPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.approval.ToolNameApprovalPolicy
import com.arc.reactor.intent.InMemoryIntentRegistry
import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.impl.CompositeIntentClassifier
import com.arc.reactor.intent.impl.JdbcIntentRegistry
import com.arc.reactor.intent.impl.LlmIntentClassifier
import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import com.arc.reactor.policy.tool.DynamicToolApprovalPolicy
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.JdbcToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.InMemoryScheduledJobStore
import com.arc.reactor.scheduler.JdbcScheduledJobStore
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.SlackMessageSender
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.web.server.WebFilter
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Arc Reactor Auto Configuration
 *
 * Spring Boot auto-configuration for Arc Reactor components.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties::class)
class ArcReactorAutoConfiguration {

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
            logger.warn { "SemanticToolSelector requested but no EmbeddingModel found, falling back to AllToolSelector" }
        }
        return AllToolSelector()
    }

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyStore(properties: AgentProperties): ToolPolicyStore =
        InMemoryToolPolicyStore(initial = ToolPolicy.fromProperties(properties.toolPolicy))

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyProvider(properties: AgentProperties, toolPolicyStore: ToolPolicyStore): ToolPolicyProvider =
        ToolPolicyProvider(properties = properties.toolPolicy, store = toolPolicyStore)

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
        toolPolicyProvider: ToolPolicyProvider
    ): ToolApprovalPolicy {
        val staticToolNames = properties.approval.toolNames

        // Dynamic tool policy enabled: allow write-tool approval list to change at runtime.
        if (properties.toolPolicy.dynamic.enabled) {
            return DynamicToolApprovalPolicy(
                staticToolNames = staticToolNames,
                toolPolicyProvider = toolPolicyProvider
            )
        }

        // Static mode: approval list is fixed at startup.
        val toolNames = (staticToolNames + properties.toolPolicy.writeToolNames)
        return if (toolNames.isNotEmpty()) ToolNameApprovalPolicy(toolNames) else AlwaysApprovePolicy()
    }

    /**
     * Pending Approval Store — only created when HITL is enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun pendingApprovalStore(properties: AgentProperties): PendingApprovalStore {
        return InMemoryPendingApprovalStore(defaultTimeoutMs = properties.approval.timeoutMs)
    }

    /**
     * Memory Store: In-memory fallback (when no DataSource/JDBC)
     */
    @Bean
    @ConditionalOnMissingBean
    fun memoryStore(): MemoryStore = InMemoryMemoryStore()

    /**
     * JDBC Memory Store Configuration (only loaded when JDBC is on classpath)
     */
    @Configuration
    @ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
    @ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
    class JdbcMemoryStoreConfiguration {

        @Bean
        @Primary
        fun jdbcMemoryStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
            tokenEstimator: TokenEstimator
        ): MemoryStore = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, tokenEstimator = tokenEstimator)

        @Bean
        @Primary
        fun jdbcPersonaStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
            transactionTemplate: org.springframework.transaction.support.TransactionTemplate
        ): PersonaStore = JdbcPersonaStore(jdbcTemplate = jdbcTemplate, transactionTemplate = transactionTemplate)

        @Bean
        @Primary
        fun jdbcPromptTemplateStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): PromptTemplateStore = JdbcPromptTemplateStore(jdbcTemplate = jdbcTemplate)

        @Bean
        @Primary
        fun jdbcMcpServerStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): McpServerStore = JdbcMcpServerStore(jdbcTemplate = jdbcTemplate)

        @Bean
        @Primary
        fun jdbcOutputGuardRuleStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): OutputGuardRuleStore = JdbcOutputGuardRuleStore(jdbcTemplate = jdbcTemplate)

        @Bean
        @Primary
        fun jdbcOutputGuardRuleAuditStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): OutputGuardRuleAuditStore = JdbcOutputGuardRuleAuditStore(jdbcTemplate = jdbcTemplate)

        @Bean
        @Primary
        @ConditionalOnProperty(
            prefix = "arc.reactor.scheduler", name = ["enabled"],
            havingValue = "true", matchIfMissing = false
        )
        fun jdbcScheduledJobStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): ScheduledJobStore = JdbcScheduledJobStore(jdbcTemplate = jdbcTemplate)
    }

    /**
     * JDBC Tool Policy Store (dynamic tool policy only).
     *
     * Only created when:
     * - JDBC is available
     * - datasource is configured
     * - `arc.reactor.tool-policy.dynamic.enabled=true`
     */
    @Configuration
    @ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
    @ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool-policy.dynamic", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    class JdbcToolPolicyStoreConfiguration {

        @Bean
        @Primary
        fun jdbcToolPolicyStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
            transactionTemplate: org.springframework.transaction.support.TransactionTemplate
        ): ToolPolicyStore = JdbcToolPolicyStore(
            jdbcTemplate = jdbcTemplate,
            transactionTemplate = transactionTemplate
        )
    }

    /**
     * Persona Store: In-memory fallback (when no DataSource/JDBC)
     */
    @Bean
    @ConditionalOnMissingBean
    fun personaStore(): PersonaStore = InMemoryPersonaStore()

    /**
     * Prompt Template Store: In-memory fallback (when no DataSource/JDBC)
     */
    @Bean
    @ConditionalOnMissingBean
    fun promptTemplateStore(): PromptTemplateStore = InMemoryPromptTemplateStore()

    /**
     * Startup Info Logger (logs provider, URLs, feature flags on startup)
     */
    @Bean
    @ConditionalOnMissingBean
    fun startupInfoLogger(
        environment: Environment,
        chatModelProvider: ChatModelProvider,
        authProperties: ObjectProvider<AuthProperties>
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
        properties: AgentProperties
    ): ConversationManager = DefaultConversationManager(memoryStore, properties)

    /**
     * MCP Server Store: In-memory fallback
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpServerStore(): McpServerStore = InMemoryMcpServerStore()

    /**
     * Dynamic output guard rule store: in-memory fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleStore(): OutputGuardRuleStore = InMemoryOutputGuardRuleStore()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleAuditStore(): OutputGuardRuleAuditStore = InMemoryOutputGuardRuleAuditStore()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleInvalidationBus(): OutputGuardRuleInvalidationBus = OutputGuardRuleInvalidationBus()

    @Bean
    @ConditionalOnMissingBean
    fun outputGuardRuleEvaluator(): OutputGuardRuleEvaluator = OutputGuardRuleEvaluator()

    /**
     * MCP Manager
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpManager(properties: AgentProperties, mcpServerStore: McpServerStore): McpManager {
        val mcpSecurity = properties.mcp.security
        return DefaultMcpManager(
            connectionTimeoutMs = properties.mcp.connectionTimeoutMs,
            securityConfig = McpSecurityConfig(
                allowedServerNames = mcpSecurity.allowedServerNames,
                maxToolOutputLength = mcpSecurity.maxToolOutputLength
            ),
            store = mcpServerStore,
            reconnectionProperties = properties.mcp.reconnection
        )
    }

    /**
     * MCP Startup Initializer
     *
     * Seeds yml-defined servers to store and auto-connects servers on startup.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["mcpStartupInitializer"])
    fun mcpStartupInitializer(
        properties: AgentProperties,
        mcpManager: McpManager,
        mcpServerStore: McpServerStore
    ): McpStartupInitializer = McpStartupInitializer(properties, mcpManager, mcpServerStore)

    /**
     * Hook Executor
     */
    @Bean
    @ConditionalOnMissingBean
    fun hookExecutor(
        beforeStartHooks: List<BeforeAgentStartHook>,
        beforeToolCallHooks: List<BeforeToolCallHook>,
        afterToolCallHooks: List<AfterToolCallHook>,
        afterCompleteHooks: List<AfterAgentCompleteHook>
    ): HookExecutor = HookExecutor(
        beforeStartHooks = beforeStartHooks,
        beforeToolCallHooks = beforeToolCallHooks,
        afterToolCallHooks = afterToolCallHooks,
        afterCompleteHooks = afterCompleteHooks
    )

    /**
     * Webhook Notification Hook (only when arc.reactor.webhook.enabled=true)
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.webhook", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun webhookNotificationHook(properties: AgentProperties): WebhookNotificationHook {
        val config = properties.webhook
        return WebhookNotificationHook(
            webhookProperties = WebhookProperties(
                enabled = config.enabled,
                url = config.url,
                timeoutMs = config.timeoutMs,
                includeConversation = config.includeConversation
            )
        )
    }

    /**
     * Write Tool Block Hook (only when arc.reactor.tool-policy.enabled=true)
     */
    @Bean
    @ConditionalOnMissingBean(name = ["writeToolBlockHook"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool-policy", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun writeToolBlockHook(toolPolicyProvider: ToolPolicyProvider): WriteToolBlockHook =
        WriteToolBlockHook(toolPolicyProvider)

    /**
     * Guard Configuration
     */
    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["enabled"],
        havingValue = "true", matchIfMissing = true
    )
    class GuardConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = ["rateLimitStage"])
        fun rateLimitStage(properties: AgentProperties): GuardStage =
            DefaultRateLimitStage(
                requestsPerMinute = properties.guard.rateLimitPerMinute,
                requestsPerHour = properties.guard.rateLimitPerHour
            )

        @Bean
        @ConditionalOnMissingBean(name = ["inputValidationStage"])
        fun inputValidationStage(properties: AgentProperties): GuardStage =
            DefaultInputValidationStage(
                maxLength = properties.boundaries.inputMaxChars,
                minLength = properties.boundaries.inputMinChars,
                systemPromptMaxChars = properties.boundaries.systemPromptMaxChars
            )

        @Bean
        @ConditionalOnMissingBean(name = ["injectionDetectionStage"])
        @ConditionalOnProperty(
            prefix = "arc.reactor.guard", name = ["injection-detection-enabled"],
            havingValue = "true", matchIfMissing = true
        )
        fun injectionDetectionStage(): GuardStage = DefaultInjectionDetectionStage()

        @Bean
        @ConditionalOnMissingBean
        fun requestGuard(stages: List<GuardStage>): RequestGuard = GuardPipeline(stages)
    }

    /**
     * Output Guard Configuration (opt-in)
     *
     * Post-execution response validation: PII masking, pattern blocking.
     * Only active when `arc.reactor.output-guard.enabled=true`.
     */
    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.output-guard", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    class OutputGuardConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = ["piiMaskingOutputGuard"])
        @ConditionalOnProperty(
            prefix = "arc.reactor.output-guard", name = ["pii-masking-enabled"],
            havingValue = "true", matchIfMissing = true
        )
        fun piiMaskingOutputGuard(): OutputGuardStage = PiiMaskingOutputGuard()

        @Bean
        @ConditionalOnMissingBean(name = ["regexPatternOutputGuard"])
        fun regexPatternOutputGuard(properties: AgentProperties): OutputGuardStage? {
            val patterns = properties.outputGuard.customPatterns
            if (patterns.isEmpty()) return null
            return RegexPatternOutputGuard(patterns)
        }

        @Bean
        @ConditionalOnMissingBean(name = ["dynamicRuleOutputGuard"])
        @ConditionalOnProperty(
            prefix = "arc.reactor.output-guard", name = ["dynamic-rules-enabled"],
            havingValue = "true", matchIfMissing = true
        )
        fun dynamicRuleOutputGuard(
            properties: AgentProperties,
            outputGuardRuleStore: OutputGuardRuleStore,
            invalidationBus: OutputGuardRuleInvalidationBus,
            evaluator: OutputGuardRuleEvaluator
        ): OutputGuardStage = DynamicRuleOutputGuard(
            store = outputGuardRuleStore,
            refreshIntervalMs = properties.outputGuard.dynamicRulesRefreshMs,
            invalidationBus = invalidationBus,
            evaluator = evaluator
        )

        @Bean
        @ConditionalOnMissingBean
        fun outputGuardPipeline(stages: List<OutputGuardStage>): OutputGuardPipeline =
            OutputGuardPipeline(stages)
    }

    /**
     * RAG Configuration
     */
    @Configuration
    @ConditionalOnProperty(prefix = "arc.reactor.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    class RagConfiguration {

        /**
         * Document Retriever
         * Uses VectorStore when available, falls back to in-memory.
         */
        private val ragLogger = KotlinLogging.logger("com.arc.reactor.autoconfigure.RagConfiguration")

        @Bean
        @ConditionalOnMissingBean
        fun documentRetriever(
            vectorStoreProvider: ObjectProvider<VectorStore>,
            properties: AgentProperties
        ): DocumentRetriever {
            val vectorStore = vectorStoreProvider.ifAvailable
            return if (vectorStore != null) {
                ragLogger.info { "RAG: Using SpringAiVectorStoreRetriever (VectorStore found)" }
                SpringAiVectorStoreRetriever(
                    vectorStore = vectorStore,
                    defaultSimilarityThreshold = properties.rag.similarityThreshold
                )
            } else {
                ragLogger.info { "RAG: Using InMemoryDocumentRetriever (no VectorStore)" }
                InMemoryDocumentRetriever()
            }
        }

        /**
         * Document Reranker (default: simple score-based)
         */
        @Bean
        @ConditionalOnMissingBean
        fun documentReranker(): DocumentReranker = SimpleScoreReranker()

        /**
         * RAG Pipeline
         */
        @Bean
        @ConditionalOnMissingBean
        fun ragPipeline(
            retriever: DocumentRetriever,
            reranker: DocumentReranker,
            properties: AgentProperties
        ): RagPipeline = DefaultRagPipeline(
            retriever = retriever,
            reranker = reranker,
            maxContextTokens = properties.rag.maxContextTokens
        )
    }

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
     * Auth Configuration (only when arc.reactor.auth.enabled=true)
     */
    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.auth", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    @ConditionalOnClass(name = ["io.jsonwebtoken.Jwts"])
    class AuthConfiguration {

        @Bean
        fun authProperties(environment: Environment): AuthProperties {
            val publicPathsCsv = environment.getProperty("arc.reactor.auth.public-paths")
            val defaultPublicPaths = mutableListOf(
                "/api/auth/login", "/api/auth/register",
                "/v3/api-docs", "/swagger-ui", "/webjars"
            )

            // Convenience option: make health endpoint publicly accessible without requiring users to
            // override the entire public-paths list.
            val publicActuatorHealth = environment.getProperty(
                "arc.reactor.auth.public-actuator-health", Boolean::class.java, false
            )
            if (publicActuatorHealth) {
                defaultPublicPaths.add("/actuator/health")
            }

            val publicPaths = publicPathsCsv
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: defaultPublicPaths

            return AuthProperties(
                enabled = true,
                jwtSecret = environment.getProperty("arc.reactor.auth.jwt-secret", ""),
                jwtExpirationMs = environment.getProperty(
                    "arc.reactor.auth.jwt-expiration-ms", Long::class.java, 86_400_000L
                ),
                publicPaths = publicPaths,
                loginRateLimitPerMinute = environment.getProperty(
                    "arc.reactor.auth.login-rate-limit-per-minute", Int::class.java, 5
                )
            )
        }

        @Bean
        @ConditionalOnMissingBean
        fun userStore(): UserStore = InMemoryUserStore()

        @Bean
        @ConditionalOnMissingBean
        fun authProvider(userStore: UserStore): AuthProvider = DefaultAuthProvider(userStore)

        @Bean
        @ConditionalOnMissingBean
        fun jwtTokenProvider(authProperties: AuthProperties): JwtTokenProvider =
            JwtTokenProvider(authProperties)

        @Bean
        @ConditionalOnMissingBean(name = ["jwtAuthWebFilter"])
        fun jwtAuthWebFilter(
            jwtTokenProvider: JwtTokenProvider,
            authProperties: AuthProperties
        ): WebFilter = JwtAuthWebFilter(jwtTokenProvider, authProperties)

        @Bean
        @ConditionalOnMissingBean
        fun adminInitializer(
            userStore: UserStore,
            authProvider: AuthProvider
        ): AdminInitializer = AdminInitializer(userStore, authProvider)

        @Bean
        @ConditionalOnMissingBean(name = ["authRateLimitFilter"])
        fun authRateLimitFilter(authProperties: AuthProperties): WebFilter =
            AuthRateLimitFilter(maxAttemptsPerMinute = authProperties.loginRateLimitPerMinute)
    }

    /**
     * JDBC Auth Configuration (when JDBC is available and auth is enabled)
     */
    @Configuration
    @ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate", "io.jsonwebtoken.Jwts"])
    @ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
    class JdbcAuthConfiguration {

        @Bean
        @Primary
        @ConditionalOnProperty(
            prefix = "arc.reactor.auth", name = ["enabled"],
            havingValue = "true", matchIfMissing = false
        )
        fun jdbcUserStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): UserStore = JdbcUserStore(jdbcTemplate)
    }

    /**
     * Intent Classification Configuration (only when arc.reactor.intent.enabled=true)
     */
    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.intent", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    class IntentConfiguration {

        /**
         * Intent Registry: In-memory fallback
         */
        @Bean
        @ConditionalOnMissingBean
        fun intentRegistry(): IntentRegistry = InMemoryIntentRegistry()

        /**
         * Intent Classifier: Composite (Rule -> LLM cascading)
         */
        @Bean
        @ConditionalOnMissingBean
        fun intentClassifier(
            intentRegistry: IntentRegistry,
            chatModelProvider: ChatModelProvider,
            properties: AgentProperties
        ): IntentClassifier {
            val intentProps = properties.intent
            val chatClient = chatModelProvider.getChatClient(intentProps.llmModel)
            val ruleClassifier = RuleBasedIntentClassifier(intentRegistry)
            val llmClassifier = LlmIntentClassifier(
                chatClient = chatClient,
                registry = intentRegistry,
                maxExamplesPerIntent = intentProps.maxExamplesPerIntent,
                maxConversationTurns = intentProps.maxConversationTurns
            )
            return CompositeIntentClassifier(
                ruleClassifier = ruleClassifier,
                llmClassifier = llmClassifier,
                ruleConfidenceThreshold = intentProps.ruleConfidenceThreshold
            )
        }

        /**
         * Intent Resolver
         */
        @Bean
        @ConditionalOnMissingBean
        fun intentResolver(
            intentClassifier: IntentClassifier,
            intentRegistry: IntentRegistry,
            properties: AgentProperties
        ): IntentResolver = IntentResolver(
            classifier = intentClassifier,
            registry = intentRegistry,
            confidenceThreshold = properties.intent.confidenceThreshold
        )
    }

    /**
     * JDBC Intent Registry (when JDBC is available and intent is enabled)
     */
    @Configuration
    @ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
    @ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
    class JdbcIntentRegistryConfiguration {

        @Bean
        @Primary
        @ConditionalOnProperty(
            prefix = "arc.reactor.intent", name = ["enabled"],
            havingValue = "true", matchIfMissing = false
        )
        fun jdbcIntentRegistry(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): IntentRegistry = JdbcIntentRegistry(jdbcTemplate)
    }

    /**
     * Dynamic Scheduler Configuration (only when arc.reactor.scheduler.enabled=true)
     *
     * Provides cron-based MCP tool execution with dynamic job management.
     */
    @Configuration
    @ConditionalOnProperty(
        prefix = "arc.reactor.scheduler", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    class SchedulerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        fun scheduledJobStore(): ScheduledJobStore = InMemoryScheduledJobStore()

        @Bean
        @ConditionalOnMissingBean(name = ["schedulerTaskScheduler"])
        fun schedulerTaskScheduler(properties: AgentProperties): TaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = properties.scheduler.threadPoolSize
            scheduler.setThreadNamePrefix("arc-scheduler-")
            scheduler.initialize()
            return scheduler
        }

        @Bean
        @ConditionalOnMissingBean
        fun dynamicSchedulerService(
            scheduledJobStore: ScheduledJobStore,
            schedulerTaskScheduler: TaskScheduler,
            mcpManager: McpManager,
            slackMessageSender: ObjectProvider<SlackMessageSender>
        ): DynamicSchedulerService = DynamicSchedulerService(
            store = scheduledJobStore,
            taskScheduler = schedulerTaskScheduler,
            mcpManager = mcpManager,
            slackMessageSender = slackMessageSender.ifAvailable
        )
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
        outputGuardPipelineProvider: ObjectProvider<OutputGuardPipeline>
    ): AgentExecutor = SpringAiAgentExecutor(
        chatClient = chatClient,
        chatModelProvider = chatModelProvider,
        properties = properties,
        localTools = localTools,
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
        outputGuardPipeline = outputGuardPipelineProvider.ifAvailable
    )
}
