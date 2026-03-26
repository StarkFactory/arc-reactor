package com.arc.reactor.rag.search

import mu.KotlinLogging
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln

private val logger = KotlinLogging.logger {}

/**
 * 인메모리 BM25 스코어러.
 *
 * 키워드 기반 관련도 스코어링을 위한 BM25F 랭킹 함수 구현체.
 * 고유명사(팀명, 시스템명, 인명) 검색에 특히 효과적이다.
 * Vector Search는 어휘 불일치(vocabulary mismatch)로 이런 고유명사를 놓칠 수 있지만,
 * BM25는 정확한 토큰 매칭으로 이를 보완한다.
 *
 * ## BM25 공식
 * ```
 * BM25(q, d) = Σ IDF(t) * (tf(t,d) * (k1 + 1)) / (tf(t,d) + k1 * (1 - b + b * |d| / avgdl))
 * ```
 * - **IDF(t)**: 역문서빈도. 희귀한 단어일수록 높은 가중치
 * - **tf(t,d)**: 문서 d에서 단어 t의 출현 빈도
 * - **k1**: 단어 빈도 포화 파라미터. 높을수록 빈도 차이에 민감
 * - **b**: 문서 길이 정규화 파라미터. 1.0이면 긴 문서에 강한 페널티
 * - **|d|/avgdl**: 해당 문서 길이와 평균 문서 길이의 비율
 *
 * @param k1 단어 빈도 포화 파라미터 (기본값 1.5 — 학계에서 가장 널리 쓰이는 표준값)
 * @param b  문서 길이 정규화 파라미터 (기본값 0.75 — 긴 문서에 적절한 페널티 부여)
 */
class Bm25Scorer(
    private val k1: Double = 1.5,
    private val b: Double = 0.75
) {

    /**
     * ReadWriteLock으로 읽기/쓰기 동시성을 분리한다.
     * - index/clear: write lock (배타적)
     * - search/score/getContent/size: read lock (동시 허용)
     * - tokenize(): 순수 함수이므로 락 밖에서 실행 (CPU-bound 작업이 검색을 블로킹하지 않음)
     */
    private val rwLock = ReentrantReadWriteLock()

    /** 문서 ID → 원본 텍스트 내용. LinkedHashMap으로 삽입 순서를 유지한다. */
    private val docContents: MutableMap<String, String> = LinkedHashMap()

    /** 문서 ID → (토큰 → 빈도) 맵. 각 문서의 단어 빈도(term frequency)를 저장한다. */
    private val termFrequencies: MutableMap<String, Map<String, Int>> = LinkedHashMap()

    /** 문서 ID → 문서 길이(토큰 수). 인덱싱 시 미리 계산하여 쿼리 시 재계산을 방지한다. */
    private val documentLengths: MutableMap<String, Int> = LinkedHashMap()

    /** 토큰 → 해당 토큰을 포함하는 문서 수. IDF 계산에 사용된다. */
    private val documentFrequency: MutableMap<String, Int> = HashMap()

    /** 토큰 → 미리 계산된 IDF 값. 인덱스 변경 시 무효화된다. */
    private var idfCache: Map<String, Double> = emptyMap()

    /** 전체 인덱싱된 문서들의 토큰 수 합계. 평균 문서 길이 계산에 사용된다. */
    private var totalLength: Long = 0L

    /**
     * 평균 문서 길이 (토큰 기준).
     * BM25 공식에서 문서 길이 정규화(b 파라미터)에 사용된다.
     * 문서가 없으면 0으로 나누는 것을 방지하기 위해 1.0을 반환한다.
     */
    private val averageLength: Double get() =
        if (termFrequencies.isEmpty()) 1.0 else totalLength.toDouble() / termFrequencies.size

    /**
     * 인덱스에 문서를 추가하거나 교체한다.
     *
     * 이미 같은 docId가 존재하면 기존 통계를 제거한 후 새로 인덱싱한다.
     * @Synchronized로 동시 접근 시 인덱스 일관성을 보장한다.
     *
     * @param docId   고유 문서 식별자
     * @param content 원본 문서 텍스트
     */
    fun index(docId: String, content: String) {
        // tokenize는 순수 함수 — 락 밖에서 실행하여 검색을 블로킹하지 않는다
        val tokens = tokenize(content)
        val tf = tokens.groupingBy { it }.eachCount()

        rwLock.write {
            // 재인덱싱 시: 기존 문서의 통계를 역산하여 제거한다
            val existing = termFrequencies[docId]
            if (existing != null) {
                totalLength -= existing.values.sum()
                for (token in existing.keys) {
                    val df = documentFrequency[token] ?: 1
                    if (df <= 1) documentFrequency.remove(token) else documentFrequency[token] = df - 1
                }
            }

            // 새 문서 통계를 인덱스에 반영한다
            docContents[docId] = content
            termFrequencies[docId] = tf
            documentLengths[docId] = tokens.size
            totalLength += tokens.size
            for (token in tf.keys) {
                documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
            }
            idfCache = emptyMap()
        }
        logger.debug { "BM25: indexed doc=$docId tokens=${tokens.size}" }
    }

    /**
     * 쿼리에 대한 특정 문서의 BM25 스코어를 계산한다.
     *
     * @param query 검색 쿼리 원본 텍스트
     * @param docId 스코어를 계산할 문서 ID
     * @return BM25 관련도 점수 (문서가 없으면 0.0)
     */
    fun score(query: String, docId: String): Double = rwLock.read { scoreInternal(query, docId) }

    /**
     * 인덱스를 검색하여 BM25 스코어 내림차순으로 상위 K개 문서를 반환한다.
     *
     * @param query 검색 쿼리 텍스트
     * @param topK  최대 결과 수
     * @return (문서ID, 스코어) 쌍 목록. 최고 스코어 순서
     */
    fun search(query: String, topK: Int): List<Pair<String, Double>> {
        val queryTokens = tokenize(query).toSet() // 순수 함수, 락 불필요

        return rwLock.read {
            if (termFrequencies.isEmpty()) return@read emptyList()
            val idf = getIdf()
            val avgLen = averageLength

            // min-heap으로 O(N log K) — 전체 정렬 O(N log N) 대비 개선
            val heap = java.util.PriorityQueue<Pair<String, Double>>(topK + 1, compareBy { it.second })
            for ((docId, tf) in termFrequencies) {
                val docLen = (documentLengths[docId] ?: tf.values.sum()).toDouble()
                val s = scoreWithTokens(queryTokens, tf, docLen, idf, avgLen)
                if (s > 0.0) {
                    heap.add(docId to s)
                    if (heap.size > topK) heap.poll()
                }
            }
            heap.sortedByDescending { it.second }
        }
    }

    /**
     * 인덱싱된 문서의 원본 텍스트를 ID로 조회한다.
     *
     * @param docId 문서 식별자
     * @return 원본 텍스트. 인덱싱되지 않은 문서이면 null
     */
    fun getContent(docId: String): String? = rwLock.read { docContents[docId] }

    /** 인덱싱된 모든 문서를 제거한다. */
    fun clear() = rwLock.write {
        docContents.clear()
        termFrequencies.clear()
        documentLengths.clear()
        documentFrequency.clear()
        idfCache = emptyMap()
        totalLength = 0L
    }

    /** 인덱싱된 문서 수. */
    val size: Int get() = rwLock.read { termFrequencies.size }

    // -------------------------------------------------------------------------
    // 내부 헬퍼 메서드
    // -------------------------------------------------------------------------

    /**
     * BM25 스코어 계산 내부 로직.
     *
     * 1) 쿼리를 토큰화하여 고유 토큰 집합을 만든다
     * 2) 각 쿼리 토큰에 대해:
     *    - 해당 토큰의 문서 내 빈도(tf)를 조회한다
     *    - IDF 캐시에서 역문서빈도를 가져온다
     *    - BM25 공식의 분자/분모를 계산한다:
     *      분자 = tf * (k1 + 1)
     *      분모 = tf + k1 * (1 - b + b * docLength / avgLength)
     *    - IDF * (분자/분모)를 합산한다
     * 3) 모든 쿼리 토큰의 스코어 합을 반환한다
     */
    private fun scoreInternal(query: String, docId: String): Double {
        val tf = termFrequencies[docId] ?: return 0.0
        val docLen = (documentLengths[docId] ?: tf.values.sum()).toDouble()
        return scoreWithTokens(tokenize(query).toSet(), tf, docLen, getIdf(), averageLength)
    }

    /** 사전 토큰화된 쿼리 토큰으로 BM25 스코어를 계산한다. search()에서 반복 호출 최적화용. */
    private fun scoreWithTokens(
        queryTokens: Set<String>,
        tf: Map<String, Int>,
        docLength: Double,
        idf: Map<String, Double>,
        avgLen: Double
    ): Double {
        return queryTokens.sumOf { token ->
            val termFreq = tf[token]?.toDouble() ?: 0.0
            val idfScore = idf[token] ?: 0.0
            val numerator = termFreq * (k1 + 1)
            val denominator = termFreq + k1 * (1 - b + b * docLength / avgLen)
            idfScore * (numerator / denominator)
        }
    }

    /**
     * IDF(역문서빈도) 캐시를 조회하거나 재계산한다.
     *
     * IDF 공식: ln((N - df + 0.5) / (df + 0.5) + 1)
     * - N: 전체 문서 수
     * - df: 해당 토큰을 포함하는 문서 수
     * - +0.5: 스무딩(smoothing) — df=0 또는 df=N일 때의 극단적 IDF 값을 방지
     * - +1: 음수 IDF 방지 — 모든 문서에 존재하는 토큰이라도 IDF >= 0이 되도록
     *
     * 왜 ln(자연로그)를 사용하는가: 문서 빈도와 중요도 간의 관계가 로그적이므로,
     * 매우 희귀한 단어와 약간 희귀한 단어 간의 중요도 차이를 적절히 압축한다.
     */
    private fun getIdf(): Map<String, Double> {
        if (idfCache.isNotEmpty()) return idfCache
        val n = termFrequencies.size.toDouble()
        idfCache = documentFrequency.mapValues { (_, df) ->
            ln((n - df + 0.5) / (df + 0.5) + 1)
        }
        return idfCache
    }

    /**
     * 텍스트를 검색 가능한 토큰으로 분리한다.
     *
     * ASCII/라틴 문자: 소문자 변환 후 비영숫자 문자로 분리.
     * 한국어 텍스트: 공백 단위의 전체 토큰 유지 + 길이 >= MIN_TOKEN_LENGTH인
     * 연속 한글 부분 문자열을 추가 생성하여 부분 매칭을 지원한다.
     *
     * 예시: "플랫폼팀은" → ["플랫폼팀은", "플랫폼", "플랫폼팀", "랫폼팀", "랫폼팀은", ...]
     * 이렇게 하면 "플랫폼팀"으로 검색해도 "플랫폼팀은"이 포함된 문서를 찾을 수 있다.
     *
     * 왜 서브스트링을 생성하는가: 한국어는 조사(은, 는, 이, 가 등)가 붙어서
     * 정확한 토큰 매칭이 어렵기 때문이다. 영어의 stemming에 해당하는 처리.
     */
    private fun tokenize(text: String): List<String> {
        val normalized = text.lowercase()
        val words = normalized.split(TOKEN_SPLIT_REGEX).filter { it.length >= MIN_TOKEN_LENGTH }
        val extra = mutableListOf<String>()
        for (word in words) {
            // 한국어 문자가 포함된 단어에 대해 2~MAX_NGRAM_LENGTH 길이의 n-gram을 생성하여
            // 부분 접두사 쿼리도 매칭되도록 한다 (O(n²) → O(n) 제한)
            val koreanRuns = KOREAN_RUN_REGEX.findAll(word)
            for (run in koreanRuns) {
                val s = run.value
                for (start in s.indices) {
                    for (len in MIN_TOKEN_LENGTH..minOf(MAX_NGRAM_LENGTH, s.length - start)) {
                        val sub = s.substring(start, start + len)
                        if (sub != word) extra.add(sub)
                    }
                }
            }
        }
        return words + extra
    }

    companion object {
        /** 최소 토큰 길이. 1글자 토큰은 노이즈가 많으므로 2 이상으로 설정한다. */
        private const val MIN_TOKEN_LENGTH = 2
        /** 한국어 n-gram 최대 길이. O(n²) 부분 문자열 생성을 제한한다. */
        private const val MAX_NGRAM_LENGTH = 4
        /** 비영숫자/비한글 문자를 기준으로 토큰을 분리하는 정규식 */
        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9가-힣]+")
        /** 연속 한글 문자열을 찾는 정규식 (최소 MIN_TOKEN_LENGTH 글자) */
        private val KOREAN_RUN_REGEX = Regex("[가-힣]{$MIN_TOKEN_LENGTH,}")
    }
}
