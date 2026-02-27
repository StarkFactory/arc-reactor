package com.arc.reactor.guard.canary

/**
 * System Prompt Post-Processor
 *
 * Modifies the system prompt before it is sent to the LLM.
 * Used to inject canary tokens or other security markers.
 */
fun interface SystemPromptPostProcessor {
    fun process(systemPrompt: String): String
}
