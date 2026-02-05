package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
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
 * Spring Boot 자동 설정.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties::class)
class ArcReactorAutoConfiguration {

    /**
     * Tool Selector (기본: 전체 선택)
     */
    @Bean
    @ConditionalOnMissingBean
    fun toolSelector(): ToolSelector = AllToolSelector()

    /**
     * Memory Store (기본: 인메모리)
     */
    @Bean
    @ConditionalOnMissingBean
    fun memoryStore(): MemoryStore = InMemoryMemoryStore()

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
         * Document Retriever (Spring AI VectorStore 사용 시)
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
         * Document Retriever (VectorStore 없을 때 - 인메모리)
         */
        @Bean
        @ConditionalOnMissingBean(VectorStore::class, DocumentRetriever::class)
        fun inMemoryDocumentRetriever(): DocumentRetriever = InMemoryDocumentRetriever()

        /**
         * Document Reranker (기본: 단순 점수 기반)
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
            reranker: DocumentReranker
        ): RagPipeline = DefaultRagPipeline(
            retriever = retriever,
            reranker = reranker
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
        mcpManager: McpManager
    ): AgentExecutor = SpringAiAgentExecutor(
        chatClient = chatClient,
        properties = properties,
        localTools = localTools,
        toolCallbacks = toolCallbacks,
        toolSelector = toolSelector,
        guard = requestGuard,
        hookExecutor = hookExecutor,
        memoryStore = memoryStore,
        mcpToolCallbacks = { mcpManager.getAllToolCallbacks() }
    )
}
