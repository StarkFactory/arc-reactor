package com.arc.reactor.rag.model

/**
 * Hybrid Retrieval 실행에서 각 검색 신호별 점수를 담는 검색 결과.
 *
 * Vector Search와 BM25 각각의 기여도를 기록하여
 * 호출자가 검색 신호별 상대적 영향을 확인하거나 로깅할 수 있게 한다.
 */
data class HybridSearchResult(
    /** 문서 식별자 */
    val docId: String,
    /** 원본 문서 텍스트 */
    val content: String,
    /** Vector Search의 코사인 유사도 점수 (Vector로 검색되지 않은 경우 0.0) */
    val vectorScore: Double,
    /** BM25 관련도 점수 (BM25로 검색되지 않은 경우 0.0) */
    val bm25Score: Double,
    /** Reciprocal Rank Fusion으로 산출한 최종 융합 점수 */
    val rrfScore: Double
)
