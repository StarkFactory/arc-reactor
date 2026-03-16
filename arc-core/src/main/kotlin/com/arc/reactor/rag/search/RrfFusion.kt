package com.arc.reactor.rag.search

/**
 * Reciprocal Rank Fusion (RRF) 유틸리티.
 *
 * 서로 다른 검색 방법(예: Vector Search + BM25)의 랭킹 목록을
 * 표준 RRF 공식을 사용하여 하나의 통합 랭킹으로 결합한다.
 *
 * ## RRF 공식
 * ```
 * RRF(doc) = Σ_i( weight_i / (K + rank_i(doc)) )
 * ```
 *
 * ## 왜 RRF인가?
 * - 각 검색 방법의 점수 스케일이 다르므로 직접 합산하면 편향 발생
 * - RRF는 점수 대신 **순위(rank)**만 사용하므로 스케일 불변(scale-invariant)
 * - 구현이 단순하면서도 학계에서 검증된 강건한 융합 성능
 *
 * ## K=60의 의미
 * - 상위 랭크 문서의 과도한 부스팅을 방지하는 스무딩 상수
 * - K가 클수록 순위 위치에 덜 민감 (부드러운 융합)
 * - K가 작으면 1위와 2위 간 점수 차이가 극대화됨
 * - 60은 Cormack et al. (2009) 원논문에서 제안한 표준값
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">RRF Paper (Cormack et al., 2009)</a>
 */
object RrfFusion {

    /** 표준 RRF 스무딩 상수. */
    const val K = 60.0

    /**
     * 두 랭킹 목록을 Reciprocal Rank Fusion으로 융합한다.
     *
     * 각 문서에 대해 vector 순위와 BM25 순위 각각의 역순위 점수를 가중합산하여
     * 최종 RRF 점수를 계산한다. rank는 1-based(+1 보정)로 처리한다.
     *
     * 예시: 문서 A가 vector 1위, BM25 3위일 때 (동일 가중치 0.5, K=60):
     *   RRF(A) = 0.5/(60+1) + 0.5/(60+3) = 0.00820 + 0.00794 = 0.01614
     *
     * @param vectorResults  (문서ID, 점수) 쌍, 점수 내림차순 — Vector Search 결과
     * @param bm25Results    (문서ID, 점수) 쌍, 점수 내림차순 — BM25 결과
     * @param vectorWeight   Vector Search 순위에 적용할 가중치 (기본값 0.5)
     * @param bm25Weight     BM25 순위에 적용할 가중치 (기본값 0.5)
     * @return (문서ID, RRF 점수) 쌍, 융합 점수 내림차순
     */
    fun fuse(
        vectorResults: List<Pair<String, Double>>,
        bm25Results: List<Pair<String, Double>>,
        vectorWeight: Double = 0.5,
        bm25Weight: Double = 0.5,
        k: Double = K
    ): List<Pair<String, Double>> {
        val scores = HashMap<String, Double>()

        // Vector Search 결과의 각 문서에 대해 가중 역순위 점수를 부여한다
        // rank+1: 0-based index를 1-based rank로 변환
        vectorResults.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + vectorWeight / (k + rank + 1)
        }

        // BM25 결과에 대해서도 동일하게 가중 역순위 점수를 누적한다
        // 두 검색에 모두 등장한 문서는 양쪽 점수가 합산되어 자연스럽게 부스팅된다
        bm25Results.forEachIndexed { rank, (docId, _) ->
            scores[docId] = (scores[docId] ?: 0.0) + bm25Weight / (k + rank + 1)
        }

        // 최종 RRF 점수 내림차순으로 정렬하여 반환한다
        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
}
