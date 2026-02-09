package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.auth.AdminInitializer
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
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.McpManager
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
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.LocalTool
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
import org.springframework.core.env.Environment
import org.springframework.web.server.WebFilter

/**
 * Arc Reactor Auto Configuration
 *
 * Spring Boot auto-configuration for Arc Reactor components.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties::class)
class ArcReactorAutoConfiguration {

    /**
     * Tool Selector (default: select all)
     */
    @Bean
    @ConditionalOnMissingBean
    fun toolSelector(): ToolSelector = AllToolSelector()

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
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): PersonaStore = JdbcPersonaStore(jdbcTemplate = jdbcTemplate)

        @Bean
        @Primary
        fun jdbcPromptTemplateStore(
            jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate
        ): PromptTemplateStore = JdbcPromptTemplateStore(jdbcTemplate = jdbcTemplate)
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
     * MCP Manager
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpManager(): McpManager = DefaultMcpManager()

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
                maxLength = properties.guard.maxInputLength
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
     * RAG Configuration
     */
    @Configuration
    @ConditionalOnProperty(prefix = "arc.reactor.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    class RagConfiguration {

        /**
         * Document Retriever (when using Spring AI VectorStore)
         */
        @Bean
        @ConditionalOnBean(VectorStore::class)
        @ConditionalOnMissingBean
        fun documentRetriever(
            vectorStore: VectorStore,
            properties: AgentProperties
        ): DocumentRetriever = SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = properties.rag.similarityThreshold
        )

        /**
         * Document Retriever (without VectorStore - in-memory fallback)
         */
        @Bean
        @ConditionalOnMissingBean(DocumentRetriever::class)
        fun inMemoryDocumentRetriever(): DocumentRetriever = InMemoryDocumentRetriever()

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
            val publicPaths = publicPathsCsv?.split(",")?.map { it.trim() }
                ?: listOf("/api/auth/login", "/api/auth/register")

            return AuthProperties(
                enabled = true,
                jwtSecret = environment.getProperty("arc.reactor.auth.jwt-secret", ""),
                jwtExpirationMs = environment.getProperty(
                    "arc.reactor.auth.jwt-expiration-ms", Long::class.java, 86_400_000L
                ),
                publicPaths = publicPaths
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
        conversationManager: ConversationManager
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
        conversationManager = conversationManager
    )
}
