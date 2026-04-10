package com.arc.reactor.approval

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
 * [ChainedApprovalContextResolver] 단위 테스트.
 *
 * R226: 여러 리졸버를 순서대로 시도하는 조합 유틸의 동작을 검증한다.
 * short-circuit, fail-open, 순서 보존, thread-safety 관련 invariant 확인.
 */
class ChainedApprovalContextResolverTest {

    private val sampleArgs = mapOf("issueKey" to "HRFW-42")

    @Nested
    inner class EmptyChain {

        @Test
        fun `빈 체인은 항상 null을 반환해야 한다`() {
            val chain = ChainedApprovalContextResolver(emptyList())
            val result = chain.resolve("jira_get_issue", sampleArgs)
            assertNull(result) { "빈 체인의 resolve는 null이어야 한다" }
        }

        @Test
        fun `EMPTY 상수도 null을 반환해야 한다`() {
            val result = ChainedApprovalContextResolver.EMPTY.resolve("any_tool", emptyMap())
            assertNull(result) { "EMPTY 상수는 null을 반환해야 한다" }
        }

        @Test
        fun `varargs로 인자 없이 생성하면 빈 체인이어야 한다`() {
            val chain = ChainedApprovalContextResolver()
            assertTrue(chain.isEmpty()) { "빈 varargs는 isEmpty true" }
            assertEquals(0, chain.size()) { "size는 0" }
        }

        @Test
        fun `isEmpty와 size는 빈 체인에 대해 일관되어야 한다`() {
            val chain = ChainedApprovalContextResolver(emptyList())
            assertTrue(chain.isEmpty()) { "isEmpty true" }
            assertEquals(0, chain.size()) { "size 0" }
        }
    }

    @Nested
    inner class SingleResolver {

        @Test
        fun `단일 리졸버가 결과를 반환하면 그대로 전달해야 한다`() {
            val expected = ApprovalContext(reason = "test", action = "jira_get_issue")
            val resolver = mockk<ApprovalContextResolver>()
            every { resolver.resolve("jira_get_issue", sampleArgs) } returns expected

            val chain = ChainedApprovalContextResolver(resolver)
            val result = chain.resolve("jira_get_issue", sampleArgs)

            assertEquals(expected, result) { "단일 리졸버의 결과가 그대로 반환되어야 한다" }
            verify(exactly = 1) { resolver.resolve("jira_get_issue", sampleArgs) }
        }

        @Test
        fun `단일 리졸버가 null을 반환하면 체인도 null이어야 한다`() {
            val resolver = mockk<ApprovalContextResolver>()
            every { resolver.resolve(any(), any()) } returns null

            val chain = ChainedApprovalContextResolver(resolver)
            val result = chain.resolve("any_tool", emptyMap())

            assertNull(result) { "단일 리졸버의 null은 체인 null로 전달되어야 한다" }
        }

        @Test
        fun `단일 리졸버 size는 1이어야 한다`() {
            val chain = ChainedApprovalContextResolver(mockk<ApprovalContextResolver>())
            assertEquals(1, chain.size()) { "단일 리졸버 체인의 size는 1" }
            assertFalse(chain.isEmpty()) { "비어있지 않아야 한다" }
        }
    }

    @Nested
    inner class MultiResolverOrdering {

        @Test
        fun `첫 번째 리졸버가 non-null을 반환하면 두 번째는 호출되지 않아야 한다`() {
            val firstCtx = ApprovalContext(reason = "first", action = "from-first")
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } returns firstCtx
            every { second.resolve(any(), any()) } returns ApprovalContext(reason = "second")

            val chain = ChainedApprovalContextResolver(first, second)
            val result = chain.resolve("jira_get_issue", sampleArgs)

            assertEquals(firstCtx, result) { "첫 번째 리졸버의 결과가 사용되어야 한다" }
            verify(exactly = 1) { first.resolve("jira_get_issue", sampleArgs) }
            verify(exactly = 0) { second.resolve(any(), any()) }
        }

        @Test
        fun `첫 번째가 null을 반환하면 두 번째 리졸버가 호출되어야 한다`() {
            val secondCtx = ApprovalContext(reason = "second", action = "from-second")
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } returns null
            every { second.resolve(any(), any()) } returns secondCtx

            val chain = ChainedApprovalContextResolver(first, second)
            val result = chain.resolve("unknown_tool", emptyMap())

            assertEquals(secondCtx, result) { "두 번째 리졸버의 결과가 사용되어야 한다" }
            verifyOrder {
                first.resolve("unknown_tool", emptyMap())
                second.resolve("unknown_tool", emptyMap())
            }
        }

        @Test
        fun `모든 리졸버가 null을 반환하면 체인도 null이어야 한다`() {
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            val third = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } returns null
            every { second.resolve(any(), any()) } returns null
            every { third.resolve(any(), any()) } returns null

            val chain = ChainedApprovalContextResolver(first, second, third)
            val result = chain.resolve("tool", emptyMap())

            assertNull(result) { "모두 null이면 체인도 null" }
            verify(exactly = 1) { first.resolve(any(), any()) }
            verify(exactly = 1) { second.resolve(any(), any()) }
            verify(exactly = 1) { third.resolve(any(), any()) }
        }

        @Test
        fun `3개 리졸버에서 두 번째만 non-null이면 세 번째는 호출되지 않아야 한다`() {
            val secondCtx = ApprovalContext(reason = "second")
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            val third = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } returns null
            every { second.resolve(any(), any()) } returns secondCtx
            every { third.resolve(any(), any()) } returns ApprovalContext(reason = "third")

            val chain = ChainedApprovalContextResolver(first, second, third)
            val result = chain.resolve("tool", emptyMap())

            assertEquals(secondCtx, result) { "두 번째의 결과" }
            verify(exactly = 1) { first.resolve(any(), any()) }
            verify(exactly = 1) { second.resolve(any(), any()) }
            verify(exactly = 0) { third.resolve(any(), any()) }
        }
    }

    @Nested
    inner class FailOpenBehavior {

        @Test
        fun `첫 번째 리졸버가 예외를 던지면 두 번째로 진행해야 한다`() {
            val secondCtx = ApprovalContext(reason = "recovered")
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } throws RuntimeException("first broken")
            every { second.resolve(any(), any()) } returns secondCtx

            val chain = ChainedApprovalContextResolver(first, second)
            val result = chain.resolve("tool", emptyMap())

            assertEquals(secondCtx, result) { "예외 후 다음 리졸버로 넘어가야 한다" }
        }

        @Test
        fun `모든 리졸버가 예외를 던지면 null을 반환해야 한다`() {
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } throws IllegalStateException("first")
            every { second.resolve(any(), any()) } throws RuntimeException("second")

            val chain = ChainedApprovalContextResolver(first, second)
            val result = chain.resolve("tool", emptyMap())

            assertNull(result) { "모든 리졸버가 예외를 던지면 null" }
        }

        @Test
        fun `예외 후에도 체인은 다음 리졸버에 동일한 인자를 전달해야 한다`() {
            val first = mockk<ApprovalContextResolver>()
            val second = mockk<ApprovalContextResolver>()
            every { first.resolve(any(), any()) } throws RuntimeException("fail")
            every { second.resolve(any(), any()) } returns null

            val chain = ChainedApprovalContextResolver(first, second)
            chain.resolve("tool_x", mapOf("k" to "v"))

            verify(exactly = 1) { first.resolve("tool_x", mapOf("k" to "v")) }
            verify(exactly = 1) { second.resolve("tool_x", mapOf("k" to "v")) }
        }
    }

    @Nested
    inner class ImmutableConstruction {

        @Test
        fun `생성 후 원본 리스트를 변경해도 체인에 영향이 없어야 한다`() {
            val mutableList = mutableListOf<ApprovalContextResolver>(
                mockk { every { resolve(any(), any()) } returns ApprovalContext(reason = "a") }
            )
            val chain = ChainedApprovalContextResolver(mutableList)
            assertEquals(1, chain.size()) { "초기 size는 1" }

            // 외부에서 리스트 변경
            mutableList.add(mockk { every { resolve(any(), any()) } returns ApprovalContext(reason = "b") })
            mutableList.clear()

            // 체인은 여전히 초기 상태를 유지
            assertEquals(1, chain.size()) { "체인 size는 여전히 1이어야 한다" }
            val result = chain.resolve("any", emptyMap())
            assertNotNull(result) { "초기 리졸버는 여전히 호출되어야 한다" }
            assertEquals("a", result!!.reason) { "초기 리졸버의 결과가 반환되어야 한다" }
        }
    }

    @Nested
    inner class IntegrationWithRealResolvers {

        /**
         * 실제 사용 패턴: Atlassian resolver → Heuristic fallback.
         */
        @Test
        fun `Atlassian 우선 Heuristic fallback 패턴이 동작해야 한다`() {
            val chain = ChainedApprovalContextResolver(
                AtlassianApprovalContextResolver(),
                HeuristicApprovalContextResolver()
            )

            // atlassian 도구는 AtlassianResolver가 처리
            val jiraCtx = chain.resolve("jira_get_issue", mapOf("issueKey" to "HRFW-5695"))
            assertNotNull(jiraCtx) { "Jira 도구는 컨텍스트를 받아야 한다" }
            assertEquals("HRFW-5695", jiraCtx!!.impactScope) { "Atlassian resolver가 처리" }
            assertTrue(jiraCtx.reason?.contains("Jira") == true) {
                "Atlassian resolver의 reason이 사용되어야 한다"
            }
            assertEquals(Reversibility.REVERSIBLE, jiraCtx.reversibility) {
                "Atlassian은 모두 REVERSIBLE"
            }
        }

        @Test
        fun `atlassian 외 도구는 Heuristic fallback이 처리해야 한다`() {
            val chain = ChainedApprovalContextResolver(
                AtlassianApprovalContextResolver(),
                HeuristicApprovalContextResolver()
            )

            // delete_order는 atlassian 아니지만 Heuristic가 IRREVERSIBLE로 분류
            val deleteCtx = chain.resolve("delete_order", mapOf("orderId" to "42"))
            assertNotNull(deleteCtx) { "delete_order도 컨텍스트를 받아야 한다" }
            assertEquals(Reversibility.IRREVERSIBLE, deleteCtx!!.reversibility) {
                "Heuristic resolver가 delete_* 를 IRREVERSIBLE로 분류"
            }
        }

        @Test
        fun `양쪽 모두 처리할 수 없는 도구도 안전하게 처리되어야 한다`() {
            // HeuristicResolver는 알려지지 않은 도구도 UNKNOWN으로 처리하므로
            // 이 테스트는 Heuristic이 non-null을 반환함을 확인
            val chain = ChainedApprovalContextResolver(
                AtlassianApprovalContextResolver(),
                HeuristicApprovalContextResolver()
            )

            val ctx = chain.resolve("custom_unknown_tool", emptyMap())
            assertNotNull(ctx) { "Heuristic resolver가 UNKNOWN으로 처리" }
            assertEquals(Reversibility.UNKNOWN, ctx!!.reversibility) {
                "알 수 없는 도구는 UNKNOWN"
            }
        }
    }

    @Nested
    inner class ConstructorEquivalence {

        @Test
        fun `varargs와 List 생성자 결과는 동등해야 한다`() {
            val r1 = mockk<ApprovalContextResolver>()
            val r2 = mockk<ApprovalContextResolver>()
            every { r1.resolve(any(), any()) } returns null
            every { r2.resolve(any(), any()) } returns ApprovalContext(reason = "test")

            val viaVarargs = ChainedApprovalContextResolver(r1, r2)
            val viaList = ChainedApprovalContextResolver(listOf(r1, r2))

            assertEquals(viaVarargs.size(), viaList.size()) { "size 동등" }

            val result1 = viaVarargs.resolve("tool", emptyMap())
            val result2 = viaList.resolve("tool", emptyMap())
            assertEquals(result1, result2) { "동일 입력에 대해 동일 결과" }
        }
    }
}
