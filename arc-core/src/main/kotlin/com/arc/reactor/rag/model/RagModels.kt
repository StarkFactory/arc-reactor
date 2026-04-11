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
    /**
     * 추정 토큰 수. LLM 컨텍스트 윈도우 예산 계산에 사용한다.
     *
     * R328 fix: 기존 구현은 `content.length / 4`(영문 기준 ~4 chars/token)를 모든 문자에 동일
     * 적용하여 CJK(한글/한자/가나) 문서를 **2.6배 과소 평가**했다. 한국어 1글자는 BPE 토크나이저
     * 기준 약 1 토큰이므로 4로 나누면 실제 토큰 수의 25%만 계산된 셈.
     * 이로 인해 `DefaultRagPipeline`이 컨텍스트 예산을 과다 산정하여 LLM 컨텍스트 윈도우를
     * **overflow**시키는 상황이 발생했다.
     *
     * 개선 공식:
     * - CJK 문자(한글/한자/가나): 1글자 = 1 토큰
     * - 그 외(영문/숫자/기호): 4자 = 1 토큰
     *
     * 문자열을 단 한 번만 순회하여 두 카테고리를 동시에 세고 합친다.
     * 정확한 토크나이저(BPE, tiktoken)는 아니지만 보수적 상한을 제공하여 context overflow를 방지.
     * 더 정확한 값이 필요하면 `DefaultRagPipeline` 수준에서 `TokenEstimator`를 주입해 대체할 수 있다.
     */
    val estimatedTokens: Int get() {
        var cjkCount = 0
        var otherCount = 0
        for (ch in content) {
            if (isCjkChar(ch)) cjkCount++ else otherCount++
        }
        return cjkCount + (otherCount / 4)
    }

    /** 한글/한자/가나 Unicode 블록 판별. 정확한 토크나이저는 아니지만 언어별 정규화 유용. */
    private fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return when (code) {
            // CJK Unified Ideographs: 4E00-9FFF
            in 0x4E00..0x9FFF -> true
            // Hangul Syllables: AC00-D7A3
            in 0xAC00..0xD7A3 -> true
            // Hangul Jamo: 1100-11FF (초성/중성/종성)
            in 0x1100..0x11FF -> true
            // Hiragana: 3040-309F
            in 0x3040..0x309F -> true
            // Katakana: 30A0-30FF
            in 0x30A0..0x30FF -> true
            // CJK Compatibility Ideographs: F900-FAFF
            in 0xF900..0xFAFF -> true
            else -> false
        }
    }
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
