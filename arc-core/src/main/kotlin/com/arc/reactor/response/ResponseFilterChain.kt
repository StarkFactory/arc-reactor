package com.arc.reactor.response

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Executes a list of [ResponseFilter]s in order on the response content.
 *
 * Filters are sorted by [ResponseFilter.order] (ascending).
 * If a filter throws an exception (other than [CancellationException]),
 * it is logged and skipped — the chain continues with the previous content (fail-open).
 *
 * ## Usage
 * ```kotlin
 * val chain = ResponseFilterChain(listOf(maxLengthFilter, sanitizationFilter))
 * val filtered = chain.apply("raw content", context)
 * ```
 */
class ResponseFilterChain(filters: List<ResponseFilter>) {

    private val sorted: List<ResponseFilter> = filters.sortedBy { it.order }

    /**
     * Apply all filters in order to the given content.
     *
     * @param content Raw response content from the agent
     * @param context Request and execution metadata
     * @return Filtered content
     */
    suspend fun apply(content: String, context: ResponseFilterContext): String {
        if (sorted.isEmpty()) return content

        var result = content
        for (filter in sorted) {
            try {
                result = filter.filter(result, context)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.warn(e) { "ResponseFilter '${filter::class.simpleName}' failed, skipping" }
            }
        }
        return result
    }

    /** Number of filters in the chain. */
    val size: Int get() = sorted.size
}
