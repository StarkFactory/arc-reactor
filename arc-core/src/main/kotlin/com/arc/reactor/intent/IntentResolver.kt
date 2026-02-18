package com.arc.reactor.intent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Intent Resolver
 *
 * Orchestrates intent classification and applies the matched profile to an [AgentCommand].
 * This is the main integration point between the intent system and the agent executor.
 *
 * ## Design Principle
 * - Classification failure = default pipeline (intent system never blocks requests)
 * - Low confidence = default pipeline (only high-confidence matches apply profiles)
 * - Profile fields are merged: null = keep original, non-null = override
 *
 * @param classifier Intent classifier (rule-based, LLM, or composite)
 * @param registry Intent registry for looking up definitions and profiles
 * @param confidenceThreshold Minimum confidence to apply a profile (default 0.6)
 */
class IntentResolver(
    private val classifier: IntentClassifier,
    private val registry: IntentRegistry,
    private val confidenceThreshold: Double = 0.6
) {

    /**
     * Classify user input and resolve the appropriate profile.
     *
     * @param text User input text
     * @param context Classification context (conversation history, user info)
     * @return Resolved intent with profile, or null if no confident match
     */
    suspend fun resolve(text: String, context: ClassificationContext = ClassificationContext.EMPTY): ResolvedIntent? {
        try {
            val result = classifier.classify(text, context)

            if (result.isUnknown) {
                logger.debug { "IntentResolver: no intent matched, using default pipeline" }
                return null
            }

            val primary = result.primary ?: run {
                logger.warn { "IntentResolver: non-unknown result without primary intent, using default pipeline" }
                return null
            }
            if (primary.confidence < confidenceThreshold) {
                logger.debug {
                    "IntentResolver: confidence too low " +
                        "(intent=${primary.intentName}, confidence=${primary.confidence}, threshold=$confidenceThreshold)"
                }
                return null
            }

            val definition = registry.get(primary.intentName)
            if (definition == null) {
                logger.warn { "IntentResolver: classified intent '${primary.intentName}' not found in registry" }
                return null
            }

            val mergedProfile = mergeProfiles(definition.profile, result)

            logger.info {
                "IntentResolver: resolved intent=${primary.intentName} " +
                    "confidence=${primary.confidence} classifiedBy=${result.classifiedBy} " +
                    "model=${mergedProfile.model ?: "default"} tokenCost=${result.tokenCost}"
            }

            return ResolvedIntent(
                intentName = primary.intentName,
                profile = mergedProfile,
                result = result
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "IntentResolver: resolution failed, using default pipeline" }
            return null
        }
    }

    /**
     * Apply a resolved intent profile to an [AgentCommand].
     *
     * Only non-null profile fields override the command. The original command values
     * are preserved for any null profile field.
     */
    fun applyProfile(command: AgentCommand, resolved: ResolvedIntent): AgentCommand {
        val profile = resolved.profile
        val primaryConfidence = requireNotNull(resolved.result.primary) {
            "ResolvedIntent.result.primary must not be null when applying an intent profile"
        }.confidence
        val intentMetadata = mutableMapOf<String, Any>(
            METADATA_INTENT_NAME to resolved.intentName,
            METADATA_INTENT_CONFIDENCE to primaryConfidence,
            METADATA_INTENT_CLASSIFIED_BY to resolved.result.classifiedBy,
            METADATA_INTENT_TOKEN_COST to resolved.result.tokenCost
        )
        profile.allowedTools?.let { tools ->
            // Stored as a stable list for easier logging/serialization.
            intentMetadata[METADATA_INTENT_ALLOWED_TOOLS] = tools.toList().sorted()
        }
        return command.copy(
            systemPrompt = profile.systemPrompt ?: command.systemPrompt,
            model = profile.model ?: command.model,
            temperature = profile.temperature ?: command.temperature,
            maxToolCalls = profile.maxToolCalls ?: command.maxToolCalls,
            responseFormat = profile.responseFormat ?: command.responseFormat,
            metadata = command.metadata + intentMetadata
        )
    }

    /**
     * Merge primary profile with secondary intents' allowed tools.
     *
     * For multi-intent inputs (e.g. "refund and check shipping"),
     * tools from secondary intents are added to the primary's allowedTools.
     */
    private fun mergeProfiles(primaryProfile: IntentProfile, result: IntentResult): IntentProfile {
        if (result.secondary.isEmpty()) return primaryProfile

        val secondaryTools = result.secondary
            .filter { it.confidence >= confidenceThreshold }
            .mapNotNull { registry.get(it.intentName)?.profile?.allowedTools }
            .flatten()
            .toSet()

        if (secondaryTools.isEmpty()) return primaryProfile

        val mergedTools = (primaryProfile.allowedTools ?: emptySet()) + secondaryTools
        return primaryProfile.copy(allowedTools = mergedTools.ifEmpty { null })
    }

    companion object {
        const val METADATA_INTENT_NAME = "intentName"
        const val METADATA_INTENT_CONFIDENCE = "intentConfidence"
        const val METADATA_INTENT_CLASSIFIED_BY = "intentClassifiedBy"
        const val METADATA_INTENT_TOKEN_COST = "intentTokenCost"
        const val METADATA_INTENT_ALLOWED_TOOLS = "intentAllowedTools"
    }
}

/**
 * Resolved intent — classification result + matched profile ready for application.
 */
data class ResolvedIntent(
    val intentName: String,
    val profile: IntentProfile,
    val result: IntentResult
)
