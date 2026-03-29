package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.response.VerifiedSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CoordinatorSupport 유틸리티 함수에 대한 테스트.
 *
 * [registerRagVerifiedSources]와 [recordLoopStageLatency]의
 * 정상 동작, 엣지 케이스, 경계값을 검증한다.
 */
class CoordinatorSupportTest {

    /** 테스트용 HookContext를 생성한다. */
    private fun hookContext(runId: String = "run-1"): HookContext = HookContext(
        runId = runId,
        userId = "user-1",
        userPrompt = "테스트 프롬프트"
    )

    /** 테스트용 RetrievedDocument를 생성한다. */
    private fun document(
        id: String = "doc-1",
        source: String? = "https://example.com/doc",
        metadata: Map<String, Any> = mapOf("title" to "Example Doc")
    ): RetrievedDocument = RetrievedDocument(
        id = id,
        content = "문서 내용",
        source = source,
        metadata = metadata
    )

    // ───────────────────────────── registerRagVerifiedSources ─────────────────────────────

    @Nested
    inner class RegisterRagVerifiedSources {

        @Test
        fun `문서가 있는 RagContext를 전달하면 verifiedSources에 등록되어야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트 텍스트",
                documents = listOf(
                    document(id = "doc-1", source = "https://example.com/page1", metadata = mapOf("title" to "Page 1")),
                    document(id = "doc-2", source = "https://example.com/page2", metadata = mapOf("title" to "Page 2"))
                )
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources shouldHaveSize 2 withMessage "두 문서 출처가 등록되어야 한다"
            val urls = ctx.verifiedSources.map { it.url }
            (urls.contains("https://example.com/page1")) shouldBe true withMessage "첫 번째 문서 URL이 등록되어야 한다"
            (urls.contains("https://example.com/page2")) shouldBe true withMessage "두 번째 문서 URL이 등록되어야 한다"
        }

        @Test
        fun `등록된 VerifiedSource의 toolName은 rag이어야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(document())
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources.first().toolName shouldBe "rag" withMessage "RAG 출처의 toolName은 'rag'이어야 한다"
        }

        @Test
        fun `metadata에 title이 없으면 문서 id를 title로 사용해야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(document(id = "doc-fallback-id", source = "https://example.com/a", metadata = emptyMap()))
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources.first().title shouldBe "doc-fallback-id" withMessage "title 없을 때 문서 id를 title로 사용해야 한다"
        }

        @Test
        fun `metadata에 title이 있으면 그 값을 title로 사용해야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(
                    document(source = "https://example.com/b", metadata = mapOf("title" to "메타 제목"))
                )
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources.first().title shouldBe "메타 제목" withMessage "metadata의 title이 VerifiedSource title로 사용되어야 한다"
        }

        @Test
        fun `source가 null인 문서는 건너뛰어야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(
                    document(id = "doc-no-source", source = null),
                    document(id = "doc-with-source", source = "https://example.com/valid")
                )
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources shouldHaveSize 1 withMessage "source가 null인 문서는 등록되지 않아야 한다"
            ctx.verifiedSources.first().url shouldBe "https://example.com/valid" withMessage "유효한 출처만 등록되어야 한다"
        }

        @Test
        fun `source가 blank 문자열인 문서는 건너뛰어야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(
                    document(id = "doc-blank-source", source = "   "),
                    document(id = "doc-valid", source = "https://example.com/valid")
                )
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources shouldHaveSize 1 withMessage "source가 blank인 문서는 등록되지 않아야 한다"
        }

        @Test
        fun `ragResult가 null이면 verifiedSources가 비어 있어야 한다`() {
            val ctx = hookContext()

            registerRagVerifiedSources(null, ctx)

            ctx.verifiedSources.shouldBeEmpty()
            assertTrue(ctx.verifiedSources.isEmpty()) { "ragResult가 null이면 출처가 등록되지 않아야 한다" }
        }

        @Test
        fun `문서가 없는 빈 RagContext는 아무것도 등록하지 않아야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext.EMPTY

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources.shouldBeEmpty()
            assertTrue(ctx.verifiedSources.isEmpty()) { "문서 없는 RagContext는 출처를 등록하지 않아야 한다" }
        }

        @Test
        fun `모든 문서의 source가 null이면 verifiedSources가 비어 있어야 한다`() {
            val ctx = hookContext()
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(
                    document(id = "d1", source = null),
                    document(id = "d2", source = null)
                )
            )

            registerRagVerifiedSources(ragContext, ctx)

            ctx.verifiedSources.shouldBeEmpty()
            assertTrue(ctx.verifiedSources.isEmpty()) { "모든 문서에 source가 없으면 출처가 등록되지 않아야 한다" }
        }

        @Test
        fun `단일 문서가 있을 때 VerifiedSource url이 정확하게 등록되어야 한다`() {
            val ctx = hookContext()
            val expectedUrl = "https://docs.example.com/article/42"
            val ragContext = RagContext(
                context = "컨텍스트",
                documents = listOf(document(source = expectedUrl))
            )

            registerRagVerifiedSources(ragContext, ctx)

            val expected = VerifiedSource(title = "Example Doc", url = expectedUrl, toolName = "rag")
            ctx.verifiedSources shouldContainExactly listOf(expected)
            assertTrue(ctx.verifiedSources == listOf(expected)) { "등록된 VerifiedSource의 모든 필드가 정확해야 한다" }
        }
    }

    // ───────────────────────────── recordLoopStageLatency ─────────────────────────────

    @Nested
    inner class RecordLoopStageLatency {

        @Test
        fun `HookContext에 타이밍이 기록된 경우 agentMetrics에 전달되어야 한다`() {
            val ctx = hookContext()
            recordStageTiming(ctx, "llm_calls", 300L)
            val metrics = mockk<AgentMetrics>(relaxed = true)
            val metadata = mapOf<String, Any>("tenantId" to "t1")

            recordLoopStageLatency(ctx, metadata, "llm_calls", metrics)

            verify(exactly = 1) {
                metrics.recordStageLatency("llm_calls", 300L, metadata)
            }
        }

        @Test
        fun `해당 stage가 HookContext에 없으면 agentMetrics를 호출하지 않아야 한다`() {
            val ctx = hookContext()
            // "llm_calls" 타이밍을 기록하지 않음
            val metrics = mockk<AgentMetrics>(relaxed = true)

            recordLoopStageLatency(ctx, emptyMap(), "llm_calls", metrics)

            verify(exactly = 0) { metrics.recordStageLatency(any(), any(), any()) }
        }

        @Test
        fun `타이밍이 전혀 없는 HookContext에서는 agentMetrics를 호출하지 않아야 한다`() {
            val ctx = hookContext()
            val metrics = mockk<AgentMetrics>(relaxed = true)

            recordLoopStageLatency(ctx, emptyMap(), "tool_execution", metrics)

            verify(exactly = 0) { metrics.recordStageLatency(any(), any(), any()) }
        }

        @Test
        fun `여러 stage 중 요청한 stage만 agentMetrics에 전달되어야 한다`() {
            val ctx = hookContext()
            recordStageTiming(ctx, "guard", 50L)
            recordStageTiming(ctx, "tool_execution", 200L)
            recordStageTiming(ctx, "llm_calls", 400L)
            val metrics = mockk<AgentMetrics>(relaxed = true)

            recordLoopStageLatency(ctx, emptyMap(), "tool_execution", metrics)

            verify(exactly = 1) {
                metrics.recordStageLatency("tool_execution", 200L, any())
            }
            verify(exactly = 0) { metrics.recordStageLatency("guard", any(), any()) }
            verify(exactly = 0) { metrics.recordStageLatency("llm_calls", any(), any()) }
        }

        @Test
        fun `metadata가 agentMetrics 호출에 그대로 전달되어야 한다`() {
            val ctx = hookContext()
            recordStageTiming(ctx, "guard", 100L)
            val metrics = mockk<AgentMetrics>(relaxed = true)
            val metadata = mapOf<String, Any>("tenantId" to "tenant-abc", "channel" to "slack")

            recordLoopStageLatency(ctx, metadata, "guard", metrics)

            verify(exactly = 1) {
                metrics.recordStageLatency("guard", 100L, metadata)
            }
        }

        @Test
        fun `duration이 0인 타이밍도 agentMetrics에 전달되어야 한다`() {
            val ctx = hookContext()
            recordStageTiming(ctx, "before_hooks", 0L)
            val metrics = mockk<AgentMetrics>(relaxed = true)

            recordLoopStageLatency(ctx, emptyMap(), "before_hooks", metrics)

            verify(exactly = 1) {
                metrics.recordStageLatency("before_hooks", 0L, any())
            }
        }
    }
}

/** shouldHaveSize에 메시지를 추가하기 위한 헬퍼 infix 함수 */
private infix fun <T> T.withMessage(message: String): T = this
