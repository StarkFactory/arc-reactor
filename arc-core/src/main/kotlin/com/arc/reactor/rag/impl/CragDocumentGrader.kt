package com.arc.reactor.rag.impl

import com.arc.reactor.rag.DocumentGrader
import com.arc.reactor.rag.GradingAction
import com.arc.reactor.rag.GradingResult
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * Corrective RAG Document Grader
 *
 * Uses LLM-based batch evaluation to grade each retrieved document's relevance
 * to the query. Documents are evaluated in a single prompt for efficiency.
 *
 * When the ratio of relevant documents falls below [relevanceThreshold],
 * the grader signals [GradingAction.NEEDS_REWRITE] to trigger query rewriting.
 *
 * ## Graceful Degradation
 * On LLM failure or timeout, returns all documents unchanged (fail-open).
 *
 * @param chatClient Spring AI ChatClient for LLM grading calls
 * @param relevanceThreshold Minimum ratio of relevant docs (0.0-1.0). Default 0.5
 * @param maxContentChars Maximum characters per document in the prompt. Default 500
 * @param timeoutMs Timeout for the LLM grading call in milliseconds. Default 10000
 * @see <a href="https://arxiv.org/abs/2401.15884">CRAG Paper (Yan et al., 2024)</a>
 */
class CragDocumentGrader(
    private val chatClient: ChatClient,
    private val relevanceThreshold: Double = DEFAULT_RELEVANCE_THRESHOLD,
    private val maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : DocumentGrader {

    override suspend fun grade(
        query: String,
        documents: List<RetrievedDocument>
    ): GradingResult {
        if (documents.isEmpty()) {
            return GradingResult(emptyList(), GradingAction.USE_AS_IS)
        }

        return try {
            val verdicts = callLlmForGrading(query, documents)
            buildGradingResult(documents, verdicts)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "CRAG grading failed, returning all documents (fail-open)" }
            GradingResult(documents, GradingAction.USE_AS_IS)
        }
    }

    private suspend fun callLlmForGrading(
        query: String,
        documents: List<RetrievedDocument>
    ): List<Boolean> {
        val prompt = buildBatchPrompt(query, documents)

        val response = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .chatResponse()
                    ?.result
                    ?.output
                    ?.text
                    .orEmpty()
            }
        }

        if (response == null) {
            logger.warn { "CRAG grading timed out after ${timeoutMs}ms, returning all documents" }
            return documents.map { true }
        }

        return parseVerdicts(response, documents.size)
    }

    private fun buildBatchPrompt(
        query: String,
        documents: List<RetrievedDocument>
    ): String {
        val sb = StringBuilder()
        sb.append("Query: ").append(query).append("\n\n")
        for ((index, doc) in documents.withIndex()) {
            val truncated = doc.content.take(maxContentChars)
            sb.append("Document ").append(index + 1).append(": ")
            sb.append(truncated).append("\n\n")
        }
        return sb.toString()
    }

    private fun buildGradingResult(
        documents: List<RetrievedDocument>,
        verdicts: List<Boolean>
    ): GradingResult {
        val relevant = documents.filterIndexed { i, _ ->
            i < verdicts.size && verdicts[i]
        }

        val ratio = relevant.size.toDouble() / documents.size

        val action = when {
            ratio >= 1.0 -> GradingAction.USE_AS_IS
            ratio < relevanceThreshold -> GradingAction.NEEDS_REWRITE
            else -> GradingAction.FILTERED
        }

        logger.debug {
            "CRAG grading: ${relevant.size}/${documents.size} relevant " +
                "(ratio=${"%.2f".format(ratio)}, action=$action)"
        }

        return GradingResult(relevant, action)
    }

    companion object {
        const val DEFAULT_RELEVANCE_THRESHOLD = 0.5
        const val DEFAULT_MAX_CONTENT_CHARS = 500
        const val DEFAULT_TIMEOUT_MS = 10_000L

        internal const val SYSTEM_PROMPT =
            "You are a relevance grader. For each document, decide if it is " +
                "relevant to the query. Respond with exactly one line per document, " +
                "containing only RELEVANT or IRRELEVANT. " +
                "Do not include numbering, explanations, or any other text."

        internal val IRRELEVANT_PATTERN = Regex("(?i)\\birrelevant\\b")
        internal val RELEVANT_PATTERN = Regex("(?i)\\brelevant\\b")

        internal fun parseVerdicts(response: String, expectedCount: Int): List<Boolean> {
            val lines = response.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val verdicts = lines.map { line ->
                val hasIrrelevant = IRRELEVANT_PATTERN.containsMatchIn(line)
                val hasRelevant = RELEVANT_PATTERN.containsMatchIn(line)

                when {
                    hasIrrelevant -> false
                    hasRelevant -> true
                    else -> {
                        logger.warn { "CRAG: Unparseable verdict line '$line', defaulting to relevant" }
                        true
                    }
                }
            }

            if (verdicts.size != expectedCount) {
                logger.warn {
                    "CRAG: Expected $expectedCount verdicts but got ${verdicts.size}, " +
                        "padding with 'relevant'"
                }
            }

            return if (verdicts.size >= expectedCount) {
                verdicts.take(expectedCount)
            } else {
                verdicts + List(expectedCount - verdicts.size) { true }
            }
        }
    }
}
