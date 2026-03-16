package com.arc.reactor.rag

import com.arc.reactor.rag.model.RetrievedDocument

/**
 * 문맥 압축(Contextual Compression) 인터페이스.
 *
 * 검색된 문서에서 쿼리와 관련된 정보만 추출하여 무관한 내용을 제거함으로써
 * LLM 컨텍스트 윈도우 사용을 최적화한다.
 *
 * RECOMP (Xu et al., 2024)에서 영감: "Improving Retrieval-Augmented LMs
 * with Compression and Selective Augmentation" (arXiv:2310.04408).
 *
 * ## 파이프라인 내 위치
 * ```
 * Query → Retrieve → Rerank → **Compress** → ContextBuilder → LLM
 * ```
 *
 * ## 왜 압축이 필요한가?
 * - 검색된 문서가 1000토큰이라도 쿼리와 관련된 부분은 100토큰일 수 있다
 * - 무관한 내용이 포함되면 LLM의 주의(attention)가 분산된다
 * - 토큰 비용 절감 + 응답 정확도 향상
 *
 * ## 사용 예시
 * ```kotlin
 * val compressor = LlmContextualCompressor(chatClient)
 * val compressed = compressor.compress("return policy", rerankedDocs)
 * // 문서들이 반환 정책 관련 내용만 포함하도록 압축됨
 * ```
 *
 * @see com.arc.reactor.rag.impl.LlmContextualCompressor
 */
interface ContextCompressor {

    /**
     * 쿼리와 관련된 내용만 추출하여 문서를 압축한다.
     *
     * 전혀 관련 없는 문서는 결과에서 제거된다.
     * 압축된 문서의 메타데이터는 보존된다.
     *
     * @param query 관련도 판단을 위한 사용자 쿼리
     * @param documents 압축할 문서 목록
     * @return 관련 내용만 포함하는 압축된 문서 목록 (입력보다 적을 수 있음)
     */
    suspend fun compress(query: String, documents: List<RetrievedDocument>): List<RetrievedDocument>
}
