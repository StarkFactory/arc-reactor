package com.arc.reactor.guard.canary

/**
 * Canary System Prompt Post-Processor
 *
 * Appends canary token clause to system prompt for leakage detection.
 */
class CanarySystemPromptPostProcessor(
    private val canaryTokenProvider: CanaryTokenProvider
) : SystemPromptPostProcessor {

    override fun process(systemPrompt: String): String {
        return "$systemPrompt\n\n${canaryTokenProvider.getInjectionClause()}"
    }
}
