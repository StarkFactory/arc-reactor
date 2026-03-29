package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.QueryComplexity
import com.arc.reactor.rag.impl.AdaptiveQueryRouter
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.chunking.InstrumentedDocumentChunker
import com.arc.reactor.rag.chunking.NoOpDocumentChunker
import com.arc.reactor.rag.chunking.TokenBasedDocumentChunker
import com.arc.reactor.rag.impl.Bm25WarmUpRunner
import com.arc.reactor.rag.impl.DecompositionQueryTransformer
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.HybridRagPipeline
import com.arc.reactor.rag.impl.HyDEQueryTransformer
import com.arc.reactor.rag.impl.LlmContextualCompressor
import com.arc.reactor.rag.impl.ParentDocumentRetriever
import com.arc.reactor.rag.impl.PassthroughQueryTransformer
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import com.arc.reactor.rag.search.Bm25Scorer
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** 항상 SIMPLE을 반환하는 폴백 라우터 — LLM 프로바이더가 없을 때 사용. */
private val SIMPLE_FALLBACK_ROUTER = object : QueryRouter {
    override suspend fun route(query: String) = QueryComplexity.SIMPLE
}

/**
 * RAG 자동 설정
 */
@Configuration
@ConditionalOnProperty(prefix = "arc.reactor.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class RagConfiguration {

    /**
     * 문서 검색기
     * VectorStore가 필요하다.
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
        val base: DocumentRetriever = SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = properties.rag.similarityThreshold,
            timeoutMs = properties.rag.retrievalTimeoutMs
        )
        val parentConfig = properties.rag.parentRetrieval
        if (parentConfig.enabled) {
            ragLogger.info {
                "RAG: Wrapping retriever with ParentDocumentRetriever (windowSize=${parentConfig.windowSize})"
            }
            return ParentDocumentRetriever(delegate = base, windowSize = parentConfig.windowSize)
        }
        return base
    }

    /**
     * 문서 재랭커 (기본: 단순 점수 기반)
     */
    @Bean
    @ConditionalOnMissingBean
    fun documentReranker(): DocumentReranker = SimpleScoreReranker()

    /**
     * 쿼리 변환 전략.
     * - passthrough (기본값): 변환 없이 그대로 전달
     * - hyde: 가상 문서 생성으로 검색 품질 향상
     * - decomposition: 복잡한 쿼리를 단순 하위 쿼리로 분해
     */
    @Bean
    @ConditionalOnMissingBean
    fun queryTransformer(
        chatModelProvider: ObjectProvider<ChatModelProvider>,
        properties: AgentProperties
    ): QueryTransformer {
        val mode = properties.rag.queryTransformer.trim().lowercase()
        return when (mode) {
            "hyde" -> llmTransformerOrPassthrough(chatModelProvider, ::HyDEQueryTransformer)
            "decomposition" -> llmTransformerOrPassthrough(chatModelProvider, ::DecompositionQueryTransformer)
            "passthrough", "" -> PassthroughQueryTransformer()
            else -> {
                ragLogger.warn { "RAG: Unknown query transformer '$mode', falling back to passthrough" }
                PassthroughQueryTransformer()
            }
        }
    }

    private fun llmTransformerOrPassthrough(
        chatModelProvider: ObjectProvider<ChatModelProvider>,
        factory: (ChatClient) -> QueryTransformer
    ): QueryTransformer {
        val chatClient = resolveChatClient(chatModelProvider) ?: return PassthroughQueryTransformer()
        val transformer = factory(chatClient)
        ragLogger.info { "RAG: Using ${transformer::class.simpleName}" }
        return transformer
    }

    private fun resolveChatClient(chatModelProvider: ObjectProvider<ChatModelProvider>): ChatClient? {
        val provider = chatModelProvider.ifAvailable
        if (provider == null) {
            ragLogger.warn { "RAG: ChatModelProvider is unavailable, using passthrough" }
            return null
        }
        val selectedProvider = provider.availableProviders()
            .firstOrNull { it == provider.defaultProvider() }
            ?: provider.availableProviders().firstOrNull()
        if (selectedProvider == null) {
            ragLogger.warn { "RAG: No chat providers are available, using passthrough" }
            return null
        }
        return provider.getChatClient(selectedProvider)
    }

    /**
     * 컨텍스트 압축기 — LLM 기반 문서 압축.
     * 압축이 명시적으로 활성화된 경우에만 생성된다.
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
     * BM25 스코어러 — 하이브리드 검색 활성 시에만 생성.
     * 사용자는 커스텀 [Bm25Scorer] 빈으로 재정의할 수 있다.
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
     * 하이브리드 RAG 파이프라인 (BM25 + Vector + RRF).
     *
     * @Primary로 등록하여 하이브리드 검색 활성 시 [DefaultRagPipeline]보다 우선한다.
     * arc.reactor.rag.enabled=true와 arc.reactor.rag.hybrid.enabled=true가 모두 설정된 경우에만 생성된다.
     * 폴백 빈의 @ConditionalOnMissingBean(RagPipeline::class)에 의해 [DefaultRagPipeline]이 억제된다.
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
     * 기본 RAG 파이프라인 — 하이브리드 검색 비활성 시 사용.
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
     * BM25 워밍업 러너 — 시작 시 VectorStore에서 BM25 스코어러를 재인덱싱한다.
     * [HybridRagPipeline]이 활성 상태일 때만 등록된다 (즉, 하이브리드 검색이 활성화된 경우).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(HybridRagPipeline::class)
    fun bm25WarmUpRunner(
        hybridRagPipeline: HybridRagPipeline,
        vectorStore: ObjectProvider<VectorStore>
    ): Bm25WarmUpRunner = Bm25WarmUpRunner(hybridRagPipeline, vectorStore)

    /**
     * 적응형 쿼리 라우터 — 검색 전 쿼리 복잡도를 분류한다.
     * RAG와 적응형 라우팅이 모두 활성화된 경우에만 생성된다.
     */
    @Bean
    @ConditionalOnMissingBean(QueryRouter::class)
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.adaptive-routing", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun queryRouter(
        chatModelProvider: ObjectProvider<ChatModelProvider>,
        properties: AgentProperties
    ): QueryRouter {
        val provider = chatModelProvider.ifAvailable
        if (provider == null) {
            ragLogger.warn {
                "Adaptive routing enabled but ChatModelProvider unavailable, " +
                    "routing will default to SIMPLE"
            }
            return SIMPLE_FALLBACK_ROUTER
        }
        val selectedProvider = provider.availableProviders()
            .firstOrNull { it == provider.defaultProvider() }
            ?: provider.availableProviders().firstOrNull()
        if (selectedProvider == null) {
            ragLogger.warn {
                "Adaptive routing enabled but no chat providers available, " +
                    "routing will default to SIMPLE"
            }
            return SIMPLE_FALLBACK_ROUTER
        }
        ragLogger.info { "RAG: Using AdaptiveQueryRouter (provider=$selectedProvider)" }
        return AdaptiveQueryRouter(
            chatClient = provider.getChatClient(selectedProvider),
            timeoutMs = properties.rag.adaptiveRouting.timeoutMs
        )
    }

    /**
     * 문서 청커 — 긴 문서를 더 작은 청크로 분할하여 임베딩 품질을 향상시킨다.
     * 청킹이 비활성화된 경우 NoOpDocumentChunker를 반환한다.
     * MeterRegistry가 사용 가능하면 [InstrumentedDocumentChunker]로 래핑한다.
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
