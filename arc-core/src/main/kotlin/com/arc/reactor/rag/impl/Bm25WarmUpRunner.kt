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
 * Warm-up runner that re-indexes the BM25 scorer from the VectorStore on startup.
 * Ensures hybrid search works correctly after service restarts instead of silently
 * degrading to vector-only retrieval.
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
            logger.warn(e) {
                "BM25 warm-up failed — hybrid search will use vector-only until next indexDocuments() call"
            }
        }
    }

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
        private const val WARM_UP_TOP_K = 10_000
    }
}
