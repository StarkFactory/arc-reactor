package com.arc.reactor.controller

/**
 * Generic paginated response wrapper for list endpoints.
 *
 * @param T The type of items in the response
 * @param items The page of results
 * @param total Total number of items matching the query (before pagination)
 * @param offset Zero-based offset of the first item
 * @param limit Maximum number of items per page
 */
data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

/**
 * Clamps a raw limit value to the allowed range [1, 200].
 */
internal fun clampLimit(raw: Int): Int = raw.coerceIn(1, 200)

/**
 * Applies in-memory pagination to a list.
 *
 * @param offset Zero-based start index
 * @param limit Maximum items to return (already clamped)
 * @return PaginatedResponse with the correct slice and total count
 */
internal fun <T> List<T>.paginate(offset: Int, limit: Int): PaginatedResponse<T> {
    val safeOffset = offset.coerceAtLeast(0)
    val total = size
    val end = (safeOffset + limit).coerceAtMost(total)
    val items = if (safeOffset >= total) emptyList() else subList(safeOffset, end)
    return PaginatedResponse(items = items, total = total, offset = safeOffset, limit = limit)
}
