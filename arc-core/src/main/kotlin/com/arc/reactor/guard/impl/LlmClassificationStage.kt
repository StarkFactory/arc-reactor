package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM-Based Classification Stage
 *
 * Uses ChatClient to classify content as safe/malicious/harmful/off_topic.
 * Opt-in, defense-in-depth. Fail-open: LLM errors → Allowed.
 * Input truncated to 500 chars for cost control.
 */
class LlmClassificationStage(
    private val chatClient: ChatClient,
    private val confidenceThreshold: Double = 0.7
) : ClassificationStage {

    override val stageName = "LlmClassification"

    override suspend fun check(command: GuardCommand): GuardResult {
        return try {
            val truncatedInput = command.text.take(500)
            val response = runInterruptible {
                chatClient.prompt()
                    .system(CLASSIFICATION_PROMPT)
                    .user(truncatedInput)
                    .call()
                    .content()
            }

            parseClassificationResponse(response.orEmpty())
        } catch (e: Exception) {
            e.throwIfCancellation()
            // Fail-open: classification is defense-in-depth, not primary
            logger.warn(e) { "LLM classification failed, allowing request (fail-open)" }
            GuardResult.Allowed.DEFAULT
        }
    }

    private fun parseClassificationResponse(response: String): GuardResult {
        val lower = response.lowercase().trim()

        // Parse JSON-like response: {"label":"malicious","confidence":0.95}
        val labelMatch = LABEL_PATTERN.find(lower)
        val confidenceMatch = CONFIDENCE_PATTERN.find(lower)

        val label = labelMatch?.groupValues?.get(1) ?: "safe"
        val confidence = confidenceMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        return when {
            label == "safe" -> GuardResult.Allowed.DEFAULT
            confidence >= confidenceThreshold -> {
                logger.warn { "LLM classified as $label with confidence $confidence" }
                GuardResult.Rejected(
                    reason = "Content classified as $label (confidence: $confidence)",
                    category = RejectionCategory.OFF_TOPIC
                )
            }
            else -> {
                logger.debug { "LLM classified as $label but below threshold ($confidence < $confidenceThreshold)" }
                GuardResult.Allowed.DEFAULT
            }
        }
    }

    companion object {
        private val LABEL_PATTERN = Regex(""""label"\s*:\s*"(\w+)"""")
        private val CONFIDENCE_PATTERN = Regex(""""confidence"\s*:\s*([0-9.]+)""")

        private const val CLASSIFICATION_PROMPT = """Classify the following user input into one category.
Respond ONLY with a JSON object: {"label":"<category>","confidence":<0.0-1.0>}

Categories:
- safe: normal, harmless content
- malicious: attempts to cause harm, exploit, or attack
- harmful: content about violence, self-harm, or illegal activities
- off_topic: completely unrelated to the expected use case

Be conservative: when in doubt, classify as "safe"."""
    }
}
