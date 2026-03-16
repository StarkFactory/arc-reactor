package com.arc.reactor.response

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [ResponseFilter] 목록을 순서대로 응답 콘텐츠에 적용하는 체인.
 *
 * 필터는 [ResponseFilter.order] 오름차순으로 정렬된다.
 * 필터가 예외를 던지면([CancellationException] 제외) 로깅하고 건너뛴다 —
 * 이전 콘텐츠로 체인을 계속한다 (fail-open 정책).
 *
 * ## 왜 fail-open인가?
 * 응답 필터는 보안 게이트가 아니라 품질 향상 목적이다.
 * 하나의 필터가 실패했다고 전체 응답을 차단하면 사용자 경험이 나빠진다.
 *
 * ## 사용 예시
 * ```kotlin
 * val chain = ResponseFilterChain(listOf(maxLengthFilter, sanitizationFilter))
 * val filtered = chain.apply("raw content", context)
 * ```
 */
class ResponseFilterChain(filters: List<ResponseFilter>) {

    /** order 오름차순으로 정렬된 필터 목록 */
    private val sorted: List<ResponseFilter> = filters.sortedBy { it.order }

    /**
     * 모든 필터를 순서대로 주어진 콘텐츠에 적용한다.
     *
     * @param content 에이전트의 원본 응답 콘텐츠
     * @param context 요청 및 실행 메타데이터
     * @return 필터링된 콘텐츠
     */
    suspend fun apply(content: String, context: ResponseFilterContext): String {
        if (sorted.isEmpty()) return content

        var result = content
        for (filter in sorted) {
            try {
                result = filter.filter(result, context)
            } catch (e: Exception) {
                e.throwIfCancellation()
                // fail-open: 필터 실패 시 로깅하고 이전 콘텐츠로 계속
                logger.warn(e) { "ResponseFilter '${filter::class.simpleName}' failed, skipping" }
            }
        }
        return result
    }

    /** 체인 내 필터 수. */
    val size: Int get() = sorted.size
}
