package com.arc.reactor.admin.controller

import com.arc.reactor.admin.collection.MetricRingBuffer
import com.arc.reactor.admin.model.EvalResultEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.ToolCallEvent
import com.arc.reactor.auth.UserRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.math.BigDecimal

class MetricIngestionControllerTest {

    private lateinit var ringBuffer: MetricRingBuffer
    private lateinit var controller: MetricIngestionController
    private lateinit var adminExchange: ServerWebExchange

    private fun exchangeWithRole(role: UserRole?): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>()
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes
        return exchange
    }

    @BeforeEach
    fun setUp() {
        ringBuffer = MetricRingBuffer(64)
        controller = MetricIngestionController(ringBuffer)
        adminExchange = exchangeWithRole(null) // null role = admin (auth disabled)
    }

    @Nested
    inner class Authentication {

        @Test
        fun `should return 403 for non-admin on ingestMcpHealth`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = McpHealthRequest(tenantId = "t1", serverName = "s1")

            val response = controller.ingestMcpHealth(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            (response.body as AdminErrorResponse).error shouldContain "Admin"
        }

        @Test
        fun `should return 403 for non-admin on ingestToolCall`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = ToolCallRequest(tenantId = "t1", runId = "r1", toolName = "tool1")

            val response = controller.ingestToolCall(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `should return 403 for non-admin on ingestEvalResult`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = EvalResultRequest(tenantId = "t1", evalRunId = "e1", testCaseId = "tc1", pass = true)

            val response = controller.ingestEvalResult(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `should return 403 for non-admin on ingestEvalResults batch`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = EvalRunResultsRequest(
                tenantId = "t1", evalRunId = "e1",
                results = listOf(EvalTestCaseResult(testCaseId = "tc1", pass = true))
            )

            val response = controller.ingestEvalResults(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `should return 403 for non-admin on ingestBatch`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val requests = listOf(McpHealthRequest(tenantId = "t1", serverName = "s1"))

            val response = controller.ingestBatch(requests, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class BatchLimits {

        @Test
        fun `should reject eval results batch exceeding limit`() {
            val results = (1..1001).map { EvalTestCaseResult(testCaseId = "tc-$it", pass = true) }
            val request = EvalRunResultsRequest(tenantId = "t1", evalRunId = "e1", results = results)

            val response = controller.ingestEvalResults(request, adminExchange)
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            (response.body as AdminErrorResponse).error shouldContain "Batch size exceeds limit"
        }

        @Test
        fun `should reject mcp health batch exceeding limit`() {
            val requests = (1..1001).map { McpHealthRequest(tenantId = "t1", serverName = "s-$it") }

            val response = controller.ingestBatch(requests, adminExchange)
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            (response.body as AdminErrorResponse).error shouldContain "Batch size exceeds limit"
        }
    }

    @Nested
    inner class McpHealthIngestion {

        @Test
        fun `should accept valid MCP health event`() {
            val request = McpHealthRequest(
                tenantId = "tenant-1",
                serverName = "error-log-mcp",
                status = "CONNECTED",
                responseTimeMs = 45,
                toolCount = 13
            )

            val response = controller.ingestMcpHealth(request, adminExchange)
            response.statusCode shouldBe HttpStatus.ACCEPTED

            val events = ringBuffer.drain(10)
            events.size shouldBe 1
            val event = events[0].shouldBeInstanceOf<McpHealthEvent>()
            event.tenantId shouldBe "tenant-1"
            event.serverName shouldBe "error-log-mcp"
            event.status shouldBe "CONNECTED"
            event.responseTimeMs shouldBe 45
            event.toolCount shouldBe 13
        }

        @Test
        fun `should accept MCP health with error details`() {
            val request = McpHealthRequest(
                tenantId = "tenant-1",
                serverName = "figma-mcp",
                status = "FAILED",
                errorClass = "IOException",
                errorMessage = "Connection refused"
            )

            val response = controller.ingestMcpHealth(request, adminExchange)
            response.statusCode shouldBe HttpStatus.ACCEPTED

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<McpHealthEvent>()
            event.status shouldBe "FAILED"
            event.errorClass shouldBe "IOException"
            event.errorMessage shouldBe "Connection refused"
        }
    }

    @Nested
    inner class ToolCallIngestion {

        @Test
        fun `should accept valid tool call event`() {
            val request = ToolCallRequest(
                tenantId = "tenant-1",
                runId = "run-123",
                toolName = "analyze_error",
                toolSource = "mcp",
                mcpServerName = "error-log-mcp",
                success = true,
                durationMs = 250
            )

            val response = controller.ingestToolCall(request, adminExchange)
            response.statusCode shouldBe HttpStatus.ACCEPTED

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.tenantId shouldBe "tenant-1"
            event.runId shouldBe "run-123"
            event.toolName shouldBe "analyze_error"
            event.toolSource shouldBe "mcp"
            event.mcpServerName shouldBe "error-log-mcp"
            event.success shouldBe true
            event.durationMs shouldBe 250
        }

        @Test
        fun `should default toolSource to mcp when null`() {
            val request = ToolCallRequest(
                tenantId = "tenant-1",
                runId = "run-123",
                toolName = "some_tool",
                toolSource = null
            )

            controller.ingestToolCall(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.toolSource shouldBe "mcp"
        }

        @Test
        fun `should default callIndex to 0 when null`() {
            val request = ToolCallRequest(
                tenantId = "tenant-1",
                runId = "run-123",
                toolName = "some_tool",
                callIndex = null
            )

            controller.ingestToolCall(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<ToolCallEvent>()
            event.callIndex shouldBe 0
        }
    }

    @Nested
    inner class EvalResultIngestion {

        @Test
        fun `should accept valid eval result event`() {
            val request = EvalResultRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-001",
                testCaseId = "tc-1",
                pass = true,
                score = 0.95,
                latencyMs = 1200,
                tokenUsage = 500,
                cost = BigDecimal("0.0015"),
                assertionType = "contains",
                tags = listOf("golden", "customer-service")
            )

            val response = controller.ingestEvalResult(request, adminExchange)
            response.statusCode shouldBe HttpStatus.ACCEPTED

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<EvalResultEvent>()
            event.tenantId shouldBe "tenant-1"
            event.evalRunId shouldBe "eval-run-001"
            event.testCaseId shouldBe "tc-1"
            event.pass shouldBe true
            event.score shouldBe 0.95
            event.latencyMs shouldBe 1200
            event.tokenUsage shouldBe 500
            event.cost shouldBe BigDecimal("0.0015")
            event.assertionType shouldBe "contains"
            event.tags shouldBe listOf("golden", "customer-service")
        }

        @Test
        fun `should accept failed eval result with failure details`() {
            val request = EvalResultRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-001",
                testCaseId = "tc-2",
                pass = false,
                score = 0.2,
                failureClass = "WRONG_TOOL",
                failureDetail = "Expected tool 'check_order' but got 'search_faq'"
            )

            controller.ingestEvalResult(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<EvalResultEvent>()
            event.pass shouldBe false
            event.failureClass shouldBe "WRONG_TOOL"
            event.failureDetail shouldBe "Expected tool 'check_order' but got 'search_faq'"
        }

        @Test
        fun `should truncate long failure detail to 500 chars`() {
            val longDetail = "x".repeat(1000)
            val request = EvalResultRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-001",
                testCaseId = "tc-3",
                pass = false,
                failureDetail = longDetail
            )

            controller.ingestEvalResult(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<EvalResultEvent>()
            event.failureDetail!!.length shouldBe 500
        }

        @Test
        fun `should default cost to ZERO when null`() {
            val request = EvalResultRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-001",
                testCaseId = "tc-4",
                pass = true
            )

            controller.ingestEvalResult(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<EvalResultEvent>()
            event.cost shouldBe BigDecimal.ZERO
        }

        @Test
        fun `should default tags to empty list when null`() {
            val request = EvalResultRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-001",
                testCaseId = "tc-5",
                pass = true,
                tags = null
            )

            controller.ingestEvalResult(request, adminExchange)

            val event = ringBuffer.drain(10)[0].shouldBeInstanceOf<EvalResultEvent>()
            event.tags shouldBe emptyList()
        }
    }

    @Nested
    inner class BatchEvalIngestion {

        @Test
        fun `should accept batch eval results`() {
            val request = EvalRunResultsRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-002",
                results = listOf(
                    EvalTestCaseResult(testCaseId = "tc-1", pass = true, score = 1.0),
                    EvalTestCaseResult(testCaseId = "tc-2", pass = false, score = 0.3,
                        failureClass = "HALLUCINATION"),
                    EvalTestCaseResult(testCaseId = "tc-3", pass = true, score = 0.9,
                        tags = listOf("redteam"))
                )
            )

            val response = controller.ingestEvalResults(request, adminExchange)
            response.statusCode shouldBe HttpStatus.OK

            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            body["evalRunId"] shouldBe "eval-run-002"
            body["accepted"] shouldBe 3
            body["dropped"] shouldBe 0

            val events = ringBuffer.drain(10)
            events.size shouldBe 3
            events.forEach { it.shouldBeInstanceOf<EvalResultEvent>() }

            val evalEvents = events.map { it as EvalResultEvent }
            evalEvents[0].testCaseId shouldBe "tc-1"
            evalEvents[1].testCaseId shouldBe "tc-2"
            evalEvents[1].failureClass shouldBe "HALLUCINATION"
            evalEvents[2].tags shouldBe listOf("redteam")

            // All should share the same evalRunId and tenantId
            evalEvents.forEach { event ->
                event.evalRunId shouldBe "eval-run-002"
                event.tenantId shouldBe "tenant-1"
            }
        }

        @Test
        fun `should reject empty results list`() {
            val request = EvalRunResultsRequest(
                tenantId = "tenant-1",
                evalRunId = "eval-run-003",
                results = emptyList()
            )

            val response = controller.ingestEvalResults(request, adminExchange)
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
        }
    }

    @Nested
    inner class BatchMcpHealthIngestion {

        @Test
        fun `should accept batch MCP health events`() {
            val requests = listOf(
                McpHealthRequest(tenantId = "t1", serverName = "error-log-mcp", status = "CONNECTED"),
                McpHealthRequest(tenantId = "t1", serverName = "figma-mcp", status = "CONNECTED"),
                McpHealthRequest(tenantId = "t1", serverName = "swagger-mcp", status = "FAILED",
                    errorClass = "Timeout")
            )

            val response = controller.ingestBatch(requests, adminExchange)
            response.statusCode shouldBe HttpStatus.OK

            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            body["accepted"] shouldBe 3
            body["dropped"] shouldBe 0

            val events = ringBuffer.drain(10)
            events.size shouldBe 3
        }
    }

    @Nested
    inner class BufferFull {

        @Test
        fun `should return 503 when buffer is full for single event`() {
            val tinyBuffer = MetricRingBuffer(64) // capacity = 64
            val ctrl = MetricIngestionController(tinyBuffer)

            // Fill the buffer
            repeat(64) {
                ctrl.ingestMcpHealth(McpHealthRequest(
                    tenantId = "t1", serverName = "s$it"
                ), adminExchange)
            }

            // Next should be rejected
            val response = ctrl.ingestMcpHealth(McpHealthRequest(
                tenantId = "t1", serverName = "overflow"
            ), adminExchange)
            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }

        @Test
        fun `should report drops in batch response when buffer fills`() {
            val tinyBuffer = MetricRingBuffer(64)
            val ctrl = MetricIngestionController(tinyBuffer)

            // Fill most of the buffer
            repeat(60) {
                ctrl.ingestMcpHealth(McpHealthRequest(tenantId = "t1", serverName = "s$it"), adminExchange)
            }

            // Batch of 10 — some should be accepted, some dropped
            val requests = (0 until 10).map {
                McpHealthRequest(tenantId = "t1", serverName = "batch-$it")
            }

            val response = ctrl.ingestBatch(requests, adminExchange)
            response.statusCode shouldBe HttpStatus.OK

            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            val accepted = body["accepted"] as Int
            val dropped = body["dropped"] as Int
            (accepted + dropped) shouldBe 10
            (accepted <= 4) shouldBe true // only ~4 slots remaining
            (dropped >= 6) shouldBe true
        }
    }
}
