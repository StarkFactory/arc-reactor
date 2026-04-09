package com.arc.reactor.response.impl

import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterContext

/**
 * 보안 새니타이징 태그를 사용자 친화적 텍스트로 변환하는 [ResponseFilter].
 *
 * [com.arc.reactor.guard.tool.ToolOutputSanitizer]가 Injection 패턴을 `[SANITIZED]`로
 * 치환하는데, 이 태그가 최종 사용자 응답에 그대로 노출되면 혼란을 준다.
 * 이 필터는 두 가지 전략으로 처리한다:
 *
 * 1. **제목/인라인 치환**: `[SANITIZED]`가 포함된 짧은 텍스트는 "(보안 처리됨)"으로 교체
 * 2. **독립 행 제거**: `[SANITIZED]`만으로 구성된 행은 제거
 *
 * 실행 순서: 15 (MaxLengthResponseFilter(10) 다음, VerifiedSourcesResponseFilter(90) 이전).
 */
class SanitizedTextResponseFilter : ResponseFilter {

    override val order: Int = 15

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        if (!content.contains(SANITIZED_TAG)) return content

        return content
            .replace(STANDALONE_LINE_REGEX, "")
            .replace(SANITIZED_TAG, USER_FRIENDLY_LABEL)
            .replace(BLANK_LINE_COLLAPSE_REGEX, "\n\n")
            .trim()
    }

    companion object {
        /** 도구 출력 새니타이저가 삽입하는 태그 */
        private const val SANITIZED_TAG = "[SANITIZED]"

        /** 사용자에게 보여줄 대체 텍스트 */
        private const val USER_FRIENDLY_LABEL = "(보안 처리됨)"

        /** `[SANITIZED]`만으로 구성된 독립 행 제거 */
        private val STANDALONE_LINE_REGEX = Regex("(?m)^\\s*\\[SANITIZED]\\s*$\\n?")

        /** 연속 빈 줄 정리 */
        private val BLANK_LINE_COLLAPSE_REGEX = Regex("\\n{3,}")
    }
}
