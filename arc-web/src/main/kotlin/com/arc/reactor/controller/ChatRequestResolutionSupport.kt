package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.persona.resolveEffectivePrompt
import com.arc.reactor.prompt.PromptTemplateStore
import mu.KotlinLogging
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

internal class SystemPromptResolver(
    private val personaStore: PersonaStore? = null,
    private val promptTemplateStore: PromptTemplateStore? = null
) {

    fun resolve(
        personaId: String?,
        promptTemplateId: String?,
        systemPrompt: String?
    ): String {
        personaId?.let { resolvePersonaPromptSafely(it)?.let { prompt -> return prompt } }
        promptTemplateId?.let { resolveTemplatePromptSafely(it)?.let { prompt -> return prompt } }
        if (!systemPrompt.isNullOrBlank()) return systemPrompt
        resolveDefaultPersonaPromptSafely()?.let { return it }
        return DEFAULT_SYSTEM_PROMPT
    }

    private fun resolvePersonaPromptSafely(personaId: String): String? {
        return try {
            personaStore?.get(personaId)?.let { it.resolveEffectivePrompt(promptTemplateStore) }
        } catch (e: Exception) {
            logger.warn(e) { "Persona lookup failed for personaId='$personaId'; falling back to default prompt" }
            null
        }
    }

    private fun resolveTemplatePromptSafely(promptTemplateId: String): String? {
        return try {
            promptTemplateStore?.getActiveVersion(promptTemplateId)?.content
        } catch (e: Exception) {
            logger.warn(e) { "Prompt template lookup failed for promptTemplateId='$promptTemplateId'" }
            null
        }
    }

    private fun resolveDefaultPersonaPromptSafely(): String? {
        return try {
            personaStore?.getDefault()?.takeIf { it.isActive }?.let { it.resolveEffectivePrompt(promptTemplateStore) }
        } catch (e: Exception) {
            logger.warn(e) { "Default persona lookup failed; using hardcoded fallback system prompt" }
            null
        }
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant powered by Arc Reactor. " +
                "Answer in the same language as the user's message. " +
                "Be concise and direct. " +
                "When you have tools available, use them to provide accurate, grounded answers. " +
                "Do not fabricate citations, references, or sources. " +
                "If you are unsure about something, say so honestly rather than guessing."
    }
}

internal fun resolveUserId(exchange: ServerWebExchange, requestUserId: String?): String {
    return exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
        ?: requestUserId
        ?: "anonymous"
}
