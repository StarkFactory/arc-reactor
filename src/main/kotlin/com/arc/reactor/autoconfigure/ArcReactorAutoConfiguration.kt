package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
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
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.memory.MemoryStore
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
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
     * Memory Store (default: in-memory)
     */
    @Bean
    @ConditionalOnMissingBean
    fun memoryStore(): MemoryStore = InMemoryMemoryStore()

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
    @ConditionalOnProperty(prefix = "arc.reactor.guard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
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
        @ConditionalOnProperty(prefix = "arc.reactor.guard", name = ["injection-detection-enabled"], havingValue = "true", matchIfMissing = true)
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
     * Agent Executor
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatClient::class)
    fun agentExecutor(
        chatClient: ChatClient,
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
        ragPipelineProvider: ObjectProvider<RagPipeline>
    ): AgentExecutor = SpringAiAgentExecutor(
        chatClient = chatClient,
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
        ragPipeline = ragPipelineProvider.ifAvailable
    )
}
