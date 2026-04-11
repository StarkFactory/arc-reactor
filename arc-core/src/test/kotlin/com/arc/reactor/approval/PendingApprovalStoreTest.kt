package com.arc.reactor.approval

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PendingApprovalStore에 대한 테스트.
 *
 * 대기 중 승인 저장소의 CRUD 동작을 검증합니다.
 */
class PendingApprovalStoreTest {

    private lateinit var store: InMemoryPendingApprovalStore

    @BeforeEach
    fun setup() {
        store = InMemoryPendingApprovalStore(defaultTimeoutMs = 5_000)
    }

    @Nested
    inner class ApprovalFlow {

        @Test
        fun `approve pending request해야 한다`() = runBlocking {
            val result = async {
                store.requestApproval(
                    runId = "run-1", userId = "user-1",
                    toolName = "delete_order", arguments = mapOf("orderId" to "123")
                )
            }

            // for the request to register를 기다립니다
            delay(100)

            // 목록 조회 및 승인
            val pending = store.listPending()
            assertEquals(1, pending.size) { "Expected 1 pending approval" }
            assertEquals("delete_order", pending[0].toolName) { "Tool name mismatch" }
            assertEquals(ApprovalStatus.PENDING, pending[0].status) { "Status should be PENDING" }

            val approved = store.approve(pending[0].id)
            assertTrue(approved) { "Approve should return true" }

            val response = result.await()
            assertTrue(response.approved) { "Response should be approved" }
        }

        @Test
        fun `reason로 reject pending request해야 한다`() = runBlocking {
            val result = async {
                store.requestApproval(
                    runId = "run-1", userId = "user-1",
                    toolName = "process_refund", arguments = mapOf("amount" to 50000)
                )
            }

            delay(100)

            val pending = store.listPending()
            val rejected = store.reject(pending[0].id, "Amount too large")
            assertTrue(rejected) { "Reject should return true" }

            val response = result.await()
            assertFalse(response.approved) { "Response should be rejected" }
            assertEquals("Amount too large", response.reason) { "Rejection reason mismatch" }
        }

        @Test
        fun `modified arguments로 approve해야 한다`() = runBlocking {
            val result = async {
                store.requestApproval(
                    runId = "run-1", userId = "user-1",
                    toolName = "process_refund", arguments = mapOf("amount" to 50000)
                )
            }

            delay(100)

            val pending = store.listPending()
            val modifiedArgs = mapOf("amount" to 10000, "reason" to "partial refund")
            store.approve(pending[0].id, modifiedArgs)

            val response = result.await()
            assertTrue(response.approved) { "Response should be approved" }
            assertEquals(10000, response.modifiedArguments?.get("amount")) {
                "Modified arguments should contain reduced amount"
            }
        }
    }

    @Nested
    inner class Timeout {

        @Test
        fun `no approval given일 때 time out해야 한다`() = runBlocking {
            val shortTimeoutStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 200)

            val response = shortTimeoutStore.requestApproval(
                runId = "run-1", userId = "user-1",
                toolName = "slow_tool", arguments = emptyMap()
            )

            assertFalse(response.approved) { "Should be rejected on timeout" }
            assertTrue(response.reason?.contains("timed out") == true) {
                "Should indicate timeout, got: ${response.reason}"
            }
        }

        @Test
        fun `timeout 후 clean up pending entry해야 한다`() = runBlocking {
            val shortTimeoutStore = InMemoryPendingApprovalStore(defaultTimeoutMs = 200)

            shortTimeoutStore.requestApproval(
                runId = "run-1", userId = "user-1",
                toolName = "slow_tool", arguments = emptyMap()
            )

            // 타임아웃 후 대기 목록은 비어 있어야 합니다
            val pending = shortTimeoutStore.listPending()
            assertTrue(pending.isEmpty()) { "Pending list should be empty after timeout" }
        }
    }

    @Nested
    inner class ListingAndFiltering {

        @Test
        fun `list pending by user해야 한다`() = runBlocking {
            val result1 = async {
                store.requestApproval(
                    runId = "run-1", userId = "user-A",
                    toolName = "tool1", arguments = emptyMap()
                )
            }
            val result2 = async {
                store.requestApproval(
                    runId = "run-2", userId = "user-B",
                    toolName = "tool2", arguments = emptyMap()
                )
            }

            delay(100)

            val userAPending = store.listPendingByUser("user-A")
            assertEquals(1, userAPending.size) { "User A should have 1 pending" }
            assertEquals("tool1", userAPending[0].toolName) { "User A tool name mismatch" }

            val userBPending = store.listPendingByUser("user-B")
            assertEquals(1, userBPending.size) { "User B should have 1 pending" }
            assertEquals("tool2", userBPending[0].toolName) { "User B tool name mismatch" }

            val allPending = store.listPending()
            assertEquals(2, allPending.size) { "Total should be 2 pending" }

            // 정리
            allPending.forEach { store.approve(it.id) }
            result1.await()
            result2.await()
        }

        @Test
        fun `non-existent approval ID에 대해 return false해야 한다`() {
            assertFalse(store.approve("non-existent")) { "Should return false for non-existent ID" }
            assertFalse(store.reject("non-existent")) { "Should return false for non-existent ID" }
        }
    }

    @Nested
    inner class BoundedCache {

        /**
         * R310 회귀: ConcurrentHashMap → Caffeine bounded cache 마이그레이션.
         *
         * 이전 구현은 `pending`이 무제한으로 성장할 수 있어 악성 호출이나 운영 실수로
         * OOM 위험이 있었다. maxPending 상한을 넘으면 W-TinyLFU 정책으로 evict되어야 한다.
         */
        @Test
        fun `maxPending 초과 시 Caffeine이 evict해야 한다`() = runBlocking {
            val boundedStore = InMemoryPendingApprovalStore(
                defaultTimeoutMs = 10_000,
                maxPending = 5
            )

            // 100개 요청을 제출 (전부 pending 상태로 대기)
            val jobs = (1..100).map { idx ->
                async {
                    boundedStore.requestApproval(
                        runId = "run-$idx", userId = "user-$idx",
                        toolName = "tool-$idx", arguments = emptyMap()
                    )
                }
            }

            delay(200)

            val pendingCount = boundedStore.listPending().size
            assertTrue(pendingCount < 100) {
                "Expected eviction to reduce size below 100, got $pendingCount"
            }
            assertTrue(pendingCount <= 20) {
                "Expected Caffeine bounded cache to converge near maxPending=5, got $pendingCount " +
                    "(W-TinyLFU는 정확한 상한이 아닌 근사치로 수렴)"
            }

            // 정리 — 살아있는 pending은 승인하고, evict된 것은 timeout 대기
            boundedStore.listPending().forEach { boundedStore.approve(it.id) }
            // evict된 요청들은 withTimeoutOrNull에서 자연스럽게 타임아웃됨
            jobs.forEach { it.await() }
        }

        @Test
        fun `DEFAULT_MAX_PENDING은 10000이다`() {
            assertEquals(10_000L, InMemoryPendingApprovalStore.DEFAULT_MAX_PENDING) {
                "Expected default max pending to be 10000"
            }
        }
    }
}
