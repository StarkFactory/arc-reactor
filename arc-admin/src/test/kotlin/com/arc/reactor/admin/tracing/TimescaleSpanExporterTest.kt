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
        fun `success immediately without calling jdbcTemplate를 반환한다`() {
            val result = exporter.export(emptyList())

            result.isSuccess shouldBe true
            verify(exactly = 0) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }
    }

    @Nested
    inner class BatchInsert {

        @Test
        fun `batchUpdate once for a single span를 호출한다`() {
            every { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe true
            verify(exactly = 1) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }

        @Test
        fun `batchUpdate once for multiple spans를 호출한다`() {
            every { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } returns IntArray(5) { 1 }

            val result = exporter.export((1..5).map { buildSpan(name = "span-$it") })

            result.isSuccess shouldBe true
            verify(exactly = 1) { jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }

        @Test
        fun `getBatchSize은(는) matches the number of spans submitted`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns IntArray(3)

            exporter.export((1..3).map { buildSpan(name = "span-$it") })

            setterSlot.captured.getBatchSize() shouldBe 3
        }

        @Test
        fun `SQL은(는) metric_spans table를 대상으로 한다`() {
            val sqlSlot = slot<String>()
            every { jdbcTemplate.batchUpdate(capture(sqlSlot), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            sqlSlot.captured shouldContain "INSERT INTO metric_spans"
        }

        @Test
        fun `SQL은(는) jsonb cast for attributes parameter를 포함한다`() {
            val sqlSlot = slot<String>()
            every { jdbcTemplate.batchUpdate(capture(sqlSlot), any<BatchPreparedStatementSetter>()) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            sqlSlot.captured shouldContain "::jsonb"
        }
    }

    @Nested
    inner class PreparedStatementValues {

        @Test
        fun `traceId at parameter index 3를 설정한다`() {
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
        fun `spanId at parameter index 4를 설정한다`() {
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
        fun `parentSpanId to null when span has no valid parent를 설정한다`() {
            val setterSlot = slot<BatchPreparedStatementSetter>()
            every { jdbcTemplate.batchUpdate(any<String>(), capture(setterSlot)) } returns intArrayOf(1)

            exporter.export(listOf(buildSpan()))

            val ps = mockk<PreparedStatement>(relaxed = true)
            setterSlot.captured.setValues(ps, 0)

            verify { ps.setString(5, null) }
        }

        @Test
        fun `parentSpanId when span has valid parent를 설정한다`() {
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
        fun `sets success to true when span status은(는) OK이다`() {
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
        fun `sets success to false when span status은(는) ERROR이다`() {
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
        fun `durationMs from start and end epoch nanos를 계산한다`() {
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
        fun `defaults tenant_id to default when attribute은(는) absent이다`() {
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
        fun `present일 때 tenant_id attribute value를 사용한다`() {
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
        fun `defaults service name to arc-reactor when resource attribute은(는) absent이다`() {
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
        fun `present일 때 service_name resource attribute를 사용한다`() {
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
        fun `span has no attributes일 때 empty object를 반환한다`() {
            val json = captureAttributesJson(listOf(buildSpan(attributes = Attributes.empty())))

            json shouldBe "{}"
        }

        @Test
        fun `tenant_id key from serialized attributes를 제외한다`() {
            val attrs = Attributes.of(
                AttributeKey.stringKey("tenant_id"), "acme",
                AttributeKey.stringKey("run_id"), "run-42"
            )
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldNotContain "tenant_id"
            json shouldContain "run_id"
        }

        @Test
        fun `serializes은(는) single attribute as a valid JSON key-value pair`() {
            val attrs = Attributes.of(AttributeKey.stringKey("run_id"), "run-1")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldBe """{"run_id":"run-1"}"""
        }

        @Test
        fun `serializes은(는) multiple attributes with correct JSON structure`() {
            val attrs = Attributes.of(
                AttributeKey.stringKey("model"), "gpt-4",
                AttributeKey.stringKey("provider"), "openai"
            )
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """"model":"gpt-4""""
            json shouldContain """"provider":"openai""""
        }

        @Test
        fun `only tenant_id attribute is present일 때 empty object를 반환한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("tenant_id"), "acme")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldBe "{}"
        }
    }

    @Nested
    inner class JsonEscaping {

        @Test
        fun `double-quote characters in attribute values를 이스케이프한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("msg"), "say \"hello\"")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\""""
            // The raw unescaped quote must not appear inside the JSON string value
            json shouldNotContain """"say "hello""""
        }

        @Test
        fun `backslash characters in attribute values를 이스케이프한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("path"), "C:\\Users\\stark")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            // Each backslash becomes \\
            json shouldContain """\\"""
        }

        @Test
        fun `newline characters in attribute values를 이스케이프한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "line1\nline2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\n"""
        }

        @Test
        fun `carriage-return characters in attribute values를 이스케이프한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "line1\rline2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\r"""
        }

        @Test
        fun `tab characters in attribute values를 이스케이프한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("body"), "col1\tcol2")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """\t"""
        }

        @Test
        fun `double-quote characters in attribute keys를 이스케이프한다`() {
            val key = AttributeKey.stringKey("key\"with\"quotes")
            val attrs = Attributes.of(key, "value")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain """key\"with\"quotes"""
        }

        @Test
        fun `unicode characters passthrough without escaping를 처리한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("msg"), "\u4e2d\u6587\u6d4b\u8bd5")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain "\u4e2d\u6587\u6d4b\u8bd5"
        }

        @Test
        fun `emoji characters passthrough without escaping를 처리한다`() {
            val attrs = Attributes.of(AttributeKey.stringKey("status"), "ok \uD83D\uDE80")
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json shouldContain "\uD83D\uDE80"
        }

        @Test
        fun `combined escaping in a single attribute value를 처리한다`() {
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
        fun `special은(는) characters in attribute values do not break JSON envelope`(input: String) {
            val attrs = Attributes.of(AttributeKey.stringKey("k"), input)
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            json.startsWith("{") shouldBe true
            json.endsWith("}") shouldBe true
            json shouldContain """"k":"""
        }

        @Test
        fun `control characters U+0000 to U+001F as unicode escape sequences를 이스케이프한다`() {
            val controlChars = String(CharArray(32) { it.toChar() })
            val attrs = Attributes.of(AttributeKey.stringKey("ctrl"), controlChars)
            val json = captureAttributesJson(listOf(buildSpan(attributes = attrs)))

            // \n, \r, \t은(는) use their short forms해야 합니다
            json shouldContain """\n"""
            json shouldContain """\r"""
            json shouldContain """\t"""
            // NUL (U+0000)은(는) be escaped as \u0000해야 합니다
            json shouldContain """\u0000"""
            // SOH (U+0001)은(는) be escaped as \u0001해야 합니다
            json shouldContain """\u0001"""
            // US (U+001F)은(는) be escaped as \u001f해야 합니다
            json shouldContain """\u001f"""
            // No raw control characters은(는) remain in the JSON해야 합니다
            val valueStart = json.indexOf(":\"") + 2
            val valueEnd = json.lastIndexOf("\"}")
            val extractedValue = json.substring(valueStart, valueEnd)
            extractedValue.none { it.code in 0x00..0x1F } shouldBe true
        }

        @Test
        fun `very long attribute value exceeding 50KB를 처리한다`() {
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
        fun `jdbcTemplate throws일 때 failure result를 반환한다`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } throws RuntimeException("DB connection lost")

            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe false
        }

        @Test
        fun `propagate the exception to the caller하지 않는다`() {
            every {
                jdbcTemplate.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>())
            } throws RuntimeException("deadlock detected")

            // not throw; caller receives a failure result code instead해야 합니다
            val result = exporter.export(listOf(buildSpan()))

            result.isSuccess shouldBe false
        }

        @Test
        fun `batchUpdate completes without exception일 때 success를 반환한다`() {
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
        fun `flush은(는) always returns success`() {
            exporter.flush().isSuccess shouldBe true
        }

        @Test
        fun `shutdown은(는) always returns success`() {
            exporter.shutdown().isSuccess shouldBe true
        }
    }

    @Nested
    inner class MultipleBatchItems {

        @Test
        fun `setValues은(는) resolves each span by its sequential index`() {
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
        fun `a batch of 10 spans은(는) handled in a single batchUpdate call이다`() {
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
