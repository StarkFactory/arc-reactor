package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ApprovalModels에 대한 단위 테스트.
 *
 * HITL 도메인 모델(ToolApprovalRequest, ToolApprovalResponse, ApprovalStatus, ApprovalSummary)의
 * 기본값, 불변성, 동등성을 검증한다.
 */
class ApprovalModelsTest {

    @Nested
    inner class ToolApprovalRequestTest {

        @Test
        fun `필수 필드를 정확히 보유해야 한다`() {
            val now = Instant.now()
            val request = ToolApprovalRequest(
                id = "req-001",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "delete_order",
                arguments = mapOf("orderId" to "42"),
                requestedAt = now,
                timeoutMs = 10_000
            )

            assertEquals("req-001", request.id) { "id가 일치해야 한다" }
            assertEquals("run-abc", request.runId) { "runId가 일치해야 한다" }
            assertEquals("user-xyz", request.userId) { "userId가 일치해야 한다" }
            assertEquals("delete_order", request.toolName) { "toolName이 일치해야 한다" }
            assertEquals(mapOf("orderId" to "42"), request.arguments) { "arguments가 일치해야 한다" }
            assertEquals(now, request.requestedAt) { "requestedAt이 일치해야 한다" }
            assertEquals(10_000L, request.timeoutMs) { "timeoutMs가 일치해야 한다" }
        }

        @Test
        fun `timeoutMs 기본값은 0이어야 한다`() {
            val request = ToolApprovalRequest(
                id = "req-002",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "process_refund",
                arguments = emptyMap()
            )

            assertEquals(0L, request.timeoutMs) { "timeoutMs 기본값은 0이어야 한다" }
        }

        @Test
        fun `requestedAt 기본값은 현재 시각 근처이어야 한다`() {
            val before = Instant.now()
            val request = ToolApprovalRequest(
                id = "req-003",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "cancel_subscription",
                arguments = emptyMap()
            )
            val after = Instant.now()

            assertFalse(request.requestedAt.isBefore(before)) { "requestedAt이 생성 이전이면 안 된다" }
            assertFalse(request.requestedAt.isAfter(after)) { "requestedAt이 생성 이후이면 안 된다" }
        }

        @Test
        fun `arguments에 null 값을 허용해야 한다`() {
            val request = ToolApprovalRequest(
                id = "req-004",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "update_record",
                arguments = mapOf("fieldA" to null, "fieldB" to "value")
            )

            assertNull(request.arguments["fieldA"]) { "null 값 인수가 허용되어야 한다" }
            assertEquals("value", request.arguments["fieldB"]) { "non-null 값 인수가 보존되어야 한다" }
        }

        @Test
        fun `동일 필드를 가진 두 인스턴스는 동등해야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val r1 = ToolApprovalRequest(
                id = "req-eq", runId = "run-eq", userId = "user-eq",
                toolName = "tool-eq", arguments = mapOf("k" to "v"),
                requestedAt = fixedTime, timeoutMs = 5_000
            )
            val r2 = r1.copy()

            assertEquals(r1, r2) { "copy()로 생성한 인스턴스는 원본과 동등해야 한다" }
        }

        @Test
        fun `copy로 일부 필드를 변경한 인스턴스는 다른 인스턴스여야 한다`() {
            val original = ToolApprovalRequest(
                id = "req-005", runId = "run-abc", userId = "user-xyz",
                toolName = "delete_order", arguments = emptyMap()
            )
            val modified = original.copy(toolName = "process_refund")

            assertNotEquals(original, modified) { "toolName이 다른 인스턴스는 다르게 간주되어야 한다" }
            assertEquals("process_refund", modified.toolName) { "변경된 toolName이 반영되어야 한다" }
            assertEquals(original.id, modified.id) { "변경되지 않은 id는 유지되어야 한다" }
        }
    }

    @Nested
    inner class ToolApprovalResponseTest {

        @Test
        fun `승인 응답은 approved=true를 가져야 한다`() {
            val response = ToolApprovalResponse(approved = true)

            assertTrue(response.approved) { "approved가 true이어야 한다" }
            assertNull(response.reason) { "reason 기본값은 null이어야 한다" }
            assertNull(response.modifiedArguments) { "modifiedArguments 기본값은 null이어야 한다" }
        }

        @Test
        fun `거부 응답은 approved=false와 사유를 가져야 한다`() {
            val response = ToolApprovalResponse(approved = false, reason = "금액 초과")

            assertFalse(response.approved) { "approved가 false이어야 한다" }
            assertEquals("금액 초과", response.reason) { "reason이 일치해야 한다" }
        }

        @Test
        fun `수정된 인수를 가진 승인 응답을 지원해야 한다`() {
            val modifiedArgs = mapOf("amount" to 5_000, "currency" to "KRW")
            val response = ToolApprovalResponse(
                approved = true,
                modifiedArguments = modifiedArgs
            )

            assertTrue(response.approved) { "approved가 true이어야 한다" }
            assertEquals(modifiedArgs, response.modifiedArguments) { "modifiedArguments가 일치해야 한다" }
            assertNull(response.reason) { "reason은 null이어야 한다" }
        }

        @Test
        fun `reason과 modifiedArguments를 동시에 가질 수 있어야 한다`() {
            val response = ToolApprovalResponse(
                approved = false,
                reason = "부분 거부",
                modifiedArguments = mapOf("limit" to 100)
            )

            assertFalse(response.approved) { "approved가 false이어야 한다" }
            assertNotNull(response.reason) { "reason이 null이 아니어야 한다" }
            assertNotNull(response.modifiedArguments) { "modifiedArguments가 null이 아니어야 한다" }
        }

        @Test
        fun `동일 필드를 가진 두 인스턴스는 동등해야 한다`() {
            val r1 = ToolApprovalResponse(approved = true, reason = null, modifiedArguments = null)
            val r2 = ToolApprovalResponse(approved = true)

            assertEquals(r1, r2) { "동일 필드를 가진 두 응답은 동등해야 한다" }
        }
    }

    @Nested
    inner class ApprovalStatusTest {

        @Test
        fun `네 가지 상태 값이 정의되어야 한다`() {
            val values = ApprovalStatus.values()

            assertEquals(4, values.size) { "ApprovalStatus는 정확히 4개의 값을 가져야 한다" }
            assertTrue(ApprovalStatus.PENDING in values) { "PENDING이 포함되어야 한다" }
            assertTrue(ApprovalStatus.APPROVED in values) { "APPROVED가 포함되어야 한다" }
            assertTrue(ApprovalStatus.REJECTED in values) { "REJECTED가 포함되어야 한다" }
            assertTrue(ApprovalStatus.TIMED_OUT in values) { "TIMED_OUT이 포함되어야 한다" }
        }

        @Test
        fun `이름으로 상태 값을 조회할 수 있어야 한다`() {
            assertEquals(ApprovalStatus.PENDING, ApprovalStatus.valueOf("PENDING")) { "PENDING 조회가 성공해야 한다" }
            assertEquals(ApprovalStatus.APPROVED, ApprovalStatus.valueOf("APPROVED")) { "APPROVED 조회가 성공해야 한다" }
            assertEquals(ApprovalStatus.REJECTED, ApprovalStatus.valueOf("REJECTED")) { "REJECTED 조회가 성공해야 한다" }
            assertEquals(ApprovalStatus.TIMED_OUT, ApprovalStatus.valueOf("TIMED_OUT")) { "TIMED_OUT 조회가 성공해야 한다" }
        }

        @Test
        fun `잘못된 이름으로 조회 시 예외가 발생해야 한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                ApprovalStatus.valueOf("UNKNOWN_STATUS")
            }.also {
                assertNotNull(it.message) { "예외 메시지가 존재해야 한다" }
            }
        }

        @Test
        fun `ordinal 순서가 PENDING→APPROVED→REJECTED→TIMED_OUT이어야 한다`() {
            assertEquals(0, ApprovalStatus.PENDING.ordinal) { "PENDING ordinal이 0이어야 한다" }
            assertEquals(1, ApprovalStatus.APPROVED.ordinal) { "APPROVED ordinal이 1이어야 한다" }
            assertEquals(2, ApprovalStatus.REJECTED.ordinal) { "REJECTED ordinal이 2이어야 한다" }
            assertEquals(3, ApprovalStatus.TIMED_OUT.ordinal) { "TIMED_OUT ordinal이 3이어야 한다" }
        }
    }

    @Nested
    inner class ApprovalSummaryTest {

        @Test
        fun `모든 필드를 정확히 보유해야 한다`() {
            val now = Instant.parse("2024-06-15T12:00:00Z")
            val summary = ApprovalSummary(
                id = "sum-001",
                runId = "run-111",
                userId = "user-222",
                toolName = "delete_order",
                arguments = mapOf("orderId" to "99"),
                requestedAt = now,
                status = ApprovalStatus.PENDING
            )

            assertEquals("sum-001", summary.id) { "id가 일치해야 한다" }
            assertEquals("run-111", summary.runId) { "runId가 일치해야 한다" }
            assertEquals("user-222", summary.userId) { "userId가 일치해야 한다" }
            assertEquals("delete_order", summary.toolName) { "toolName이 일치해야 한다" }
            assertEquals(mapOf("orderId" to "99"), summary.arguments) { "arguments가 일치해야 한다" }
            assertEquals(now, summary.requestedAt) { "requestedAt이 일치해야 한다" }
            assertEquals(ApprovalStatus.PENDING, summary.status) { "status가 PENDING이어야 한다" }
        }

        @Test
        fun `APPROVED 상태의 요약을 생성할 수 있어야 한다`() {
            val summary = ApprovalSummary(
                id = "sum-002", runId = "run-abc", userId = "user-xyz",
                toolName = "process_refund", arguments = emptyMap(),
                requestedAt = Instant.now(), status = ApprovalStatus.APPROVED
            )

            assertEquals(ApprovalStatus.APPROVED, summary.status) { "APPROVED 상태가 반영되어야 한다" }
        }

        @Test
        fun `REJECTED 상태의 요약을 생성할 수 있어야 한다`() {
            val summary = ApprovalSummary(
                id = "sum-003", runId = "run-abc", userId = "user-xyz",
                toolName = "cancel_subscription", arguments = emptyMap(),
                requestedAt = Instant.now(), status = ApprovalStatus.REJECTED
            )

            assertEquals(ApprovalStatus.REJECTED, summary.status) { "REJECTED 상태가 반영되어야 한다" }
        }

        @Test
        fun `TIMED_OUT 상태의 요약을 생성할 수 있어야 한다`() {
            val summary = ApprovalSummary(
                id = "sum-004", runId = "run-abc", userId = "user-xyz",
                toolName = "slow_tool", arguments = emptyMap(),
                requestedAt = Instant.now(), status = ApprovalStatus.TIMED_OUT
            )

            assertEquals(ApprovalStatus.TIMED_OUT, summary.status) { "TIMED_OUT 상태가 반영되어야 한다" }
        }

        @Test
        fun `동일 필드를 가진 두 인스턴스는 동등해야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val s1 = ApprovalSummary(
                id = "sum-eq", runId = "run-eq", userId = "user-eq",
                toolName = "tool-eq", arguments = mapOf("k" to "v"),
                requestedAt = fixedTime, status = ApprovalStatus.PENDING
            )
            val s2 = s1.copy()

            assertEquals(s1, s2) { "copy()로 생성한 인스턴스는 원본과 동등해야 한다" }
        }

        @Test
        fun `copy로 status를 변경한 인스턴스는 다른 인스턴스여야 한다`() {
            val fixedTime = Instant.parse("2024-01-01T00:00:00Z")
            val pending = ApprovalSummary(
                id = "sum-005", runId = "run-abc", userId = "user-xyz",
                toolName = "delete_order", arguments = emptyMap(),
                requestedAt = fixedTime, status = ApprovalStatus.PENDING
            )
            val approved = pending.copy(status = ApprovalStatus.APPROVED)

            assertNotEquals(pending, approved) { "status가 다른 인스턴스는 다르게 간주되어야 한다" }
            assertEquals(pending.id, approved.id) { "변경되지 않은 id는 유지되어야 한다" }
        }

        @Test
        fun `arguments에 빈 맵을 허용해야 한다`() {
            val summary = ApprovalSummary(
                id = "sum-006", runId = "run-abc", userId = "user-xyz",
                toolName = "no_args_tool", arguments = emptyMap(),
                requestedAt = Instant.now(), status = ApprovalStatus.PENDING
            )

            assertTrue(summary.arguments.isEmpty()) { "빈 arguments 맵이 허용되어야 한다" }
        }
    }

    @Nested
    inner class CrossModelConsistencyTest {

        @Test
        fun `ToolApprovalRequest 필드가 ApprovalSummary에 정확히 반영될 수 있어야 한다`() {
            val fixedTime = Instant.parse("2024-03-01T09:00:00Z")
            val request = ToolApprovalRequest(
                id = "req-cross",
                runId = "run-cross",
                userId = "user-cross",
                toolName = "cross_tool",
                arguments = mapOf("param" to 42),
                requestedAt = fixedTime,
                timeoutMs = 30_000
            )

            // 저장소가 Request → Summary로 변환하는 방식 시뮬레이션
            val summary = ApprovalSummary(
                id = request.id,
                runId = request.runId,
                userId = request.userId,
                toolName = request.toolName,
                arguments = request.arguments,
                requestedAt = request.requestedAt,
                status = ApprovalStatus.PENDING
            )

            assertEquals(request.id, summary.id) { "id가 일치해야 한다" }
            assertEquals(request.runId, summary.runId) { "runId가 일치해야 한다" }
            assertEquals(request.userId, summary.userId) { "userId가 일치해야 한다" }
            assertEquals(request.toolName, summary.toolName) { "toolName이 일치해야 한다" }
            assertEquals(request.arguments, summary.arguments) { "arguments가 일치해야 한다" }
            assertEquals(request.requestedAt, summary.requestedAt) { "requestedAt이 일치해야 한다" }
            assertEquals(ApprovalStatus.PENDING, summary.status) { "신규 요약은 PENDING 상태이어야 한다" }
        }

        @Test
        fun `ToolApprovalResponse의 approved 필드와 ApprovalStatus가 일관성을 가져야 한다`() {
            val approvedResponse = ToolApprovalResponse(approved = true)
            val rejectedResponse = ToolApprovalResponse(approved = false, reason = "거부됨")

            // 승인 응답 → APPROVED 상태로 매핑해야 함
            val approvedStatus = if (approvedResponse.approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
            val rejectedStatus = if (rejectedResponse.approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED

            assertEquals(ApprovalStatus.APPROVED, approvedStatus) { "approved=true는 APPROVED 상태이어야 한다" }
            assertEquals(ApprovalStatus.REJECTED, rejectedStatus) { "approved=false는 REJECTED 상태이어야 한다" }
        }
    }
}
