package com.arc.reactor.admin.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
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
        fun `send tool call to correct endpoint해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "analyze_error",
                durationMs = 250,
                success = true,
                runId = "run-xyz"
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path == "/api/admin/metrics/ingest/tool-call" } shouldBe true
            }
            reporter.stop()

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
        fun `include error fields on failure해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "fetch_data",
                durationMs = 5000,
                success = false,
                errorClass = "TimeoutException",
                errorMessage = "Connection timed out"
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            }
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "\"errorClass\":\"TimeoutException\""
            req.body shouldContain "\"errorMessage\":\"Connection timed out\""
            req.body shouldContain "\"success\":false"
        }

        @Test
        fun `omit null error fields해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "simple_tool",
                durationMs = 50,
                success = true
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            }
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "\"success\":true"
            // null fields은(는) be filtered out해야 합니다
            (req.body.contains("errorClass") ) shouldBe false
            (req.body.contains("errorMessage")) shouldBe false
        }
    }

    @Nested
    inner class HealthReporting {

        @Test
        fun `send health event to correct endpoint해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportHealth(
                status = "CONNECTED",
                responseTimeMs = 45,
                toolCount = 5
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path == "/api/admin/metrics/ingest/mcp-health" } shouldBe true
            }
            reporter.stop()

            val healthReq = receivedRequests.first { it.path.endsWith("/mcp-health") }
            healthReq.body shouldContain "\"serverName\":\"test-mcp\""
            healthReq.body shouldContain "\"status\":\"CONNECTED\""
            healthReq.body shouldContain "\"responseTimeMs\":45"
            healthReq.body shouldContain "\"toolCount\":5"
        }

        @Test
        fun `error로 report disconnected status해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportHealth(
                status = "FAILED",
                errorClass = "IOException",
                errorMessage = "Connection refused"
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path.endsWith("/mcp-health") } shouldBe true
            }
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/mcp-health") }
            req.body shouldContain "\"status\":\"FAILED\""
            req.body shouldContain "\"errorClass\":\"IOException\""
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `start은(는) be idempotent해야 한다`() {
            val reporter = createReporter()
            reporter.start()
            reporter.start()  // 두 번째 호출은 아무 동작도 하지 않아야 합니다
            reporter.stop()
        }

        @Test
        fun `stop은(는) be idempotent해야 한다`() {
            val reporter = createReporter()
            reporter.start()
            reporter.stop()
            reporter.stop()  // 두 번째 호출은 아무 동작도 하지 않아야 합니다
        }

        @Test
        fun `stop은(는) flush remaining events해야 한다`() {
            val reporter = createReporter(flushIntervalMs = 60000) // long interval, won't auto-flush
            reporter.start()

            reporter.reportToolCall("tool1", durationMs = 10, success = true)
            reporter.reportHealth(status = "CONNECTED")

            // stop()은(는) flush the remaining events해야 합니다
            reporter.stop()

            // Both events은(는) have been sent해야 합니다
            receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            receivedRequests.any { it.path.endsWith("/mcp-health") } shouldBe true
        }
    }

    @Nested
    inner class QueueOverflow {

        @Test
        fun `queue is full일 때 drop events해야 한다`() {
            val reporter = McpMetricReporter(
                endpoint = "http://localhost:$serverPort/api/admin/metrics/ingest",
                tenantId = "test-tenant",
                serverName = "test-mcp",
                flushIntervalMs = 60000, // don't auto-flush
                maxQueueSize = 5
            )
            // Don't start — events won't flush, queue fills up
            reporter.start()

            // queue beyond capacity를 채웁니다
            repeat(10) { i ->
                reporter.reportToolCall("tool-$i", durationMs = 1, success = true)
            }

            // Queue은(는) be capped — some events dropped silently해야 합니다
            // the reporter doesn't throw 확인
            reporter.stop()
        }
    }

    @Nested
    inner class JsonEscaping {

        @Test
        fun `escape special characters in JSON해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            reporter.reportToolCall(
                toolName = "tool_with\"quotes",
                durationMs = 10,
                success = false,
                errorMessage = "Line1\nLine2\tTabbed\\Backslash"
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            }
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            req.body shouldContain "tool_with\\\"quotes"
            req.body shouldContain "Line1\\nLine2\\tTabbed\\\\Backslash"
        }
    }

    @Nested
    inner class ErrorMessageTruncation {

        @Test
        fun `truncate long error messages to 500 chars해야 한다`() {
            val reporter = createReporter()
            reporter.start()

            val longMessage = "x".repeat(1000)
            reporter.reportToolCall(
                toolName = "tool",
                durationMs = 10,
                success = false,
                errorMessage = longMessage
            )

            await atMost Duration.ofSeconds(2) untilAsserted {
                receivedRequests.any { it.path.endsWith("/tool-call") } shouldBe true
            }
            reporter.stop()

            val req = receivedRequests.first { it.path.endsWith("/tool-call") }
            // 본문에 잘린 메시지가 포함되어야 합니다 (최대 500자)
            val errorFieldLength = req.body.substringAfter("\"errorMessage\":\"")
                .substringBefore("\"")
                .length
            (errorFieldLength <= 500) shouldBe true
        }
    }
}
