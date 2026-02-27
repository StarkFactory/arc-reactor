package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.toDocument
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

@Tag(name = "RAG Ingestion Candidates", description = "RAG ingestion candidate review APIs (ADMIN only)")
@RestController
@RequestMapping("/api/rag-ingestion/candidates")
@ConditionalOnProperty(
    prefix = "arc.reactor.rag.ingestion", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class RagIngestionCandidateController(
    private val store: RagIngestionCandidateStore,
    private val adminAuditStore: AdminAuditStore,
    private val vectorStoreProvider: ObjectProvider<VectorStore>
) {

    @Operation(summary = "List RAG ingestion candidates (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of RAG ingestion candidates"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun list(
        @RequestParam(required = false) status: RagIngestionCandidateStatus?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) limit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val items = store.list(limit = limit, status = status, channel = channel).map { it.toResponse() }
        return ResponseEntity.ok(items)
    }

    @Operation(summary = "Approve candidate and ingest to VectorStore (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Candidate approved and ingested"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Candidate not found"),
        ApiResponse(responseCode = "409", description = "Candidate already reviewed"),
        ApiResponse(responseCode = "503", description = "VectorStore not configured")
    ])
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @Valid @RequestBody request: ReviewRagIngestionCandidateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val candidate = store.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse(error = "Candidate not found", timestamp = Instant.now().toString())
            )
        if (candidate.status != RagIngestionCandidateStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ErrorResponse(error = "Candidate is already reviewed", timestamp = Instant.now().toString())
            )
        }

        val vectorStore = vectorStoreProvider.ifAvailable
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ErrorResponse(error = "VectorStore is not configured", timestamp = Instant.now().toString())
            )

        val documentId = UUID.randomUUID().toString()
        vectorStore.add(listOf(candidate.toDocument(documentId = documentId)))

        val reviewed = store.updateReview(
            id = id,
            status = RagIngestionCandidateStatus.INGESTED,
            reviewedBy = currentActor(exchange),
            reviewComment = request.comment?.trim(),
            ingestedDocumentId = documentId
        ) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(error = "Candidate not found", timestamp = Instant.now().toString())
        )

        recordAdminAudit(
            store = adminAuditStore,
            category = "rag_ingestion_candidate",
            action = "APPROVE",
            actor = currentActor(exchange),
            resourceType = "rag_ingestion_candidate",
            resourceId = reviewed.id,
            detail = "runId=${reviewed.runId}, documentId=${reviewed.ingestedDocumentId}"
        )

        return ResponseEntity.ok(reviewed.toResponse())
    }

    @Operation(summary = "Reject candidate (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Candidate rejected"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Candidate not found"),
        ApiResponse(responseCode = "409", description = "Candidate already reviewed")
    ])
    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @Valid @RequestBody request: ReviewRagIngestionCandidateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val candidate = store.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse(error = "Candidate not found", timestamp = Instant.now().toString())
            )
        if (candidate.status != RagIngestionCandidateStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ErrorResponse(error = "Candidate is already reviewed", timestamp = Instant.now().toString())
            )
        }

        val reviewed = store.updateReview(
            id = id,
            status = RagIngestionCandidateStatus.REJECTED,
            reviewedBy = currentActor(exchange),
            reviewComment = request.comment?.trim()
        ) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(error = "Candidate not found", timestamp = Instant.now().toString())
        )

        recordAdminAudit(
            store = adminAuditStore,
            category = "rag_ingestion_candidate",
            action = "REJECT",
            actor = currentActor(exchange),
            resourceType = "rag_ingestion_candidate",
            resourceId = reviewed.id,
            detail = "runId=${reviewed.runId}"
        )

        return ResponseEntity.ok(reviewed.toResponse())
    }
}

data class RagIngestionCandidateResponse(
    val id: String,
    val runId: String,
    val userId: String,
    val sessionId: String?,
    val channel: String?,
    val query: String,
    val response: String,
    val status: RagIngestionCandidateStatus,
    val capturedAt: Long,
    val reviewedAt: Long?,
    val reviewedBy: String?,
    val reviewComment: String?,
    val ingestedDocumentId: String?
)

data class ReviewRagIngestionCandidateRequest(
    @field:Size(max = 500, message = "comment must not exceed 500 characters")
    val comment: String? = null
)

private fun RagIngestionCandidate.toResponse(): RagIngestionCandidateResponse = RagIngestionCandidateResponse(
    id = id,
    runId = runId,
    userId = userId,
    sessionId = sessionId,
    channel = channel,
    query = query,
    response = response,
    status = status,
    capturedAt = capturedAt.toEpochMilli(),
    reviewedAt = reviewedAt?.toEpochMilli(),
    reviewedBy = reviewedBy,
    reviewComment = reviewComment,
    ingestedDocumentId = ingestedDocumentId
)
