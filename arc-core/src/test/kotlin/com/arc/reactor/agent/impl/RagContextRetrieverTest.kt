package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * RagContextRetriever에 대한 테스트.
 *
 * RAG 컨텍스트 조회 로직을 검증합니다.
 */
class RagContextRetrieverTest {

    @Test
    fun `rag is disabled일 때 null and does not call pipeline를 반환한다`() = runTest {
        val pipeline = mockk<RagPipeline>()
        val retriever = RagContextRetriever(
            enabled = false,
            topK = 3,
            rerankEnabled = true,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000
        )

        val result = retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))

        assertNull(result, "Disabled retriever should return null when RAG is disabled")
        coVerify(exactly = 0) { pipeline.retrieve(any()) }
    }

    @Test
    fun `context and merges explicit filters with prefixed filters를 반환한다`() = runTest {
        val pipeline = mockk<RagPipeline>()
        val querySlot = slot<RagQuery>()
        coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
            context = "retrieved context",
            documents = listOf(RetrievedDocument(id = "doc-1", content = "content"))
        )
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 7,
            rerankEnabled = false,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000
        )
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "refund policy",
            metadata = mapOf(
                "ragFilters" to mapOf("tenant" to "acme", "channel" to "slack"),
                "rag.filter.channel" to "discord",
                "rag.filter.region" to "us"
            )
        )

        val result = retriever.retrieve(command)

        assertEquals("retrieved context", result?.context, "Should return the RAG context string")
        assertEquals(1, result?.documents?.size, "Should return the retrieved documents")
        assertEquals("refund policy", querySlot.captured.query)
        assertEquals(7, querySlot.captured.topK)
        assertEquals(false, querySlot.captured.rerank)
        assertEquals("acme", querySlot.captured.filters["tenant"])
        assertEquals("slack", querySlot.captured.filters["channel"])
        assertEquals("us", querySlot.captured.filters["region"])
    }

    @Test
    fun `pipeline fails with non-cancellation exception일 때 null를 반환한다`() = runTest {
        val pipeline = mockk<RagPipeline>()
        coEvery { pipeline.retrieve(any()) } throws IllegalStateException("rag failed")
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = true,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000
        )

        val result = retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))

        assertNull(result, "Retriever should return null when pipeline throws a non-cancellation exception")
    }

    @Test
    fun `rethrows은(는) cancellation exception from pipeline`() {
        val pipeline = mockk<RagPipeline>()
        coEvery { pipeline.retrieve(any()) } throws CancellationException("cancelled")
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = true,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))
            }
        }
    }

    @Test
    fun `R327 mandatoryFilterKeys 설정 시 metadata 값이 강제 주입되어야 한다`() = runTest {
        val pipeline = mockk<RagPipeline>()
        val querySlot = slot<RagQuery>()
        coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
            context = "ctx",
            documents = listOf(RetrievedDocument(id = "doc-1", content = "c"))
        )
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = false,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000,
            mandatoryFilterKeys = listOf("tenantId")
        )
        // 호출자가 ragFilters에 엉뚱한 tenantId를 제공해도 metadata 원본 값이 우선해야 한다
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "q",
            metadata = mapOf(
                "tenantId" to "acme-real",
                "ragFilters" to mapOf("tenantId" to "spoofed-evil")
            )
        )

        val result = retriever.retrieve(command)

        assertEquals("ctx", result?.context) { "정상 검색 결과 반환" }
        assertEquals(
            "acme-real",
            querySlot.captured.filters["tenantId"],
            "R327: metadata 원본 값이 spoofed 값을 덮어써야 한다 (cross-tenant leak 차단)"
        )
    }

    @Test
    fun `R327 mandatoryFilterKeys 값이 metadata에 없으면 fail-closed로 null 반환`() = runTest {
        val pipeline = mockk<RagPipeline>()
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = false,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 5000,
            mandatoryFilterKeys = listOf("tenantId")
        )
        // metadata에 tenantId가 아예 없음 → fail-closed
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "q",
            metadata = mapOf("other" to "value")
        )

        val result = retriever.retrieve(command)

        assertNull(result) {
            "R327: 필수 필터 키가 metadata에 없으면 fail-closed로 null을 반환해야 한다"
        }
        coVerify(exactly = 0) { pipeline.retrieve(any()) }
    }

    @Test
    fun `pipeline exceeds retrieval timeout일 때 null를 반환한다`() = runTest {
        val pipeline = mockk<RagPipeline>()
        coEvery { pipeline.retrieve(any()) } coAnswers {
            delay(3000)
            RagContext(
                context = "should not reach here",
                documents = listOf(RetrievedDocument(id = "doc-1", content = "content"))
            )
        }
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = true,
            ragPipeline = pipeline,
            retrievalTimeoutMs = 100
        )

        val result = retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))

        assertNull(result, "Retriever should return null when pipeline exceeds timeout")
    }
}
