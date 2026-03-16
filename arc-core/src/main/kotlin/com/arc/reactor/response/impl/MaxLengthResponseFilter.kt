package com.arc.reactor.response.impl

import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterContext

/**
 * 최대 문자 수를 초과하는 응답 콘텐츠를 자르는 필터.
 *
 * 잘림이 발생하면 사용자에게 알리는 안내문이 추가된다.
 * [maxLength]를 0(기본값)으로 설정하면 잘림이 비활성화된다.
 *
 * @param maxLength 허용되는 최대 문자 수. 0이면 무제한.
 */
class MaxLengthResponseFilter(
    private val maxLength: Int = 0
) : ResponseFilter {

    /** 다른 필터보다 먼저 실행되도록 낮은 order 설정 */
    override val order: Int = 10

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        if (maxLength <= 0 || content.length <= maxLength) return content
        return content.take(maxLength) + TRUNCATION_NOTICE
    }

    companion object {
        /** 잘림 발생 시 추가되는 안내 메시지 */
        internal const val TRUNCATION_NOTICE = "\n\n[Response truncated]"
    }
}
