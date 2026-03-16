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
        adminExchange = exchangeWithRole(UserRole.ADMIN)
    }

    @Nested
    inner class Authentication {

        @Test
        fun `non-admin on ingestMcpHealth에 대해 return 403해야 한다`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = McpHealthRequest(tenantId = "t1", serverName = "s1")

            val response = controller.ingestMcpHealth(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            (response.body as AdminErrorResponse).error shouldContain "Admin"
        }

        @Test
        fun `non-admin on ingestToolCall에 대해 return 403해야 한다`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = ToolCallRequest(tenantId = "t1", runId = "r1", toolName = "tool1")

            val response = controller.ingestToolCall(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `non-admin on ingestEvalResult에 대해 return 403해야 한다`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = EvalResultRequest(tenantId = "t1", evalRunId = "e1", testCaseId = "tc1", pass = true)

            val response = controller.ingestEvalResult(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `non-admin on ingestEvalResults batch에 대해 return 403해야 한다`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val request = EvalRunResultsRequest(
                tenantId = "t1", evalRunId = "e1",
                results = listOf(EvalTestCaseResult(testCaseId = "tc1", pass = true))
            )

            val response = controller.ingestEvalResults(request, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }

        @Test
        fun `non-admin on ingestBatch에 대해 return 403해야 한다`() {
            val userExchange = exchangeWithRole(UserRole.USER)
            val requests = listOf(McpHealthRequest(tenantId = "t1", serverName = "s1"))

            val response = controller.ingestBatch(requests, userExchange)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
        }
    }

    @Nested
    inner class BatchLimits {

        @Test
        fun `reject eval results batch exceeding limit해야 한다`() {
            val results = (1..1001).map { EvalTestCaseResult(testCaseId = "tc-$it", pass = true) }
            val request = EvalRunResultsRequest(tenantId = "t1", evalRunId = "e1", results = results)

            val response = controller.ingestEvalResults(request, adminExchange)
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            (response.body as AdminErrorResponse).error shouldContain "Batch size exceeds limit"
        }

        @Test
        fun `reject mcp health batch exceeding limit해야 한다`() {
            val requests = (1..1001).map { McpHealthRequest(tenantId = "t1", serverName = "s-$it") }

            val response = controller.ingestBatch(requests, adminExchange)
            response.statusCode shouldBe HttpStatus.BAD_REQUEST
            (response.body as AdminErrorResponse).error shouldContain "Batch size exceeds limit"
        }
    }

    @Nested
    inner class McpHealthIngestion {

        @Test
        fun `accept valid MCP health event해야 한다`() {
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
        fun `error details로 accept MCP health해야 한다`() {
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
        fun `accept valid tool call event해야 한다`() {
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
        fun `null일 때 default toolSource to mcp해야 한다`() {
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
        fun `null일 때 default callIndex to 0해야 한다`() {
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
        fun `accept valid eval result event해야 한다`() {
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
        fun `failure details로 accept failed eval result해야 한다`() {
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
        fun `truncate long failure detail to 500 chars해야 한다`() {
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
        fun `null일 때 default cost to ZERO해야 한다`() {
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
        fun `null일 때 default tags to empty list해야 한다`() {
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
        fun `accept batch eval results해야 한다`() {
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

            // All은(는) share the same evalRunId and tenantId해야 합니다
            evalEvents.forEach { event ->
                event.evalRunId shouldBe "eval-run-002"
                event.tenantId shouldBe "tenant-1"
            }
        }

        @Test
        fun `reject empty results list해야 한다`() {
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
        fun `accept batch MCP health events해야 한다`() {
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
        fun `buffer is full for single event일 때 return 503해야 한다`() {
            val tinyBuffer = MetricRingBuffer(64) // capacity = 64
            val ctrl = MetricIngestionController(tinyBuffer)

            // the buffer를 채웁니다
            repeat(64) {
                ctrl.ingestMcpHealth(McpHealthRequest(
                    tenantId = "t1", serverName = "s$it"
                ), adminExchange)
            }

            // Next은(는) be rejected해야 합니다
            val response = ctrl.ingestMcpHealth(McpHealthRequest(
                tenantId = "t1", serverName = "overflow"
            ), adminExchange)
            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }

        @Test
        fun `buffer fills일 때 report drops in batch response해야 한다`() {
            val tinyBuffer = MetricRingBuffer(64)
            val ctrl = MetricIngestionController(tinyBuffer)

            // most of the buffer를 채웁니다
            repeat(60) {
                ctrl.ingestMcpHealth(McpHealthRequest(tenantId = "t1", serverName = "s$it"), adminExchange)
            }

            // Batch of 10 — some은(는) be accepted, some dropped해야 합니다
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
