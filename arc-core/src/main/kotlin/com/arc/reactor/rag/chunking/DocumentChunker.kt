package com.arc.reactor.rag.chunking

import org.springframework.ai.document.Document
import java.util.UUID

/**
 * RAG 검색 정확도 향상을 위해 문서를 작은 청크로 분할하는 인터페이스.
 *
 * 짧은 문서(설정된 임계값 이하)는 그대로 반환된다.
 * 구현체는 추적을 위한 청킹 메타데이터(parent_document_id, chunk_index, chunk_total)를
 * 각 청크에 추가한다.
 *
 * ## 왜 청킹이 필요한가?
 * - 긴 문서를 통째로 임베딩하면 세밀한 정보가 평균화되어 사라진다
 * - 짧은 청크는 특정 토픽에 대해 더 정확한 임베딩을 생성한다
 * - LLM 컨텍스트 윈도우에 필요한 부분만 효율적으로 포함할 수 있다
 */
interface DocumentChunker {

    /** 단일 문서를 하나 이상의 청크로 분할한다. */
    fun chunk(document: Document): List<Document>

    /** 여러 문서를 각각 독립적으로 청킹한다. */
    fun chunk(documents: List<Document>): List<Document> = documents.flatMap { chunk(it) }

    companion object {
        private const val CHUNK_ID_SEPARATOR = ":chunk:"

        /**
         * 부모 문서 ID와 청크 인덱스로부터 결정적(deterministic) 청크 ID를 생성한다.
         * UUID v3 (이름 기반 MD5)를 사용하여 유효한 UUID 문자열을 생성한다.
         *
         * 왜 UUID v3인가: PgVectorStore가 UUID 형식을 요구하므로,
         * 결정적이면서도 유효한 UUID를 생성해야 한다.
         */
        fun chunkId(parentId: String, index: Int): String =
            UUID.nameUUIDFromBytes("$parentId$CHUNK_ID_SEPARATOR$index".toByteArray()).toString()

        /**
         * 부모 문서 ID에 대해 가능한 모든 청크 ID를 도출한다.
         * 삭제 시 추가 검색 API 호출 없이 청크를 정리하기 위해 사용한다.
         */
        fun deriveChunkIds(parentId: String, maxChunks: Int): List<String> =
            (0 until maxChunks).map { chunkId(parentId, it) }

        /**
         * ID가 [chunkId]에 의해 생성된 것인지 확인한다.
         * UUID v3는 형식만으로는 임의 UUID와 구별할 수 없으므로
         * 레거시 호환성을 위해 구분자 포함 여부도 확인한다.
         */
        fun isChunkId(id: String): Boolean {
            return id.contains(CHUNK_ID_SEPARATOR)
        }
    }
}

/**
 * 청킹을 수행하지 않는 No-op 구현체.
 * 청킹이 비활성화되었을 때 사용한다.
 */
class NoOpDocumentChunker : DocumentChunker {
    override fun chunk(document: Document): List<Document> = listOf(document)
}
