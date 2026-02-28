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
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.VectorStore

class RagIngestionCaptureHookTest {

    @Test
    fun `captures pending candidate when review is required`() = runBlocking {
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
        assertEquals(1, items.size)
        assertEquals(RagIngestionCandidateStatus.PENDING, items.first().status)
        assertEquals("slack", items.first().channel)
    }

    @Test
    fun `skips candidate when blocked pattern matches`() = runBlocking {
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
    fun `auto-ingests when review is disabled and vector store exists`() = runBlocking {
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
        assertNotNull(saved, "Auto-ingestion should produce a candidate record in the store")
        assertEquals(RagIngestionCandidateStatus.INGESTED, saved!!.status)
        assertEquals("system:auto", saved.reviewedBy)
        assertNotNull(saved.ingestedDocumentId, "Auto-ingested candidate should have a non-null ingestedDocumentId")
        verify(exactly = 1) { vectorStore.add(any<List<org.springframework.ai.document.Document>>()) }
    }

    @Test
    fun `deduplicates by runId`() = runBlocking {
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

        assertEquals(1, store.list(limit = 10).size)
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
