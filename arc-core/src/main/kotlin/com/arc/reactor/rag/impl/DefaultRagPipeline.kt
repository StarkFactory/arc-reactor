package com.arc.reactor.rag.impl

import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.rag.ContextBuilder
import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 기본 RAG 파이프라인 구현체.
 *
 * 파이프라인 단계: Query → Transform → Retrieve → Rerank → [Compress] → Build Context
 *
 * 각 단계는 선택적(optional)이며 null이면 건너뛴다.
 *
 * @see RagPipeline 인터페이스 계약
 */
class DefaultRagPipeline(
    private val queryTransformer: QueryTransformer? = null,
    private val retriever: DocumentRetriever,
    private val reranker: DocumentReranker? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline {

    override suspend fun retrieve(query: RagQuery): RagContext {
        logger.debug { "RAG 파이프라인 시작: ${query.query}" }

        // 1단계: 쿼리 변환 (HyDE, 분해, 대화 맥락 인식 등)
        val transformedQueries = if (queryTransformer != null) {
            queryTransformer.transform(query.query)
        } else {
            listOf(query.query)
        }

        // 2단계: 문서 검색
        val documents = retriever.retrieve(transformedQueries, query.topK, query.filters)

        if (documents.isEmpty()) {
            logger.info { "RAG 검색 결과 없음: ${query.query}" }
            return RagContext.EMPTY
        }

        logger.debug { "${documents.size}개 문서 검색 완료" }

        // 3단계: 리랭킹 (선택적)
        val rerankedDocs = if (query.rerank && reranker != null) {
            reranker.rerank(query.query, documents, query.topK)
        } else {
            documents.take(query.topK)
        }

        // 4단계: 압축 (선택적, 리랭킹 후 컨텍스트 빌드 전에 수행)
        val compressedDocs = if (contextCompressor != null) {
            contextCompressor.compress(query.query, rerankedDocs)
        } else {
            rerankedDocs
        }

        if (compressedDocs.isEmpty()) {
            return RagContext.EMPTY
        }

        // 5단계: 컨텍스트 빌드 (토큰 제한 적용)
        val context = contextBuilder.build(compressedDocs, maxContextTokens)

        return RagContext(
            context = context,
            documents = compressedDocs,
            totalTokens = tokenEstimator.estimate(context)
        )
    }
}

/**
 * 단순 컨텍스트 빌더.
 *
 * 문서들을 번호를 붙여 구분자로 연결한다.
 * 토큰 예산을 초과하는 문서는 포함하지 않는다.
 *
 * @param separator 문서 간 구분자 (기본값: 줄바꿈 + 구분선)
 */
class SimpleContextBuilder(
    private val separator: String = "\n\n---\n\n"
) : ContextBuilder {

    override fun build(documents: List<RetrievedDocument>, maxTokens: Int): String {
        val sb = StringBuilder()
        var currentTokens = 0
        var docIndex = 1

        for (doc in documents) {
            val docTokens = doc.estimatedTokens
            // 토큰 예산을 초과하면 더 이상 문서를 추가하지 않는다
            if (currentTokens + docTokens > maxTokens) {
                break
            }

            if (sb.isNotEmpty()) {
                sb.append(separator)
            }

            // [번호]와 출처 정보를 포함하여 LLM이 인용할 수 있게 한다
            sb.append("[$docIndex]")
            doc.source?.let { sb.append(" Source: $it") }
            sb.append("\n")
            sb.append(doc.content)

            currentTokens += docTokens
            docIndex++
        }

        return sb.toString()
    }
}

/**
 * 패스스루(pass-through) 쿼리 변환기.
 *
 * 쿼리를 변환하지 않고 그대로 반환한다.
 * 쿼리 변환이 필요 없을 때 사용한다.
 */
class PassthroughQueryTransformer : QueryTransformer {
    override suspend fun transform(query: String): List<String> = listOf(query)
}
