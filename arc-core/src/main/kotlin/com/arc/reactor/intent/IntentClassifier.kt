package com.arc.reactor.intent

import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentResult

/**
 * Intent Classifier Interface
 *
 * Classifies user input into one or more intents.
 * Implementations may use rule-based matching, LLM calls, or a combination.
 *
 * ## Implementation Guidelines
 * - Return [IntentResult.unknown] when no intent matches with sufficient confidence
 * - Always populate [IntentResult.classifiedBy] with the classifier name
 * - Track token cost for LLM-based classifiers
 *
 * ## Example Usage
 * ```kotlin
 * val result = classifier.classify("I want to return my order", context)
 * if (!result.isUnknown) {
 *     val intentName = result.primary!!.intentName  // e.g. "refund"
 * }
 * ```
 *
 * @see com.arc.reactor.intent.impl.RuleBasedIntentClassifier for keyword matching
 * @see com.arc.reactor.intent.impl.LlmIntentClassifier for LLM-based classification
 * @see com.arc.reactor.intent.impl.CompositeIntentClassifier for cascading strategy
 */
interface IntentClassifier {

    /**
     * Classify user input into intents.
     *
     * @param text User input text
     * @param context Additional classification context (conversation history, user info)
     * @return Classification result with primary and optional secondary intents
     */
    suspend fun classify(text: String, context: ClassificationContext = ClassificationContext.EMPTY): IntentResult
}
