package com.arc.reactor.approval

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        /**
         * R337 regression: Caffeine `maximumSize` 초과로 인한 `SIZE` evict 시 대기 중인
         * `CompletableDeferred`가 **즉시 overflow 응답**으로 완료되어야 한다. 이전 구현은
         * evict 후 deferred가 완료되지 않아 사용자 요청이 `withTimeoutOrNull` 전체 타임아웃까지
         * 대기하고 관리자 `approve` 호출도 `false`를 반환하는 silent UX 버그가 있었다.
         *
         * W-TinyLFU admission 특성상 어느 entry가 evict될지 결정적이지 않으므로, 두 요청 중
         * "하나라도" overflow 응답을 받는 것을 검증한다. Caffeine eviction이 비동기이므로
         * [InMemoryPendingApprovalStore.forceCleanUp] 을 호출해 maintenance를 즉시 drain한다.
         */
        @Test
        fun `R337 Caffeine evict 시 대기 중인 entry에 overflow 응답을 즉시 전달해야 한다`() = runBlocking {
            val overflowStore = InMemoryPendingApprovalStore(
                defaultTimeoutMs = 60_000, // 길게 설정 — overflow로 즉시 resolve됨을 검증
                maxPending = 1
            )

            val first = async {
                overflowStore.requestApproval(
                    runId = "r1", userId = "u",
                    toolName = "tool_a", arguments = emptyMap()
                )
            }
            // first가 pending에 등록될 때까지 폴링 (최대 2초)
            val regDeadline = System.currentTimeMillis() + 2_000
            while (overflowStore.listPending().isEmpty() && System.currentTimeMillis() < regDeadline) {
                delay(10)
            }
            assertEquals(1, overflowStore.listPending().size) {
                "first 요청이 pending에 등록되어야 한다"
            }

            // 두 번째 요청으로 eviction 유발
            val second = async {
                overflowStore.requestApproval(
                    runId = "r2", userId = "u",
                    toolName = "tool_b", arguments = emptyMap()
                )
            }
            // second put 반영 + Caffeine eviction maintenance drain
            delay(50)
            overflowStore.forceCleanUp()
            delay(50)
            overflowStore.forceCleanUp()

            // 둘 중 하나는 overflow 응답으로 즉시 완료되어야 한다 (60초 타임아웃 대기 없이)
            suspend fun tryAwait(job: Deferred<ToolApprovalResponse>): ToolApprovalResponse? = try {
                withTimeout(2_000) { job.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }

            val firstResult = tryAwait(first)
            val secondResult = if (!second.isCompleted) null else tryAwait(second)

            val overflowResults = listOfNotNull(firstResult, secondResult).filter {
                !it.approved && it.reason?.contains("overflow") == true
            }
            assertTrue(overflowResults.isNotEmpty()) {
                "R337: Caffeine evict 시 대기 entry는 overflow 응답으로 완료되어야 한다. " +
                    "firstResult=${firstResult?.reason}, secondResult=${secondResult?.reason}"
            }

            // 정리 — 여전히 pending인 요청은 승인
            overflowStore.listPending().forEach { overflowStore.approve(it.id) }
            if (!first.isCompleted) first.await()
            if (!second.isCompleted) second.await()
        }
    }
}
