package com.arc.reactor.rag.impl

import com.arc.reactor.rag.model.RetrievedDocument
import mu.KotlinLogging
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

private val logger = KotlinLogging.logger {}

/**
 * 서비스 시작 시 VectorStore로부터 BM25 스코어러를 재인덱싱하는 워밍업 러너.
 *
 * 서비스 재시작 후에도 하이브리드 검색이 정상 동작하도록 보장한다.
 * 이 워밍업이 없으면 BM25 인덱스가 비어있어 Vector-only 검색으로
 * 조용히 성능이 저하될 수 있다.
 *
 * ## 동작 방식
 * 1. VectorStore에서 상위 WARM_UP_TOP_K개의 문서를 가져온다
 * 2. 각 문서를 BM25 스코어러에 인덱싱한다
 * 3. 실패해도 서비스 시작을 차단하지 않는다 (다음 indexDocuments() 호출까지 vector-only)
 */
class Bm25WarmUpRunner(
    private val hybridRagPipeline: HybridRagPipeline,
    private val vectorStore: ObjectProvider<VectorStore>
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val store = vectorStore.ifAvailable ?: run {
            logger.debug { "No VectorStore available — BM25 warm-up skipped" }
            return
        }
        try {
            // similarityThreshold=0.0으로 모든 문서를 가져온다. 쿼리 " "은 범용 쿼리.
            val springDocs: List<Document> = store.similaritySearch(
                SearchRequest.builder().query(" ").topK(WARM_UP_TOP_K).similarityThreshold(0.0).build()
            )

            if (springDocs.isEmpty()) {
                logger.debug { "VectorStore is empty — BM25 warm-up skipped" }
                return
            }

            val docs = springDocs.map { it.toRetrievedDocument() }
            hybridRagPipeline.indexDocuments(docs)
            logger.info { "BM25 warm-up complete: indexed ${docs.size} documents" }
        } catch (e: Exception) {
            // 워밍업 실패는 서비스 시작을 차단하지 않는다
            logger.warn(e) {
                "BM25 warm-up failed — hybrid search will use vector-only until next indexDocuments() call"
            }
        }
    }

    /**
     * Spring AI Document를 Arc Reactor의 RetrievedDocument로 변환한다.
     * 메타데이터에서 distance 또는 score 필드를 추출하여 점수로 사용한다.
     */
    private fun Document.toRetrievedDocument(): RetrievedDocument {
        val meta = this.metadata
        return RetrievedDocument(
            id = this.id,
            content = this.text.orEmpty(),
            metadata = meta,
            score = meta["distance"]?.toString()?.toDoubleOrNull()
                ?: meta["score"]?.toString()?.toDoubleOrNull()
                ?: 0.0,
            source = meta["source"]?.toString()
        )
    }

    companion object {
        /** 워밍업 시 가져올 최대 문서 수. 전체 코퍼스를 포괄하기 위해 넉넉하게 설정. */
        private const val WARM_UP_TOP_K = 10_000
    }
}
