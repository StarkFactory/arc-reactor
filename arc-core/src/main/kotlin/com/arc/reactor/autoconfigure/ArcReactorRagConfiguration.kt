package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.chunking.InstrumentedDocumentChunker
import com.arc.reactor.rag.chunking.NoOpDocumentChunker
import com.arc.reactor.rag.chunking.TokenBasedDocumentChunker
import com.arc.reactor.rag.impl.Bm25WarmUpRunner
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.HybridRagPipeline
import com.arc.reactor.rag.impl.HyDEQueryTransformer
import com.arc.reactor.rag.impl.LlmContextualCompressor
import com.arc.reactor.rag.impl.PassthroughQueryTransformer
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import com.arc.reactor.rag.search.Bm25Scorer
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * RAG Configuration
 */
@Configuration
@ConditionalOnProperty(prefix = "arc.reactor.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class RagConfiguration {

    /**
     * Document Retriever
     * Requires VectorStore.
     */
    private val ragLogger = KotlinLogging.logger("com.arc.reactor.autoconfigure.RagConfiguration")

    @Bean
    @ConditionalOnMissingBean
    fun documentRetriever(
        vectorStoreProvider: ObjectProvider<VectorStore>,
        properties: AgentProperties
    ): DocumentRetriever {
        val vectorStore = vectorStoreProvider.ifAvailable
        if (vectorStore == null) {
            throw BeanInitializationException(
                "RAG is enabled but no VectorStore bean is configured. " +
                    "Configure a persistent VectorStore (for example, pgvector) before startup."
            )
        }
        ragLogger.info { "RAG: Using SpringAiVectorStoreRetriever (VectorStore found)" }
        return SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = properties.rag.similarityThreshold,
            timeoutMs = properties.rag.retrievalTimeoutMs
        )
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
     * Contextual Compressor — LLM-based document compression.
     * Only created when compression is explicitly enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.compression", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun contextCompressor(
        chatModelProvider: ObjectProvider<ChatModelProvider>,
        properties: AgentProperties
    ): ContextCompressor {
        val provider = chatModelProvider.ifAvailable
        if (provider == null) {
            throw BeanInitializationException(
                "RAG compression is enabled but no ChatModelProvider is available."
            )
        }
        val selectedProvider = provider.availableProviders()
            .firstOrNull { it == provider.defaultProvider() }
            ?: provider.availableProviders().firstOrNull()
        if (selectedProvider == null) {
            throw BeanInitializationException(
                "RAG compression is enabled but no chat providers are available."
            )
        }
        ragLogger.info { "RAG: Using LlmContextualCompressor" }
        return LlmContextualCompressor(
            chatClient = provider.getChatClient(selectedProvider),
            minContentLength = properties.rag.compression.minContentLength
        )
    }

    /**
     * BM25 Scorer — only created when hybrid search is enabled.
     * Users can override with a custom [Bm25Scorer] bean.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.hybrid", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun bm25Scorer(properties: AgentProperties): Bm25Scorer {
        val hybrid = properties.rag.hybrid
        ragLogger.info { "RAG Hybrid: creating Bm25Scorer (k1=${hybrid.bm25K1}, b=${hybrid.bm25B})" }
        return Bm25Scorer(k1 = hybrid.bm25K1, b = hybrid.bm25B)
    }

    /**
     * Hybrid RAG Pipeline (BM25 + Vector + RRF).
     *
     * Registered as @Primary so it takes precedence over [DefaultRagPipeline] when hybrid search is active.
     * Only created when both arc.reactor.rag.enabled=true and arc.reactor.rag.hybrid.enabled=true.
     * [DefaultRagPipeline] is suppressed by @ConditionalOnMissingBean(RagPipeline::class) on the fallback bean.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(RagPipeline::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.hybrid", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun hybridRagPipeline(
        queryTransformer: QueryTransformer,
        retriever: DocumentRetriever,
        reranker: DocumentReranker,
        bm25Scorer: Bm25Scorer,
        properties: AgentProperties
    ): HybridRagPipeline {
        val hybrid = properties.rag.hybrid
        ragLogger.info {
            "RAG Hybrid: creating HybridRagPipeline " +
                "(vectorWeight=${hybrid.vectorWeight}, bm25Weight=${hybrid.bm25Weight}, rrfK=${hybrid.rrfK})"
        }
        return HybridRagPipeline(
            retriever = retriever,
            bm25Scorer = bm25Scorer,
            queryTransformer = queryTransformer,
            reranker = reranker,
            vectorWeight = hybrid.vectorWeight,
            bm25Weight = hybrid.bm25Weight,
            rrfK = hybrid.rrfK,
            maxContextTokens = properties.rag.maxContextTokens
        )
    }

    /**
     * Default RAG Pipeline — used when hybrid search is disabled.
     */
    @Bean
    @ConditionalOnMissingBean(RagPipeline::class)
    fun ragPipeline(
        queryTransformer: QueryTransformer,
        retriever: DocumentRetriever,
        reranker: DocumentReranker,
        contextCompressorProvider: ObjectProvider<ContextCompressor>,
        properties: AgentProperties
    ): RagPipeline = DefaultRagPipeline(
        queryTransformer = queryTransformer,
        retriever = retriever,
        reranker = reranker,
        contextCompressor = contextCompressorProvider.ifAvailable,
        maxContextTokens = properties.rag.maxContextTokens
    )

    /**
     * BM25 warm-up runner — re-indexes the BM25 scorer from VectorStore on startup.
     * Only registered when [HybridRagPipeline] is active (i.e., hybrid search is enabled).
     */
    @Bean
    @ConditionalOnBean(HybridRagPipeline::class)
    fun bm25WarmUpRunner(
        hybridRagPipeline: HybridRagPipeline,
        vectorStore: ObjectProvider<VectorStore>
    ): Bm25WarmUpRunner = Bm25WarmUpRunner(hybridRagPipeline, vectorStore)

    /**
     * Document Chunker — splits long documents into smaller chunks for better embedding quality.
     * Returns NoOpDocumentChunker when chunking is disabled.
     * Wraps with [InstrumentedDocumentChunker] when MeterRegistry is available.
     */
    @Bean
    @ConditionalOnMissingBean
    fun documentChunker(
        properties: AgentProperties,
        tokenEstimatorProvider: ObjectProvider<TokenEstimator>,
        meterRegistryProvider: ObjectProvider<MeterRegistry>
    ): DocumentChunker {
        val chunking = properties.rag.chunking
        if (!chunking.enabled) {
            ragLogger.info { "RAG: Chunking disabled, using NoOpDocumentChunker" }
            return NoOpDocumentChunker()
        }
        val tokenEstimator = tokenEstimatorProvider.ifAvailable ?: DefaultTokenEstimator()
        ragLogger.info {
            "RAG: Using TokenBasedDocumentChunker " +
                "(chunkSize=${chunking.chunkSize}, overlap=${chunking.overlap})"
        }
        val base: DocumentChunker = TokenBasedDocumentChunker(
            chunkSize = chunking.chunkSize,
            minChunkSizeChars = chunking.minChunkSizeChars,
            minChunkThreshold = chunking.minChunkThreshold,
            overlap = chunking.overlap,
            keepSeparator = chunking.keepSeparator,
            maxNumChunks = chunking.maxNumChunks,
            tokenEstimator = tokenEstimator
        )
        val registry = meterRegistryProvider.ifAvailable ?: return base
        ragLogger.info { "RAG: Wrapping chunker with InstrumentedDocumentChunker" }
        return InstrumentedDocumentChunker(base, registry)
    }
}
