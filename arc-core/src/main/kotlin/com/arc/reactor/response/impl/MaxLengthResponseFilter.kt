package com.arc.reactor.response.impl

import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterContext

/**
 * Truncates response content that exceeds a maximum character length.
 *
 * When truncation occurs, a notice is appended to inform the user.
 * Set [maxLength] to 0 (default) to disable truncation.
 *
 * @param maxLength Maximum allowed characters. 0 = unlimited.
 */
class MaxLengthResponseFilter(
    private val maxLength: Int = 0
) : ResponseFilter {

    override val order: Int = 10

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        if (maxLength <= 0 || content.length <= maxLength) return content
        return content.take(maxLength) + TRUNCATION_NOTICE
    }

    companion object {
        internal const val TRUNCATION_NOTICE = "\n\n[Response truncated]"
    }
}
