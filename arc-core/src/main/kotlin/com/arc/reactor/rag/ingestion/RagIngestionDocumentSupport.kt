package com.arc.reactor.rag.ingestion

import com.arc.reactor.rag.chunking.DocumentChunker
import org.springframework.ai.document.Document
import java.util.UUID

/**
 * RAG 수집 후보를 Spring AI Document로 변환하는 확장 함수.
 *
 * Q&A 형식의 콘텐츠와 추적용 메타데이터를 포함한 Document를 생성한다.
 *
 * @param documentId 문서 ID (기본값: 자동 생성 UUID)
 * @return 변환된 Spring AI Document
 */
fun RagIngestionCandidate.toDocument(documentId: String = UUID.randomUUID().toString()): Document {
    val metadata = linkedMapOf<String, Any>(
        "source" to "rag_ingestion_candidate",
        "runId" to runId,
        "userId" to userId,
        "capturedAt" to capturedAt.toString()
    )
    sessionId?.takeIf { it.isNotBlank() }?.let { metadata["sessionId"] = it }
    channel?.takeIf { it.isNotBlank() }?.let { metadata["channel"] = it }

    return Document(documentId, buildRagQaContent(query, response), metadata)
}

/**
 * 후보를 하나 이상의 문서로 변환한다. 청커가 제공되면 청킹을 적용한다.
 * 짧은 Q&A 문서는 일반적으로 minChunkThreshold 이하이므로 단일 청크로 유지된다.
 *
 * @param documentId 문서 ID (기본값: 자동 생성 UUID)
 * @param chunker 문서 청커 (선택적). null이면 청킹 없이 단일 문서 반환.
 * @return 청킹된 문서 목록
 */
fun RagIngestionCandidate.toDocuments(
    documentId: String = UUID.randomUUID().toString(),
    chunker: DocumentChunker? = null
): List<Document> {
    val document = this.toDocument(documentId)
    return chunker?.chunk(document) ?: listOf(document)
}

/**
 * Q&A 형식의 RAG 콘텐츠를 빌드한다.
 * "Q: ..." + "A: ..." 형식으로 구조화하여 검색 시 쿼리-응답 관계를 보존한다.
 */
private fun buildRagQaContent(query: String, response: String): String {
    return buildString {
        append("Q: ")
        append(query.trim())
        append("\n\nA: ")
        append(response.trim())
    }
}
