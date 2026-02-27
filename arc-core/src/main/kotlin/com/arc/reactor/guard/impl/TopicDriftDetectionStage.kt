package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.memory.MemoryStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Topic Drift Detection Stage (order=6)
 *
 * Defends against Crescendo attacks — multi-turn progressive jailbreaks
 * where each turn gradually escalates sensitivity.
 *
 * Loads conversation history from MemoryStore using the sessionId from
 * command metadata, then scores escalating sensitivity patterns across
 * a sliding window (last 5 turns).
 *
 * Patterns: hypothetical/theoretical → what if/suppose → for research → step by step → bypass/override
 */
class TopicDriftDetectionStage(
    private val memoryStore: MemoryStore? = null,
    private val maxDriftScore: Double = 0.7,
    private val windowSize: Int = 5
) : GuardStage {

    override val stageName = "TopicDriftDetection"
    override val order = 6

    override suspend fun check(command: GuardCommand): GuardResult {
        val history = loadConversationHistory(command)
        if (history.isEmpty()) return GuardResult.Allowed.DEFAULT

        // Take last N turns + current message
        val window = (history.takeLast(windowSize - 1) + command.text)
            .map { it.lowercase() }

        val score = calculateDriftScore(window)

        return if (score >= maxDriftScore) {
            logger.warn { "Topic drift detected: score=$score threshold=$maxDriftScore" }
            GuardResult.Rejected(
                reason = "Conversation pattern indicates potential jailbreak attempt",
                category = RejectionCategory.PROMPT_INJECTION
            )
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }

    private fun loadConversationHistory(command: GuardCommand): List<String> {
        // Priority 1: Explicit conversationHistory in metadata (for testing or custom wiring)
        @Suppress("UNCHECKED_CAST")
        val explicit = command.metadata["conversationHistory"] as? List<String>
        if (!explicit.isNullOrEmpty()) return explicit

        // Priority 2: Load from MemoryStore using sessionId
        val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()
        return memory.getHistory()
            .filter { it.role.name == "USER" }
            .map { it.content }
    }

    private fun calculateDriftScore(window: List<String>): Double {
        if (window.size < 2) return 0.0

        var totalScore = 0.0
        for ((index, turn) in window.withIndex()) {
            val turnScore = ESCALATION_PATTERNS.entries.sumOf { (pattern, weight) ->
                if (pattern.containsMatchIn(turn)) weight else 0.0
            }
            // Later turns contribute more (escalation weighting)
            totalScore += turnScore * (1.0 + index * 0.2)
        }

        // Normalize by window size
        return (totalScore / window.size).coerceAtMost(1.0)
    }

    companion object {
        private val ESCALATION_PATTERNS: Map<Regex, Double> = mapOf(
            // Level 1: Hypothetical framing (low weight)
            Regex("(?i)(hypothetical|theoretical|imagine|thought experiment)") to 0.1,

            // Level 2: Conditional/speculative (medium-low weight)
            Regex("(?i)(what if|suppose|let's say|assume)") to 0.15,

            // Level 3: Academic/research framing (medium weight)
            Regex("(?i)(for research|academic purposes|educational|study)") to 0.2,

            // Level 4: Procedural requests (medium-high weight)
            Regex("(?i)(step by step|detailed instructions|how exactly|specific steps)") to 0.25,

            // Level 5: Direct override/bypass (high weight)
            Regex("(?i)(bypass|override|ignore.*rules|disable.*safety|remove.*restrictions)") to 0.4
        )
    }
}
