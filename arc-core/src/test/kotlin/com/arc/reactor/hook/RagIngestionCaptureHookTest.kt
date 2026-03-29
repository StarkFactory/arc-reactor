package com.arc.reactor.hook

import com.arc.reactor.hook.impl.RagIngestionCaptureHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.VectorStore

/**
 * RagIngestionCaptureHook에 대한 테스트.
 *
 * RAG 인제스트 캡처 훅의 동작을 검증합니다.
 */
class RagIngestionCaptureHookTest {

    @Test
    fun `captures pending candidate when review은(는) required이다`() = runBlocking {
        val provider = provider(
            RagIngestionPolicy(
                enabled = true,
                requireReview = true,
                allowedChannels = setOf("slack"),
                minQueryChars = 5,
                minResponseChars = 5,
                blockedPatterns = emptySet()
            )
        )
        val store = InMemoryRagIngestionCandidateStore()
        val hook = RagIngestionCaptureHook(provider, store, vectorStore = null)

        hook.afterAgentComplete(
            context = hookContext(channel = "slack"),
            response = AgentResponse(success = true, response = "response content")
        )

        val items = store.list(limit = 10)
        assertEquals(1, items.size) { "리뷰 필요 시 후보가 1개 저장되어야 한다" }
        assertEquals(RagIngestionCandidateStatus.PENDING, items.first().status) { "상태가 PENDING이어야 한다" }
        assertEquals("slack", items.first().channel) { "채널이 slack이어야 한다" }
    }

    @Test
    fun `candidate when blocked pattern matches를 건너뛴다`() = runBlocking {
        val provider = provider(
            RagIngestionPolicy(
                enabled = true,
                requireReview = true,
                allowedChannels = emptySet(),
                minQueryChars = 1,
                minResponseChars = 1,
                blockedPatterns = setOf("password")
            )
        )
        val store = InMemoryRagIngestionCandidateStore()
        val hook = RagIngestionCaptureHook(provider, store, vectorStore = null)

        hook.afterAgentComplete(
            context = hookContext(prompt = "show password please", channel = "slack"),
            response = AgentResponse(success = true, response = "ok")
        )

        assertTrue(store.list(limit = 10).isEmpty(), "Blocked pattern should prevent candidate from being captured")
    }

    @Test
    fun `auto-ingests when review은(는) disabled and vector store exists이다`() = runBlocking {
        val provider = provider(
            RagIngestionPolicy(
                enabled = true,
                requireReview = false,
                allowedChannels = emptySet(),
                minQueryChars = 1,
                minResponseChars = 1,
                blockedPatterns = emptySet()
            )
        )
        val store = InMemoryRagIngestionCandidateStore()
        val vectorStore = mockk<VectorStore>(relaxed = true)
        val hook = RagIngestionCaptureHook(provider, store, vectorStore = vectorStore)

        hook.afterAgentComplete(
            context = hookContext(prompt = "what is release policy?", channel = "slack"),
            response = AgentResponse(success = true, response = "release policy is ...")
        )

        val saved = store.list(limit = 10).firstOrNull()
            ?: fail("자동 인제스트 시 후보 레코드가 저장되어야 한다")
        assertEquals(RagIngestionCandidateStatus.INGESTED, saved.status) { "자동 인제스트 상태는 INGESTED여야 한다" }
        assertEquals("system:auto", saved.reviewedBy) { "자동 인제스트의 reviewedBy는 system:auto여야 한다" }
        assertNotNull(saved.ingestedDocumentId) { "자동 인제스트 후보는 ingestedDocumentId가 null이 아니어야 한다" }
        verify(exactly = 1) { vectorStore.add(any<List<org.springframework.ai.document.Document>>()) }
    }

    @Test
    fun `by runId를 중복 제거한다`() = runBlocking {
        val provider = provider(
            RagIngestionPolicy(
                enabled = true,
                requireReview = true,
                allowedChannels = emptySet(),
                minQueryChars = 1,
                minResponseChars = 1,
                blockedPatterns = emptySet()
            )
        )
        val store = InMemoryRagIngestionCandidateStore()
        val hook = RagIngestionCaptureHook(provider, store, vectorStore = null)
        val context = hookContext(runId = "run-dedupe", channel = "slack")

        hook.afterAgentComplete(context, AgentResponse(success = true, response = "first"))
        hook.afterAgentComplete(context, AgentResponse(success = true, response = "second"))

        assertEquals(1, store.list(limit = 10).size) { "동일 runId로 두 번 호출해도 후보는 1개만 저장되어야 한다" }
    }

    private fun provider(policy: RagIngestionPolicy): RagIngestionPolicyProvider {
        val props = com.arc.reactor.agent.config.RagIngestionProperties(
            enabled = true,
            dynamic = com.arc.reactor.agent.config.RagIngestionDynamicProperties(enabled = true)
        )
        return RagIngestionPolicyProvider(props, InMemoryRagIngestionPolicyStore(policy))
    }

    private fun hookContext(
        runId: String = "run-1",
        prompt: String = "hello prompt",
        channel: String = "slack"
    ): HookContext = HookContext(
        runId = runId,
        userId = "user-1",
        userPrompt = prompt,
        channel = channel
    )
}
