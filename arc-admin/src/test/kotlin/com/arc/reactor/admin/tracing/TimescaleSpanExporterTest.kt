package com.arc.reactor.admin.tracing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement

class TimescaleSpanExporterTest {

    private val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
    private val exporter = TimescaleSpanExporter(jdbcTemplate)

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private fun buildSpan(
        name: String = "test-span",
        traceId: String = "aaaabbbbccccddddaaaabbbbccccdddd",
        spanId: String = "aaaabbbbccccdddd",
        parentSpanId: String = "0000000000000000",
        startNanos: Long = 1_000_000_000L,
        endNanos: Long = 2_000_000_000L,
        statusCode: StatusCode = StatusCode.OK,
        attributes: Attributes = Attributes.empty(),
        resourceAttributes: Attributes = Attributes.empty()
    ): SpanData {
        val spanContext = SpanContext.create(
            traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()
        )
        val parentContext = if (parentSpanId == "0000000000000000") {
            SpanContext.getInvalid()
        } else {
            SpanContext.create(
                traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault()
            )
        }
        val resource = Resource.create(resourceAttributes)
        val statusData = when (statusCode) {
            StatusCode.ERROR -> StatusData.error()
            StatusCode.OK -> StatusData.ok()
            else -> StatusData.unset()
        }

        val span = mockk<SpanData>()
        every { span.name } returns name
        every { span.spanContext } returns spanContext
        every { span.parentSpanContext } returns parentContext
        every { span.parentSpanId } returns parentContext.spanId
        every { span.traceId } returns traceId
        every { span.spanId } returns spanId
        every { span.status } returns statusData
        every { span.startEpochNanos } returns startNanos
        every { span.endEpochNanos } returns endNanos
        every { span.attributes } returns attributes
        every { span.resource } returns resource
        every { span.instrumentationScopeInfo } returns InstrumentationScopeInfo.create("test")
        every { span.events } returns emptyList()
        every { span.links } returns emptyList()
        every { span.totalAttributeCount } returns attributes.size()
        every { span.totalRecordedEvents } returns 0
        every { span.totalRecordedLinks } returns 0
        every { span.hasEnded() } returns true
        return span
    }

    /**
     * Captures the BatchPreparedStatementSetter and invokes setValues for the span at the
     * given index, then returns the captured attributes JSON string (parameter index 12).
     */
    private fun captureAttributesJson(spans: Collection<SpanData>, spanIndex: Int = 0): String {
        val setterSlot = slot<BatchPreparedStatementSetter>()
        every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns IntArray(spans.size)

        exporter.export(spans)

        val ps = mockk<PreparedStatement>(relaxed = true)
        val capturedJson = slot<String>()
        every { ps.setString(12, capture(capturedJson)) } returns Unit
        setterSlot.captured.setValues(ps, spanIndex)
        return capturedJson.captured
    }

    // -------------------------------------------------------------------------
    // Test groups
    // -------------------------------------------------------------------------

    @Nested
    inner class EmptyCollection {

        @Test
        fun `returns success immediately without calling jdbcTemplate`() {
            val result = exporter.export(emptyList())

            result.isSuccess shouldBe true
            verify(exactly = 0) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }
    }

    @Nested
    inner class BatchInsert {

        @Test
        fun `calls batchUpdate once for a single span`() {
            every { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe true
            verify(exactly = 1) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }

        @Test
        fun `calls batchUpdate once for multiple spans`() {
            every { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } returns IntArray(5) { 1 }

            val result = exporter.export((1..5).map { buildSpan(name = "span-$it") })

            result.isSuccess shouldBe true
            verify(exactly = 1) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }

        @Test
        fun `getBatchSize matches the number of spans submitted`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns IntArray(3)

            exporter.export((1..3).map { buildSpan(name = "span-$it") })

            setterSlot.captured.getBatchSize() shouldBe 3
        }

        @Test
        fun `SQL targets metric_spans table`() {
            val sqlSlot = slot<String>()
            every { jdbcTemplate.batchUpdate(capture(sqlSlot), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            sqlSlot.captured shouldContain "INSERT INTO metric_spans"
        }

        @Test
        fun `SQL includes jsonb cast for attributes parameter`() {
            val sqlSlot = slot<String>()
            every { jdbcTemplate.batchUpdate(capture(sqlSlot), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            sqlSlot.captured shouldContain "::jsonb"
        }
    }

    @Nested
    inner class PreparedStatementValues {

        @Test
        fun `sets traceId at parameter index 3`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            val traceId = "aabbccddaabbccddaabbccddaabbccdd"
            exporter.export(listOf(buildSpan(traceId = traceId)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(3, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe traceId
        }

        @Test
        fun `sets spanId at parameter index 4`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            val spanId = "1122334455667788"
            exporter.export(listOf(buildSpan(spanId = spanId)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(4, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe spanId
        }

        @Test
        fun `sets parentSpanId to null when span has no valid parent`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            val ps = mockk<PreparedStatement>(relaxed = true)
            setterSlot.captured.setValues(ps, 0)

            verify { ps.setString(5, null) }
        }

        @Test
        fun `sets parentSpanId when span has valid parent`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            val parentId = "aabbccdd11223344"
            exporter.export(listOf(buildSpan(traceId = "aabbccddaabbccddaabbccddaabbccdd", parentSpanId = parentId)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(5, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe parentId
        }

        @Test
        fun `sets success to true when span status is OK`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan(statusCode = StatusCode.OK)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<Boolean>()
            every { ps.setBoolean(10, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe true
        }

        @Test
        fun `sets success to false when span status is ERROR`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan(statusCode = StatusCode.ERROR)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<Boolean>()
            every { ps.setBoolean(10, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe false
        }

        @Test
        fun `computes durationMs from start and end epoch nanos`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            // 1500 ms = 1_500_000_000 ns
            exporter.export(listOf(buildSpan(startNanos = 1_000_000_000L, endNanos = 2_500_000_000L)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<Long>()
            every { ps.setLong(9, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe 1500L
        }

        @Test
        fun `defaults tenant_id to default when attribute is absent`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan(attributes = Attributes.empty())))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(2, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe "default"
        }

        @Test
        fun `uses tenant_id attribute value when present`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            val attrs = Attributes.of(AttributeKey.stringKey("tenant_id"), "acme-corp")
            exporter.export(listOf(buildSpan(attributes = attrs)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(2, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe "acme-corp"
        }

        @Test
        fun `defaults service name to arc-reactor when resource attribute is absent`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan(resourceAttributes = Attributes.empty())))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(8, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe "arc-reactor"
        }

        @Test
        fun `uses service_name resource attribute when present`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            val resAttrs = Attributes.of(AttributeKey.stringKey("service.name"), "my-service")
            exporter.export(listOf(buildSpan(resourceAttributes = resAttrs)))

            val ps = mockk<PreparedStatement>(relaxed = true)
            val captured = slot<String>()
            every { ps.setString(8, capture(captured)) } returns Unit
            setterSlot.captured.setValues(ps, 0)

            captured.captured shouldBe "my-service"
        }
    }

    @Nested
    inner class AttributesJson {

        @Test
        fun `returns empty object when span has no attributes`() {
            val json = captureAttributesJson(listOf(buildSpan(attributes = Attributes.empty())))

            json shouldBe "{}"
        }

        @Test
        fun `excludes tenant_id key from serialized attributes`() {
            val attrs = Attributes.of(
                AttributeKey.stringKey("tenant_id"), "acme",
                AttributeKey.stringKey("run_id"), "run-42"
            )
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldNotContain "tenant_id"
            json shouldContain "run_id"
        }

        @Test
        fun `serializes single attribute as a valid JSON key-value pair`() {
            val attrs = Attributes.of(AttributeKey.stringKey("run_id"), "run-1")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldBe """{"run_id":"run-1"}"""
        }

        @Test
        fun `serializes multiple attributes with correct JSON structure`() {
            val attrs = Attributes.of(
                AttributeKey.stringKey("model"), "gpt-4",
                AttributeKey.stringKey("provider"), "openai"
            )
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """"model":"gpt-4""""
            json shouldContain """"provider":"openai""""
        }

        @Test
        fun `returns empty object when only tenant_id attribute is present`() {
            val attrs = Attributes.of(AttributeKey.stringKey("tenant_id"), "acme")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldBe "{}"
        }
    }

    @Nested
    inner class JsonEscaping {

        @Test
        fun `escapes double-quote characters in attribute values`() {
            val attrs = Attributes.of(AttributeKey.stringKey("msg"), "say \"hello\"")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\""""
            // The raw unescaped quote must not appear inside the JSON string value
            json shouldNotContain """"say "hello""""
        }

        @Test
        fun `escapes backslash characters in attribute values`() {
            val attrs = Attributes.of(AttributeKey.stringKey("path"), "C:\\Users\\stark")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            // Each backslash becomes \\
            json shouldContain """\\"""
        }

        @Test
        fun `escapes newline characters in attribute values`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "line1\nline2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\n"""
        }

        @Test
        fun `escapes carriage-return characters in attribute values`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "line1\rline2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\r"""
        }

        @Test
        fun `escapes tab characters in attribute values`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "col1\tcol2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\t"""
        }

        @Test
        fun `escapes double-quote characters in attribute keys`() {
            val key = AttributeKey.stringKey("key\"with\"quotes")
            val attrs = Attributes.of(key, "value")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """key\"with\"quotes"""
        }

        @Test
        fun `handles unicode characters passthrough without escaping`() {
            val attrs = Attributes.of(AttributeKey.stringKey("msg"), "\u4e2d\u6587\u6d4b\u8bd5")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain "\u4e2d\u6587\u6d4b\u8bd5"
        }

        @Test
        fun `handles emoji characters passthrough without escaping`() {
            val attrs = Attributes.of(AttributeKey.stringKey("status"), "ok \uD83D\uDE80")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain "\uD83D\uDE80"
        }

        @Test
        fun `handles combined escaping in a single attribute value`() {
            val attrs = Attributes.of(
                AttributeKey.stringKey("data"),
                "path: C:\\dir\nval: \"quoted\"\ttabbed"
            )
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\\"""
            json shouldContain """\n"""
            json shouldContain """\""""
            json shouldContain """\t"""
        }

        @ParameterizedTest(name = "attribute value [{0}] produces well-formed JSON structure")
        @MethodSource("com.arc.reactor.admin.tracing.TimescaleSpanExporterTest#jsonEscapeEdgeCases")
        fun `special characters in attribute values do not break JSON envelope`(input: String) {
            val attrs = Attributes.of(AttributeKey.stringKey("k"), input)
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json.startsWith("{") shouldBe true
            json.endsWith("}") shouldBe true
            json shouldContain """"k":"""
        }

        @Test
        fun `handles very long attribute value exceeding 50KB`() {
            val longValue = "a".repeat(60_000)
            val attrs = Attributes.of(AttributeKey.stringKey("big"), longValue)
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """"big":"""
            // Value must not be truncated by the exporter
            json.length shouldBe longValue.length + """{"big":""}""".length
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `returns failure result when jdbcTemplate throws`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } throws RuntimeException("DB connection lost")

            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe false
        }

        @Test
        fun `does not propagate the exception to the caller`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } throws RuntimeException("deadlock detected")

            // Must not throw; caller receives a failure result code instead
            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe false
        }

        @Test
        fun `returns success when batchUpdate completes without exception`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } returns intArrayOf(1)

            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe true
        }
    }

    @Nested
    inner class LifecycleMethods {

        @Test
        fun `flush always returns success`() {
            exporter.flush().isSuccess shouldBe true
        }

        @Test
        fun `shutdown always returns success`() {
            exporter.shutdown().isSuccess shouldBe true
        }
    }

    @Nested
    inner class MultipleBatchItems {

        @Test
        fun `setValues resolves each span by its sequential index`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns IntArray(3)

            val spans = listOf(
                buildSpan(name = "span-A", spanId = "aaaaaaaaaaaaaaaa"),
                buildSpan(name = "span-B", spanId = "bbbbbbbbbbbbbbbb"),
                buildSpan(name = "span-C", spanId = "cccccccccccccccc")
            )
            exporter.export(spans)

            val setter = setterSlot.captured
            setter.getBatchSize() shouldBe 3

            listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "cccccccccccccccc").forEachIndexed { idx, expectedSpanId ->
                val ps = mockk<PreparedStatement>(relaxed = true)
                val captured = slot<String>()
                every { ps.setString(4, capture(captured)) } returns Unit
                setter.setValues(ps, idx)
                captured.captured shouldBe expectedSpanId
            }
        }

        @Test
        fun `a batch of 10 spans is handled in a single batchUpdate call`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } returns IntArray(10) { 1 }

            val result = exporter.export((1..10).map { buildSpan(name = "span-$it") })

            result.isSuccess shouldBe true
            verify(exactly = 1) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }
    }

    companion object {

        @JvmStatic
        fun jsonEscapeEdgeCases(): List<String> = listOf(
            "plain text",
            "with \"double\" quotes",
            "with 'single' quotes",
            "back\\slash",
            "new\nline",
            "carriage\rreturn",
            "tab\there",
            "mixed \\ and \"quotes\" and \n newline",
            "\u0000null byte",
            "\u0001\u0002\u001Fcontrol chars",
            "emoji \uD83D\uDE00\uD83D\uDE80",
            "\u4e2d\u6587",
            "a".repeat(1000),
            "",
            "   ",
            "}}{{",
            "::jsonb injection attempt"
        )
    }
}
