package com.arc.reactor.rag

/**
 * 쿼리 복잡도 분류에 기반하여 적절한 검색 전략으로 라우팅하는 인터페이스.
 *
 * Adaptive-RAG (Jeong et al., 2024)에서 영감을 받아,
 * 모든 쿼리에 동일한 검색 전략을 적용하는 대신 쿼리 복잡도를 분류하고
 * 적절한 검색 깊이를 선택한다 (또는 사소한 쿼리에 대해서는 검색을 생략한다).
 *
 * ## 왜 Adaptive Routing인가?
 * - "안녕하세요" 같은 인사에 Vector Search를 실행하면 비용 낭비
 * - 단순 팩트 조회와 복합 분석은 필요한 검색 깊이가 다르다
 * - 쿼리별 최적 전략으로 비용 절감 + 응답 품질 향상
 *
 * @see <a href="https://arxiv.org/abs/2403.14403">Adaptive-RAG Paper</a>
 */
interface QueryRouter {
    /**
     * 쿼리를 분류하고 적절한 복잡도 레벨을 반환한다.
     *
     * @param query 사용자 쿼리 텍스트
     * @return 검색 전략을 결정하는 복잡도 분류
     */
    suspend fun route(query: String): QueryComplexity
}

/**
 * 검색 전략을 결정하는 쿼리 복잡도 레벨.
 *
 * @param topKMultiplier 기본 topK에 적용되는 배율
 */
enum class QueryComplexity(val topKMultiplier: Double) {
    /** 인사, 잡담, 일반 대화 — RAG 검색을 완전히 건너뛴다. */
    NO_RETRIEVAL(0.0),

    /** 단일 팩트 조회 — 기본 topK를 사용한다. */
    SIMPLE(1.0),

    /** 멀티홉 추론, 비교, 분석 — topK를 3배로 늘린다 (최대 15로 제한). */
    COMPLEX(3.0)
}
