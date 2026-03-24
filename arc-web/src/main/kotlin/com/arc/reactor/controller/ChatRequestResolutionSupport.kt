package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.persona.resolveEffectivePrompt
import com.arc.reactor.prompt.PromptTemplateStore
import mu.KotlinLogging
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

/**
 * 시스템 프롬프트 해석기.
 *
 * 페르소나, 프롬프트 템플릿, 직접 지정 프롬프트, 기본 페르소나, 하드코딩 폴백 순으로
 * 우선순위에 따라 시스템 프롬프트를 해석합니다.
 * 각 조회에서 예외가 발생하면 경고 로그를 남기고 다음 단계로 넘어갑니다.
 *
 * @see PersonaStore
 * @see PromptTemplateStore
 */
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

/**
 * 사용자 ID를 해석한다.
 *
 * JWT 인증 필터가 설정한 userId를 최우선으로 사용한다.
 * JWT 인증이 활성화되지 않은 경우(USER_ID_ATTRIBUTE가 없으면) 요청에 지정된
 * requestUserId를 신뢰할 수 없으므로 "anonymous"로 폴백한다.
 * WHY: JWT 검증 없이 클라이언트가 제공한 userId를 그대로 사용하면
 * userId 스푸핑으로 타 사용자 Rate Limit 소진, 세션 탈취 등이 가능하다.
 */
internal fun resolveUserId(exchange: ServerWebExchange, requestUserId: String?): String {
    // JWT 인증 필터가 설정한 userId가 있으면 최우선 사용
    val jwtUserId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
    if (jwtUserId != null) return jwtUserId

    // JWT 인증이 비활성화된 환경에서 requestUserId 사용 시 스푸핑 경고
    if (!requestUserId.isNullOrBlank()) {
        logger.warn {
            "userId '$requestUserId' provided without JWT verification; " +
                "ignoring to prevent spoofing. Enable JWT auth for user identification."
        }
    }
    return "anonymous"
}
