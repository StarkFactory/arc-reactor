package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * HyDE (Hypothetical Document Embeddings) Query Transformer
 *
 * Uses an LLM to generate a hypothetical document that would answer the query,
 * then uses both the original query and hypothetical document as search queries.
 * This improves retrieval by bridging the vocabulary gap between questions and answers.
 *
 * ## How It Works
 * ```
 * User query: "What is our return policy?"
 *
 * → LLM generates hypothetical answer:
 *   "Our return policy allows customers to return items within 30 days
 *    of purchase for a full refund. Items must be unused and in original packaging."
 *
 * → Search with both:
 *   1. "What is our return policy?" (original)
 *   2. "Our return policy allows customers to return items..." (hypothetical)
 * ```
 *
 * ## Why It Works
 * Vector search matches similar embeddings. A question ("What is the policy?")
 * and its answer ("The policy is...") often have different vocabulary but similar meaning.
 * By generating a hypothetical answer, we create a query that's closer in embedding space
 * to the actual documents.
 *
 * @param chatClient Spring AI ChatClient for generating hypothetical documents
 * @param systemPrompt Custom system prompt for the LLM (optional)
 * @see <a href="https://arxiv.org/abs/2212.10496">HyDE Paper (Gao et al., 2022)</a>
 */
class HyDEQueryTransformer(
    private val chatClient: ChatClient,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) : QueryTransformer {

    override suspend fun transform(query: String): List<String> {
        return try {
            val hypotheticalDocument = generateHypotheticalDocument(query)
            if (hypotheticalDocument.isNullOrBlank()) {
                logger.warn { "HyDE generation returned empty result, falling back to original query" }
                listOf(query)
            } else {
                listOf(query, hypotheticalDocument)
            }
        } catch (e: Exception) {
            logger.warn(e) { "HyDE generation failed, falling back to original query" }
            listOf(query)
        }
    }

    private fun generateHypotheticalDocument(query: String): String? {
        return chatClient.prompt()
            .system(systemPrompt)
            .user(query)
            .call()
            .chatResponse()
            ?.result
            ?.output
            ?.text
    }

    companion object {
        internal const val DEFAULT_SYSTEM_PROMPT =
            "Write a short passage (2-3 sentences) that would directly answer the following question. " +
                "Write as if you are quoting from an authoritative document. " +
                "Do not include any preamble like 'Here is...' — just write the passage itself."
    }
}
