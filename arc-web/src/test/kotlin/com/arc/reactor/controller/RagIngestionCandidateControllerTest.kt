package com.arc.reactor.controller

import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange

class RagIngestionCandidateControllerTest {

    private lateinit var store: InMemoryRagIngestionCandidateStore
    private lateinit var adminAuditStore: InMemoryAdminAuditStore
    private lateinit var vectorStore: VectorStore
    private lateinit var vectorStoreProvider: ObjectProvider<VectorStore>
    private lateinit var noVectorStoreProvider: ObjectProvider<VectorStore>

    @BeforeEach
    fun setup() {
        store = InMemoryRagIngestionCandidateStore()
        adminAuditStore = InMemoryAdminAuditStore()
        vectorStore = mockk(relaxed = true)
        vectorStoreProvider = mockk()
        every { vectorStoreProvider.ifAvailable } returns vectorStore
        noVectorStoreProvider = mockk()
        every { noVectorStoreProvider.ifAvailable } returns null
    }

    private fun controller(
        provider: ObjectProvider<VectorStore> = vectorStoreProvider
    ): RagIngestionCandidateController {
        return RagIngestionCandidateController(
            store = store,
            adminAuditStore = adminAuditStore,
            vectorStoreProvider = provider
        )
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-1"
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun pendingCandidate(id: String = "c1"): RagIngestionCandidate {
        return RagIngestionCandidate(
            id = id,
            runId = "run-$id",
            userId = "user-1",
            sessionId = "session-1",
            channel = "slack",
            query = "release policy?",
            response = "release policy answer",
            status = RagIngestionCandidateStatus.PENDING
        )
    }

    @Test
    fun `list returns 403 for non-admin`() {
        val response = controller().list(
            status = null,
            channel = null,
            limit = 100,
            exchange = userExchange()
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `approve ingests document and updates status`() {
        val candidate = store.save(pendingCandidate())
        val response = controller().approve(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(comment = "good"),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val updated = store.findById(candidate.id)
        assertNotNull(updated)
        assertEquals(RagIngestionCandidateStatus.INGESTED, updated!!.status)
        assertEquals("admin-1", updated.reviewedBy)
        assertNotNull(updated.ingestedDocumentId)
        verify(exactly = 1) { vectorStore.add(any<List<org.springframework.ai.document.Document>>()) }
    }

    @Test
    fun `approve returns 503 when vector store is missing`() {
        val candidate = store.save(pendingCandidate("c2"))
        val response = controller(noVectorStoreProvider).approve(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        val same = store.findById(candidate.id)
        assertEquals(RagIngestionCandidateStatus.PENDING, same!!.status)
    }

    @Test
    fun `reject updates status to rejected`() {
        val candidate = store.save(pendingCandidate("c3"))
        val response = controller().reject(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(comment = "not useful"),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val updated = store.findById(candidate.id)
        assertEquals(RagIngestionCandidateStatus.REJECTED, updated!!.status)
        assertEquals("admin-1", updated.reviewedBy)
    }
}
