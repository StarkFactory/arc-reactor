package com.arc.reactor.admin.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class McpMetricReporterTest {

    private lateinit var server: HttpServer
    private val receivedRequests = CopyOnWriteArrayList<ReceivedRequest>()
    private var serverPort: Int = 0

    data class ReceivedRequest(
        val path: String,
        val body: String,
        val contentType: String?
    )

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port

        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            receivedRequests.add(
                ReceivedRequest(
                    path = exchange.requestURI.path,
                    body = body,
                    contentType = exchange.requestHeaders.getFirst("Content-Type")
                )
            )
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("OK".toByteArray())
            exchange.responseBody.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun createReporter(
        flushIntervalMs: Long = 100,
        maxBatchSize: Int = 100
    ): McpMetricReporter = McpMetricReporter(
        endpoint = "http://localhost:$serverPort/api/admin/metrics/ingest",
        tenantId = "test-tenant",
        serverName = "test-mcp",
        flushIntervalMs = flushIntervalMs,
        maxBatchSize = maxBatchSize
    )

    @Nested
    inner class ToolCallReporting {

        @Test
        fun `should send tool call to correct endpoint`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "analyze_error",
                durationMs = 250,
                success = true,
                runId = "run-xyz"
            )

            // Wait for flush
            Thread.sleep(300)
            reporter.stop()

            receivedRequests.any { it.path == "/api/admin/metrics/ingest/tool-call" } shouldBe true

            val toolCallReq = receivedRequests.first { it.path.endsWith("/tool-call") }
            toolCallReq.contentType shouldBe "application/json"
            toolCallReq.body shouldContain "\"toolName\":\"analyze_error\""
            toolCallReq.body shouldContain "\"durationMs\":250"
            toolCallReq.body shouldContain "\"success\":true"
            toolCallReq.body shouldContain "\"tenantId\":\"test-tenant\""
            toolCallReq.body shouldContain "\"mcpServerName\":\"test-mcp\""
            toolCallReq.body shouldContain "\"runId\":\"run-xyz\""
        }

        @Test
        fun `should include error fields on failure`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "fetch_data",
                durationMs = 5000,
                success = false,
                errorClass = "TimeoutException",
                errorMessage = "Connection timed out"
            )

            Thread.sleep(300)
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "\"errorClass\":\"TimeoutException\""
            req.body shouldContain "\"errorMessage\":\"Connection timed out\""
            req.body shouldContain "\"success\":false"
        }

        @Test
        fun `should omit null error fields`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "simple_tool",
                durationMs = 50,
                success = true
            )

            Thread.sleep(300)
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "\"success\":true"
            // null fields should be filtered out
            (req.body.contains("errorClass") ) shouldBe false
            (req.body.contains("errorMessage")) shouldBe false
        }
    }

    @Nested
    inner class HealthReporting {

        @Test
        fun `should send health event to correct endpoint`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportHealth(
                status = "CONNECTED",
                responseTimeMs = 45,
                toolCount = 5
            )

            Thread.sleep(300)
            reporter.stop()

            receivedRequests.any { it.path == "/api/admin/metrics/ingest/mcp-health" } shouldBe true

            val healthReq = receivedRequests.first { it.path.endsWith("/mcp-health") }
            healthReq.body shouldContain "\"serverName\":\"test-mcp\""
            healthReq.body shouldContain "\"status\":\"CONNECTED\""
            healthReq.body shouldContain "\"responseTimeMs\":45"
            healthReq.body shouldContain "\"toolCount\":5"
        }

        @Test
        fun `should report disconnected status with error`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportHealth(
                status = "FAILED",
                errorClass = "IOException",
                errorMessage = "Connection refused"
            )

            Thread.sleep(300)
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/mcp-health") }
            req.body shouldContain "\"status\":\"FAILED\""
            req.body shouldContain "\"errorClass\":\"IOException\""
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `start should be idempotent`() {
            val reporter = createReporter()
            reporter.start()
            reporter.start() // second call should be no-op
            reporter.stop()
        }

        @Test
        fun `stop should be idempotent`() {
            val reporter = createReporter()
            reporter.start()
            reporter.stop()
            reporter.stop() // second call should be no-op
        }

        @Test
        fun `stop should flush remaining events`() {
            val reporter = createReporter(flushIntervalMs = 60000) // long interval, won't auto-flush
            reporter.start()

            reporter.reportToolCall("tool1", durationMs = 10, success = true)
            reporter.reportHealth(status = "CONNECTED")

            // stop() should flush the remaining events
            reporter.stop()

            // Both events should have been sent
            receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            receivedRequests.any { it.path.endsWith("/mcp-health") } shouldBe true
        }
    }

    @Nested
    inner class QueueOverflow {

        @Test
        fun `should drop events when queue is full`() {
            val reporter = McpMetricReporter(
                endpoint = "http://localhost:$serverPort/api/admin/metrics/ingest",
                tenantId = "test-tenant",
                serverName = "test-mcp",
                flushIntervalMs = 60000, // don't auto-flush
                maxQueueSize = 5
            )
            // Don't start — events won't flush, queue fills up
            reporter.start()

            // Fill queue beyond capacity
            repeat(10) { i ->
                reporter.reportToolCall("tool-$i", durationMs = 1, success = true)
            }

            // Queue should be capped — some events dropped silently
            // Verify the reporter doesn't throw
            reporter.stop()
        }
    }

    @Nested
    inner class JsonEscaping {

        @Test
        fun `should escape special characters in JSON`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "tool_with\"quotes",
                durationMs = 10,
                success = false,
                errorMessage = "Line1\nLine2\tTabbed\\Backslash"
            )

            Thread.sleep(300)
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "tool_with\\\"quotes"
            req.body shouldContain "Line1\\nLine2\\tTabbed\\\\Backslash"
        }
    }

    @Nested
    inner class ErrorMessageTruncation {

        @Test
        fun `should truncate long error messages to 500 chars`() {
            val reporter = createReporter()
            reporter.start()

            val longMessage = "x".repeat(1000)
            reporter.reportToolCall(
                toolName = "tool",
                durationMs = 10,
                success = false,
                errorMessage = longMessage
            )

            Thread.sleep(300)
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            // The body should contain a truncated message (500 chars max)
            val errorFieldLength = req.body.substringAfter("\"errorMessage\":\"")
                .substringBefore("\"")
                .length
            (errorFieldLength <= 500) shouldBe true
        }
    }
}
