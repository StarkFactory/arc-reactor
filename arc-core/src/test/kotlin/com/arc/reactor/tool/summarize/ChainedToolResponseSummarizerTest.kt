package com.arc.reactor.tool.summarize

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ChainedToolResponseSummarizer] 단위 테스트.
 *
 * R230: 여러 summarizer를 순서대로 시도하는 조합 유틸의 동작 검증.
 * R226 `ChainedApprovalContextResolverTest`와 동일한 테스트 패턴.
 */
class ChainedToolResponseSummarizerTest {

    private val samplePayload = """[{"key":"HRFW-42"}]"""
    private val sampleTool = "jira_search_issues"

    @Nested
    inner class EmptyChain {

        @Test
        fun `빈 체인은 항상 null을 반환해야 한다`() {
            val chain = ChainedToolResponseSummarizer(emptyList())
            assertNull(chain.summarize(sampleTool, samplePayload, success = true)) {
                "빈 체인의 summarize는 null이어야 한다"
            }
        }

        @Test
        fun `EMPTY 상수도 null을 반환해야 한다`() {
            assertNull(
                ChainedToolResponseSummarizer.EMPTY.summarize("any", "any", true)
            ) { "EMPTY 상수는 null" }
        }

        @Test
        fun `varargs 인자 없이 생성하면 빈 체인이어야 한다`() {
            val chain = ChainedToolResponseSummarizer()
            assertTrue(chain.isEmpty()) { "빈 varargs → isEmpty true" }
            assertEquals(0, chain.size()) { "size 0" }
        }
    }

    @Nested
    inner class SingleSummarizer {

        @Test
        fun `단일 summarizer 결과가 그대로 반환되어야 한다`() {
            val expected = ToolResponseSummary(
                text = "test result",
                kind = SummaryKind.TEXT_FULL,
                originalLength = 11
            )
            val summarizer = mockk<ToolResponseSummarizer>()
            every { summarizer.summarize(any(), any(), any()) } returns expected

            val chain = ChainedToolResponseSummarizer(summarizer)
            val result = chain.summarize(sampleTool, samplePayload, true)

            assertEquals(expected, result) { "단일 summarizer 결과가 그대로" }
            verify(exactly = 1) { summarizer.summarize(sampleTool, samplePayload, true) }
        }

        @Test
        fun `단일 summarizer가 null이면 체인도 null이어야 한다`() {
            val summarizer = mockk<ToolResponseSummarizer>()
            every { summarizer.summarize(any(), any(), any()) } returns null

            val chain = ChainedToolResponseSummarizer(summarizer)
            assertNull(chain.summarize("any", "", true)) { "단일 null → 체인 null" }
        }

        @Test
        fun `단일 summarizer size는 1이어야 한다`() {
            val chain = ChainedToolResponseSummarizer(mockk<ToolResponseSummarizer>())
            assertEquals(1, chain.size()) { "size=1" }
            assertFalse(chain.isEmpty()) { "비어있지 않음" }
        }
    }

    @Nested
    inner class MultiSummarizerOrdering {

        @Test
        fun `첫 번째 summarizer가 non-null이면 두 번째는 호출 안 됨`() {
            val firstSummary = ToolResponseSummary("from first", SummaryKind.TEXT_FULL, 10)
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } returns firstSummary
            every { second.summarize(any(), any(), any()) } returns
                ToolResponseSummary("from second", SummaryKind.TEXT_FULL, 11)

            val chain = ChainedToolResponseSummarizer(first, second)
            val result = chain.summarize(sampleTool, samplePayload, true)

            assertEquals(firstSummary, result)
            verify(exactly = 1) { first.summarize(any(), any(), any()) }
            verify(exactly = 0) { second.summarize(any(), any(), any()) }
        }

        @Test
        fun `첫 번째 null → 두 번째 호출 순서 보장`() {
            val secondSummary = ToolResponseSummary("fallback", SummaryKind.STRUCTURED, 20)
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } returns null
            every { second.summarize(any(), any(), any()) } returns secondSummary

            val chain = ChainedToolResponseSummarizer(first, second)
            val result = chain.summarize(sampleTool, samplePayload, false)

            assertEquals(secondSummary, result)
            verifyOrder {
                first.summarize(sampleTool, samplePayload, false)
                second.summarize(sampleTool, samplePayload, false)
            }
        }

        @Test
        fun `모두 null → 체인 null`() {
            val summarizers = List(3) {
                mockk<ToolResponseSummarizer> {
                    every { summarize(any(), any(), any()) } returns null
                }
            }
            val chain = ChainedToolResponseSummarizer(summarizers)
            assertNull(chain.summarize("any", "", true))
            summarizers.forEach { verify(exactly = 1) { it.summarize(any(), any(), any()) } }
        }

        @Test
        fun `3개 체인에서 두 번째만 non-null → 세 번째 호출 안 됨`() {
            val middleSummary = ToolResponseSummary("middle", SummaryKind.EMPTY, 0)
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            val third = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } returns null
            every { second.summarize(any(), any(), any()) } returns middleSummary
            every { third.summarize(any(), any(), any()) } returns
                ToolResponseSummary("should not see", SummaryKind.TEXT_FULL, 10)

            val chain = ChainedToolResponseSummarizer(first, second, third)
            val result = chain.summarize("tool", "payload", true)

            assertEquals(middleSummary, result)
            verify(exactly = 1) { first.summarize(any(), any(), any()) }
            verify(exactly = 1) { second.summarize(any(), any(), any()) }
            verify(exactly = 0) { third.summarize(any(), any(), any()) }
        }
    }

    @Nested
    inner class FailOpenBehavior {

        @Test
        fun `첫 summarizer 예외 → 두 번째로 진행`() {
            val recoverSummary = ToolResponseSummary("recovered", SummaryKind.TEXT_FULL, 9)
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } throws RuntimeException("first broken")
            every { second.summarize(any(), any(), any()) } returns recoverSummary

            val chain = ChainedToolResponseSummarizer(first, second)
            val result = chain.summarize("tool", "payload", true)

            assertEquals(recoverSummary, result) { "예외 후 다음으로 진행" }
        }

        @Test
        fun `모든 summarizer가 예외를 던지면 null 반환`() {
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } throws IllegalStateException("a")
            every { second.summarize(any(), any(), any()) } throws RuntimeException("b")

            val chain = ChainedToolResponseSummarizer(first, second)
            assertNull(chain.summarize("tool", "payload", true)) { "모두 예외 → null" }
        }

        @Test
        fun `예외 후에도 인자가 그대로 다음 summarizer로 전달되어야 한다`() {
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } throws RuntimeException("fail")
            every { second.summarize(any(), any(), any()) } returns null

            val chain = ChainedToolResponseSummarizer(first, second)
            chain.summarize("tool_x", "payload_x", false)

            verify(exactly = 1) { first.summarize("tool_x", "payload_x", false) }
            verify(exactly = 1) { second.summarize("tool_x", "payload_x", false) }
        }
    }

    @Nested
    inner class ImmutableConstruction {

        @Test
        fun `생성 후 원본 리스트 변경이 체인에 영향 없어야 한다`() {
            val sentinel = ToolResponseSummary("sentinel", SummaryKind.TEXT_FULL, 8)
            val mutableList = mutableListOf<ToolResponseSummarizer>(
                mockk { every { summarize(any(), any(), any()) } returns sentinel }
            )
            val chain = ChainedToolResponseSummarizer(mutableList)
            assertEquals(1, chain.size()) { "초기 size=1" }

            mutableList.clear()
            mutableList.add(mockk { every { summarize(any(), any(), any()) } returns null })

            assertEquals(1, chain.size()) { "체인은 여전히 size=1" }
            val result = chain.summarize("any", "", true)
            assertEquals(sentinel, result) { "초기 summarizer가 그대로 동작" }
        }
    }

    @Nested
    inner class IntegrationWithDefault {

        /**
         * 실제 사용 패턴: NoOp → Default fallback.
         * NoOp은 항상 null이므로 Default가 fallback으로 동작해야 한다.
         */
        @Test
        fun `NoOp 단독 → Default 체인에서 Default가 동작해야 한다`() {
            val chain = ChainedToolResponseSummarizer(
                NoOpToolResponseSummarizer,
                DefaultToolResponseSummarizer()
            )
            val result = chain.summarize(
                "jira_search_issues",
                """[{"key":"HRFW-1"}]""",
                success = true
            )
            assertNotNull(result) { "Default fallback이 결과를 반환해야 한다" }
            assertEquals(SummaryKind.LIST_TOP_N, result!!.kind)
            assertEquals(1, result.itemCount)
        }

        @Test
        fun `두 Default 체인에서 첫 번째만 호출되어야 한다`() {
            val chain = ChainedToolResponseSummarizer(
                DefaultToolResponseSummarizer(),
                DefaultToolResponseSummarizer()
            )
            val result = chain.summarize(
                "tool",
                """{"error":"failed"}""",
                success = false
            )
            assertNotNull(result) { "첫 번째 Default가 결과 반환" }
            assertEquals(SummaryKind.ERROR_CAUSE_FIRST, result!!.kind)
        }

        @Test
        fun `빈 payload도 Default에 의해 처리되어야 한다`() {
            val chain = ChainedToolResponseSummarizer(
                DefaultToolResponseSummarizer()
            )
            val result = chain.summarize("tool", "", success = true)
            assertNotNull(result) { "빈 payload도 EMPTY kind로 요약" }
            assertEquals(SummaryKind.EMPTY, result!!.kind)
        }
    }

    @Nested
    inner class ConstructorEquivalence {

        @Test
        fun `varargs와 List 생성자는 동등한 결과를 생성해야 한다`() {
            val summary = ToolResponseSummary("eq", SummaryKind.TEXT_FULL, 2)
            val r1 = mockk<ToolResponseSummarizer>()
            val r2 = mockk<ToolResponseSummarizer>()
            every { r1.summarize(any(), any(), any()) } returns null
            every { r2.summarize(any(), any(), any()) } returns summary

            val viaVarargs = ChainedToolResponseSummarizer(r1, r2)
            val viaList = ChainedToolResponseSummarizer(listOf(r1, r2))

            assertEquals(viaVarargs.size(), viaList.size())
            assertEquals(
                viaVarargs.summarize("tool", "payload", true),
                viaList.summarize("tool", "payload", true)
            ) { "동일 결과" }
        }
    }

    @Nested
    inner class SuccessFlagPropagation {

        @Test
        fun `success 플래그는 모든 summarizer로 전달되어야 한다`() {
            val first = mockk<ToolResponseSummarizer>()
            val second = mockk<ToolResponseSummarizer>()
            every { first.summarize(any(), any(), any()) } returns null
            every { second.summarize(any(), any(), any()) } returns null

            val chain = ChainedToolResponseSummarizer(first, second)
            chain.summarize("tool", "payload", success = false)

            verify(exactly = 1) { first.summarize("tool", "payload", false) }
            verify(exactly = 1) { second.summarize("tool", "payload", false) }
        }
    }
}
