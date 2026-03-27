package com.arc.reactor.controller

import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.chunking.DocumentChunker
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

/**
 * RagIngestionCandidateController에 대한 테스트.
 *
 * RAG 인제스트 후보 REST API의 동작을 검증합니다.
 */
class RagIngestionCandidateControllerTest {

    private lateinit var store: InMemoryRagIngestionCandidateStore
    private lateinit var adminAuditStore: InMemoryAdminAuditStore
    private lateinit var vectorStore: VectorStore
    private lateinit var vectorStoreProvider: ObjectProvider<VectorStore>
    private lateinit var noVectorStoreProvider: ObjectProvider<VectorStore>
    private lateinit var documentChunkerProvider: ObjectProvider<DocumentChunker>

    @BeforeEach
    fun setup() {
        store = InMemoryRagIngestionCandidateStore()
        adminAuditStore = InMemoryAdminAuditStore()
        vectorStore = mockk(relaxed = true)
        vectorStoreProvider = mockk()
        every { vectorStoreProvider.ifUnique } returns vectorStore
        noVectorStoreProvider = mockk()
        every { noVectorStoreProvider.ifUnique } returns null
        documentChunkerProvider = mockk()
        every { documentChunkerProvider.ifAvailable } returns null
    }

    private fun controller(
        provider: ObjectProvider<VectorStore> = vectorStoreProvider
    ): RagIngestionCandidateController {
        return RagIngestionCandidateController(
            store = store,
            adminAuditStore = adminAuditStore,
            vectorStoreProvider = provider,
            documentChunkerProvider = documentChunkerProvider
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
    fun `목록 returns 403 for non-admin`() {
        val response = controller().list(
            status = null,
            channel = null,
            limit = 100,
            exchange = userExchange()
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
            "비관리자 후보 목록 요청은 403이어야 한다"
        }
    }

    @Test
    fun `ingests document and updates status를 승인한다`() {
        val candidate = store.save(pendingCandidate())
        val response = controller().approve(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(comment = "good"),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode) { "승인 응답이 200이어야 한다" }
        val updated = store.findById(candidate.id)
        assertNotNull(updated) { "승인된 후보가 스토어에서 조회 가능해야 한다" }
        assertEquals(RagIngestionCandidateStatus.INGESTED, updated!!.status) {
            "승인된 후보 상태가 INGESTED여야 한다"
        }
        assertEquals("admin-1", updated.reviewedBy) { "검토자가 admin-1이어야 한다" }
        assertNotNull(updated.ingestedDocumentId) {
            "승인된 후보는 null이 아닌 ingestedDocumentId를 가져야 한다"
        }
        verify(exactly = 1) { vectorStore.add(any<List<org.springframework.ai.document.Document>>()) }
    }

    @Test
    fun `approve returns 503 when vector store은(는) missing이다`() {
        val candidate = store.save(pendingCandidate("c2"))
        val response = controller(noVectorStoreProvider).approve(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode) {
            "벡터 스토어가 없으면 503이어야 한다"
        }
        val same = store.findById(candidate.id)
        assertEquals(RagIngestionCandidateStatus.PENDING, same!!.status) {
            "벡터 스토어 없이 승인 실패 시 상태가 PENDING이어야 한다"
        }
    }

    @Test
    fun `updates status to rejected를 거부한다`() {
        val candidate = store.save(pendingCandidate("c3"))
        val response = controller().reject(
            id = candidate.id,
            request = ReviewRagIngestionCandidateRequest(comment = "not useful"),
            exchange = adminExchange()
        )

        assertEquals(HttpStatus.OK, response.statusCode) { "거부 응답이 200이어야 한다" }
        val updated = store.findById(candidate.id)
        assertEquals(RagIngestionCandidateStatus.REJECTED, updated!!.status) {
            "거부된 후보 상태가 REJECTED여야 한다"
        }
        assertEquals("admin-1", updated.reviewedBy) { "검토자가 admin-1이어야 한다" }
    }
}
