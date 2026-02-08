package com.arc.reactor.agent.metrics

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentMetricsTest {

    @Nested
    inner class NoOpAgentMetricsTest {

        private val metrics = NoOpAgentMetrics()

        @Test
        fun `recordExecution should not throw on success result`() {
            val result = AgentResult.success(content = "Hello", durationMs = 100)

            assertDoesNotThrow {
                metrics.recordExecution(result)
            }
        }

        @Test
        fun `recordExecution should not throw on failure result`() {
            val result = AgentResult.failure(
                errorMessage = "Error occurred",
                errorCode = AgentErrorCode.UNKNOWN,
                durationMs = 50
            )

            assertDoesNotThrow {
                metrics.recordExecution(result)
            }
        }

        @Test
        fun `recordToolCall should not throw`() {
            assertDoesNotThrow {
                metrics.recordToolCall("my_tool", durationMs = 200, success = true)
            }

            assertDoesNotThrow {
                metrics.recordToolCall("my_tool", durationMs = 50, success = false)
            }
        }

        @Test
        fun `recordGuardRejection should not throw`() {
            assertDoesNotThrow {
                metrics.recordGuardRejection(stage = "RateLimit", reason = "Too many requests")
            }
        }
    }

    @Nested
    inner class AgentMetricsInterfaceTest {

        @Test
        fun `custom implementation should receive correct parameters`() {
            var lastExecution: AgentResult? = null
            var lastToolName: String? = null
            var lastToolDuration: Long? = null
            var lastToolSuccess: Boolean? = null
            var lastGuardStage: String? = null
            var lastGuardReason: String? = null

            val trackingMetrics = object : AgentMetrics {
                override fun recordExecution(result: AgentResult) {
                    lastExecution = result
                }

                override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
                    lastToolName = toolName
                    lastToolDuration = durationMs
                    lastToolSuccess = success
                }

                override fun recordGuardRejection(stage: String, reason: String) {
                    lastGuardStage = stage
                    lastGuardReason = reason
                }
            }

            // Verify recordExecution
            val result = AgentResult.success(content = "test", durationMs = 150)
            trackingMetrics.recordExecution(result)
            assertNotNull(lastExecution) { "recordExecution should capture the result" }
            val captured = lastExecution!!
            assertTrue(captured.success) { "Captured result should be successful" }
            assertEquals(150L, captured.durationMs) { "Duration should match" }

            // Verify recordToolCall
            trackingMetrics.recordToolCall("calculator", durationMs = 42, success = true)
            assertEquals("calculator", lastToolName) { "Tool name should be captured" }
            assertEquals(42L, lastToolDuration) { "Tool duration should be captured" }
            assertEquals(true, lastToolSuccess) { "Tool success should be captured" }

            // Verify recordGuardRejection
            trackingMetrics.recordGuardRejection("InputValidation", "Input too long")
            assertEquals("InputValidation", lastGuardStage) { "Guard stage should be captured" }
            assertEquals("Input too long", lastGuardReason) { "Guard reason should be captured" }
        }
    }
}
