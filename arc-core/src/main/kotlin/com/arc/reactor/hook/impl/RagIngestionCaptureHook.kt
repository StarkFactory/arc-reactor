package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.toDocument
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import org.springframework.ai.vectorstore.VectorStore
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private val logger = KotlinLogging.logger {}

/**
 * Captures successful user Q&A as ingestion candidates for RAG.
 *
 * - By default, captures as PENDING for admin review.
 * - When `requireReview=false` and VectorStore exists, auto-ingests and records INGESTED.
 */
class RagIngestionCaptureHook(
    private val policyProvider: RagIngestionPolicyProvider,
    private val candidateStore: RagIngestionCandidateStore,
    private val vectorStore: VectorStore? = null
) : AfterAgentCompleteHook {

    override val order: Int = 260

    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            if (!response.success) return
            val answer = response.response?.trim().orEmpty()
            if (answer.isBlank()) return

            val policy = policyProvider.current()
            if (!policy.enabled) return

            val query = context.userPrompt.trim()
            if (query.length < policy.minQueryChars) return
            if (answer.length < policy.minResponseChars) return

            val channel = resolveChannel(context)
            if (!isAllowedChannel(channel, policy.allowedChannels)) return
            if (matchesBlockedPattern(query, answer, policy.blockedPatterns)) return

            val existing = candidateStore.findByRunId(context.runId)
            if (existing != null) return

            val baseCandidate = RagIngestionCandidate(
                runId = context.runId,
                userId = context.userId,
                sessionId = context.metadata["sessionId"]?.toString(),
                channel = channel,
                query = query,
                response = answer,
                status = RagIngestionCandidateStatus.PENDING,
                capturedAt = Instant.now()
            )

            if (policy.requireReview || vectorStore == null) {
                candidateStore.save(baseCandidate)
                return
            }

            val documentId = UUID.randomUUID().toString()
            vectorStore.add(listOf(baseCandidate.toDocument(documentId = documentId)))
            candidateStore.save(
                baseCandidate.copy(
                    status = RagIngestionCandidateStatus.INGESTED,
                    reviewedAt = Instant.now(),
                    reviewedBy = "system:auto",
                    reviewComment = "auto-ingested(requireReview=false)",
                    ingestedDocumentId = documentId
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to capture rag ingestion candidate for runId=${context.runId}" }
        }
    }

    private fun resolveChannel(context: HookContext): String? {
        val fromMetadata = context.metadata["channelId"]?.toString()?.trim()
        if (!fromMetadata.isNullOrBlank()) return fromMetadata.lowercase()
        return context.channel?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
    }

    private fun isAllowedChannel(channel: String?, allowedChannels: Set<String>): Boolean {
        if (allowedChannels.isEmpty()) return true
        if (channel == null) return false
        return allowedChannels.contains(channel.lowercase())
    }

    private fun matchesBlockedPattern(query: String, response: String, blockedPatterns: Set<String>): Boolean {
        if (blockedPatterns.isEmpty()) return false
        for (rawPattern in blockedPatterns) {
            val pattern = compileRegex(rawPattern) ?: continue
            if (pattern.containsMatchIn(query) || pattern.containsMatchIn(response)) {
                return true
            }
        }
        return false
    }

    private fun compileRegex(rawPattern: String): Regex? {
        return try {
            Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE).toRegex()
        } catch (e: PatternSyntaxException) {
            logger.warn { "Ignoring invalid blocked regex pattern: $rawPattern" }
            null
        }
    }
}
