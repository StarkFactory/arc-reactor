package com.arc.reactor.rag.model

/**
 * RAG 쿼리 모델.
 *
 * 검색 설정(topK, 필터, 리랭킹 여부)을 포함한 검색 요청을 나타낸다.
 *
 * @param query 검색 쿼리 텍스트
 * @param filters 메타데이터 필터 (예: {"source": "docs", "category": "policy"})
 * @param topK 검색할 최대 문서 수 (기본값 10)
 * @param rerank 리랭킹 적용 여부 (기본값 true)
 */
data class RagQuery(
    val query: String,
    val filters: Map<String, Any> = emptyMap(),
    val topK: Int = 10,
    val rerank: Boolean = true
)

/**
 * 검색된 문서 모델.
 *
 * Vector Store나 검색 인덱스에서 조회된 단일 문서를 나타낸다.
 *
 * @param id 문서 고유 식별자
 * @param content 문서 텍스트 내용
 * @param metadata 문서에 첨부된 메타데이터 (출처, 카테고리 등)
 * @param score 검색 관련도 점수 (0.0~1.0, 높을수록 관련도 높음)
 * @param source 문서 출처 정보 (선택적)
 */
data class RetrievedDocument(
    val id: String,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val score: Double = 0.0,
    val source: String? = null
) {
    /** 추정 토큰 수 (약 4자 = 1토큰 기준). LLM 컨텍스트 윈도우 예산 계산에 사용한다. */
    val estimatedTokens: Int get() = content.length / 4
}

/**
 * RAG 컨텍스트 (LLM에 전달되는 검색 결과).
 *
 * 검색된 문서들을 LLM 프롬프트에 포함할 수 있도록 포맷팅한 결과이다.
 *
 * @param context LLM 프롬프트에 삽입할 포맷팅된 컨텍스트 문자열
 * @param documents 검색된 문서 목록 (참조용)
 * @param totalTokens 컨텍스트의 총 추정 토큰 수
 */
data class RagContext(
    val context: String,
    val documents: List<RetrievedDocument>,
    val totalTokens: Int = 0
) {
    /** 검색된 문서가 있는지 여부 */
    val hasDocuments: Boolean get() = documents.isNotEmpty()

    companion object {
        /** 검색 결과가 없을 때 사용하는 빈 컨텍스트 */
        val EMPTY = RagContext(
            context = "",
            documents = emptyList()
        )
    }
}
