package com.arc.reactor.memory

import com.arc.reactor.util.HashUtils
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/**
 * 대화 메모리 관리를 위한 토큰 추정 전략.
 *
 * 텍스트 콘텐츠의 대략적인 토큰 수를 제공한다.
 * [InMemoryConversationMemory]에서 토큰 제한을 적용할 때 사용된다.
 *
 * ## 커스텀 구현 예시
 * ```kotlin
 * val fixedEstimator = TokenEstimator { text -> text.length / 4 }
 * val memory = InMemoryConversationMemory(tokenEstimator = fixedEstimator)
 * ```
 *
 * @see DefaultTokenEstimator 기본 휴리스틱 구현체
 */
fun interface TokenEstimator {
    /**
     * 주어진 텍스트의 토큰 수를 추정한다.
     *
     * @param text 토큰 수를 추정할 텍스트
     * @return 대략적인 토큰 수
     */
    fun estimate(text: String): Int
}

/**
 * 문자 유형별 휴리스틱을 사용하는 기본 토큰 추정기.
 *
 * Caffeine 캐시(최대 50,000 항목, 5분 TTL)를 적용하여 동일 콘텐츠에 대한
 * 반복적인 codePoints() 순회를 피한다.
 *
 * [CACHE_KEY_MAX_LENGTH]보다 긴 문자열은 SHA-256 해시를 캐시 키로 사용한다.
 * 이는 큰 문자열이 캐시 키로 유지되어 힙 메모리를 팽창시키는 것을 방지하면서도
 * 대용량 대화 히스토리의 캐시 히트율을 보장한다.
 *
 * ## 문자 유형별 토큰 비율
 * - 라틴/ASCII: 약 4자 = 1토큰 (영어 텍스트 기준)
 * - CJK (한국어/중국어/일본어): 약 1.5자 = 1토큰
 *   → 왜 1.5인가: CJK 문자는 보통 단일 유니코드 문자가 의미 단위이며,
 *     BPE 토크나이저에서 1~2 토큰으로 인코딩된다
 * - 이모지: 약 1자 = 1토큰 (이모지 하나가 보통 1토큰)
 * - 기타: 약 3자 = 1토큰
 */
class DefaultTokenEstimator : TokenEstimator {

    companion object {
        /**
         * 이 길이 이상의 문자열은 SHA-256 해시를 캐시 키로 사용한다.
         * 큰 도구 출력이나 문서 청크가 캐시 키로 유지되는 것을 방지하면서도
         * 대용량 대화 히스토리의 캐시 히트율을 보장한다.
         */
        const val CACHE_KEY_MAX_LENGTH = 2_000

        /** 토큰 추정 결과 캐시의 최대 항목 수 */
        private const val TOKEN_CACHE_MAX_SIZE = 50_000L

        /** 토큰 추정 결과 캐시의 접근 기반 만료 시간 (분) */
        private const val TOKEN_CACHE_EXPIRE_MINUTES = 5L
    }

    /** 토큰 추정 결과 캐시. 동일 텍스트의 반복 계산을 피한다. */
    private val cache = Caffeine.newBuilder()
        .maximumSize(TOKEN_CACHE_MAX_SIZE)
        .expireAfterAccess(TOKEN_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
        .build<String, Int>()

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val cacheKey = if (text.length <= CACHE_KEY_MAX_LENGTH) text
            else HashUtils.sha256Hex(text)
        return cache.get(cacheKey) { computeTokens(text) }
    }

    /**
     * 문자 유형별로 분류하여 토큰 수를 계산한다.
     *
     * Unicode 코드 포인트를 순회하며 각 문자를 다음 카테고리로 분류:
     * 1. 이모지 (U+1F300~U+1FAFF, U+2600~U+27BF, U+FE00~U+FE0F)
     * 2. CJK 문자 (한자 U+4E00~U+9FFF, 한글 U+AC00~U+D7AF, 히라가나/가타카나)
     * 3. 라틴/ASCII (U+0000~U+007F)
     * 4. 기타 유니코드 문자
     *
     * 각 카테고리별 경험적 비율을 적용하여 최종 토큰 수를 산출한다.
     * 최소 1토큰을 보장한다 (빈 문자열이 아닌 경우).
     */
    private fun computeTokens(text: String): Int {
        var latinChars = 0
        var cjkChars = 0
        var emojiChars = 0
        var otherChars = 0

        text.codePoints().forEach { cp ->
            when {
                cp in 0x1F300..0x1FAFF ||  // 이모티콘, 심볼, 픽토그래프
                    cp in 0x2600..0x27BF || // 기타 심볼, 딩뱃
                    cp in 0xFE00..0xFE0F   // 변형 선택자 (이모지 수식)
                    -> emojiChars++
                cp in 0x4E00..0x9FFF ||    // CJK 통합 한자
                    cp in 0xAC00..0xD7AF || // 한글 음절
                    cp in 0x3040..0x309F || // 히라가나
                    cp in 0x30A0..0x30FF   // 가타카나
                    -> cjkChars++
                cp <= 0x7F -> latinChars++ // ASCII (라틴 기본)
                else -> otherChars++       // 아랍어, 키릴 문자 등 기타
            }
        }

        val latinTokens = latinChars / 4          // 영어: 평균 4자 = 1토큰
        val cjkTokens = (cjkChars * 2 + 1) / 3   // CJK: 약 1.5자 = 1토큰 (정수 반올림)
        val emojiTokens = emojiChars              // 이모지: 1자 = 1토큰
        val otherTokens = otherChars / 3          // 기타: 약 3자 = 1토큰

        return maxOf(1, latinTokens + cjkTokens + emojiTokens + otherTokens)
    }
}
