package com.arc.reactor.controller

/**
 * 목록 엔드포인트용 범용 페이지네이션 응답 래퍼.
 *
 * @param T 응답 항목의 타입
 * @param items 현재 페이지의 결과 목록
 * @param total 페이지네이션 전 전체 항목 수
 * @param offset 첫 번째 항목의 0 기반 오프셋
 * @param limit 페이지당 최대 항목 수
 */
data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

/** limit 값을 허용 범위 [1, 200]으로 클램핑한다. */
internal fun clampLimit(raw: Int): Int = raw.coerceIn(1, 200)

/**
 * 리스트에 인메모리 페이지네이션을 적용한다.
 *
 * @param offset 0 기반 시작 인덱스
 * @param limit 반환할 최대 항목 수 (이미 클램핑됨)
 * @return 올바른 슬라이스와 전체 개수를 포함한 [PaginatedResponse]
 */
internal fun <T> List<T>.paginate(offset: Int, limit: Int): PaginatedResponse<T> {
    val safeOffset = offset.coerceAtLeast(0)
    val total = size
    val end = (safeOffset + limit).coerceAtMost(total)
    val items = if (safeOffset >= total) emptyList() else subList(safeOffset, end)
    return PaginatedResponse(items = items, total = total, offset = safeOffset, limit = limit)
}
