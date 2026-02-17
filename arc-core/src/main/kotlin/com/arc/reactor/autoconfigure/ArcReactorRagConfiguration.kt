package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.HyDEQueryTransformer
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.PassthroughQueryTransformer
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import mu.KotlinLogging
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
     * Query transformer strategy.
     * - passthrough (default): no rewrite
     * - hyde: hypothetical document generation for better retrieval
     */
    @Bean
    @ConditionalOnMissingBean
    fun queryTransformer(
        chatModelProvider: ObjectProvider<ChatModelProvider>,
        properties: AgentProperties
    ): QueryTransformer {
        return when (properties.rag.queryTransformer.trim().lowercase()) {
            "hyde" -> {
                val provider = chatModelProvider.ifAvailable
                if (provider != null) {
                    val selectedProvider = provider.availableProviders()
                        .firstOrNull { it == provider.defaultProvider() }
                        ?: provider.availableProviders().firstOrNull()
                    if (selectedProvider == null) {
                        ragLogger.warn { "RAG: HyDE requested but no chat providers are available, using passthrough" }
                        return PassthroughQueryTransformer()
                    }
                    ragLogger.info { "RAG: Using HyDEQueryTransformer" }
                    HyDEQueryTransformer(provider.getChatClient(selectedProvider))
                } else {
                    ragLogger.warn { "RAG: HyDE requested but ChatModelProvider is unavailable, using passthrough" }
                    PassthroughQueryTransformer()
                }
            }
            "passthrough", "" -> PassthroughQueryTransformer()
            else -> {
                ragLogger.warn {
                    "RAG: Unknown query transformer '${properties.rag.queryTransformer}', falling back to passthrough"
                }
                PassthroughQueryTransformer()
            }
        }
    }

    /**
     * RAG Pipeline
     */
    @Bean
    @ConditionalOnMissingBean
    fun ragPipeline(
        queryTransformer: QueryTransformer,
        retriever: DocumentRetriever,
        reranker: DocumentReranker,
        properties: AgentProperties
    ): RagPipeline = DefaultRagPipeline(
        queryTransformer = queryTransformer,
        retriever = retriever,
        reranker = reranker,
        maxContextTokens = properties.rag.maxContextTokens
    )
}
