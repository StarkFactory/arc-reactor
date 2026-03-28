package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [PaginatedResponse] 유틸리티 함수에 대한 단위 테스트.
 *
 * [clampLimit]과 [List.paginate] 확장 함수의 경계 조건 및 정상 동작을 검증한다.
 */
class PaginatedResponseTest {

    @Nested
    inner class ClampLimit {

        @Test
        fun `정상 범위 내 값은 그대로 반환한다`() {
            assertEquals(50, clampLimit(50)) { "허용 범위 내 값이 변환 없이 반환되어야 한다" }
        }

        @Test
        fun `최솟값 경계인 1은 그대로 반환한다`() {
            assertEquals(1, clampLimit(1)) { "허용 최솟값 1은 변환 없이 반환되어야 한다" }
        }

        @Test
        fun `최댓값 경계인 200은 그대로 반환한다`() {
            assertEquals(200, clampLimit(200)) { "허용 최댓값 200은 변환 없이 반환되어야 한다" }
        }

        @Test
        fun `0이하 값은 1로 클램핑된다`() {
            assertEquals(1, clampLimit(0)) { "0은 최솟값 1로 클램핑되어야 한다" }
        }

        @Test
        fun `음수는 1로 클램핑된다`() {
            assertEquals(1, clampLimit(-100)) { "음수는 최솟값 1로 클램핑되어야 한다" }
        }

        @Test
        fun `200초과 값은 200으로 클램핑된다`() {
            assertEquals(200, clampLimit(201)) { "201은 최댓값 200으로 클램핑되어야 한다" }
        }

        @Test
        fun `매우 큰 값은 200으로 클램핑된다`() {
            assertEquals(200, clampLimit(Int.MAX_VALUE)) { "Int.MAX_VALUE는 200으로 클램핑되어야 한다" }
        }
    }

    @Nested
    inner class Paginate {

        private val items = (1..10).toList()

        @Test
        fun `첫 페이지를 올바르게 반환한다`() {
            val result = items.paginate(offset = 0, limit = 3)

            assertEquals(listOf(1, 2, 3), result.items) { "offset=0, limit=3이면 첫 3개 항목이 반환되어야 한다" }
            assertEquals(10, result.total) { "total은 전체 항목 수여야 한다" }
            assertEquals(0, result.offset) { "offset은 0이어야 한다" }
            assertEquals(3, result.limit) { "limit은 3이어야 한다" }
        }

        @Test
        fun `중간 페이지를 올바르게 반환한다`() {
            val result = items.paginate(offset = 3, limit = 3)

            assertEquals(listOf(4, 5, 6), result.items) { "offset=3, limit=3이면 4~6번 항목이 반환되어야 한다" }
            assertEquals(10, result.total) { "total은 전체 항목 수여야 한다" }
        }

        @Test
        fun `마지막 페이지에서 남은 항목만 반환한다`() {
            val result = items.paginate(offset = 8, limit = 5)

            assertEquals(listOf(9, 10), result.items) { "범위를 초과하는 limit은 남은 항목만 반환해야 한다" }
            assertEquals(10, result.total) { "total은 전체 항목 수여야 한다" }
        }

        @Test
        fun `offset이 전체 크기 이상이면 빈 목록을 반환한다`() {
            val result = items.paginate(offset = 10, limit = 5)

            assertTrue(result.items.isEmpty()) { "offset이 total과 같으면 빈 목록이 반환되어야 한다" }
            assertEquals(10, result.total) { "total은 여전히 전체 항목 수여야 한다" }
        }

        @Test
        fun `offset이 total을 초과해도 빈 목록을 반환한다`() {
            val result = items.paginate(offset = 100, limit = 5)

            assertTrue(result.items.isEmpty()) { "offset이 total을 초과하면 빈 목록이 반환되어야 한다" }
        }

        @Test
        fun `음수 offset은 0으로 정규화된다`() {
            val result = items.paginate(offset = -5, limit = 3)

            assertEquals(listOf(1, 2, 3), result.items) { "음수 offset은 0으로 처리되어야 한다" }
            assertEquals(0, result.offset) { "정규화된 offset이 0이어야 한다" }
        }

        @Test
        fun `빈 목록에 페이지네이션 적용 시 빈 결과를 반환한다`() {
            val result = emptyList<String>().paginate(offset = 0, limit = 10)

            assertTrue(result.items.isEmpty()) { "빈 목록은 빈 결과를 반환해야 한다" }
            assertEquals(0, result.total) { "빈 목록의 total은 0이어야 한다" }
        }

        @Test
        fun `limit이 전체 목록보다 크면 전체를 반환한다`() {
            val result = items.paginate(offset = 0, limit = 100)

            assertEquals(items, result.items) { "limit이 전체 크기보다 크면 전체 항목이 반환되어야 한다" }
            assertEquals(10, result.total) { "total은 전체 항목 수여야 한다" }
        }

        @Test
        fun `단일 항목 목록에서 정확히 동작한다`() {
            val result = listOf("유일한항목").paginate(offset = 0, limit = 10)

            assertEquals(listOf("유일한항목"), result.items) { "단일 항목 목록은 그대로 반환되어야 한다" }
            assertEquals(1, result.total) { "단일 항목 목록의 total은 1이어야 한다" }
        }
    }
}
