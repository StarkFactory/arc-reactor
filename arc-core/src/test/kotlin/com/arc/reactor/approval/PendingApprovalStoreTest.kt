package com.arc.reactor.approval

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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

            // List and approve
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

            // After timeout, pending list은(는) be empty해야 합니다
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
}
