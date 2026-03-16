package com.arc.reactor.slack.tools.config

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/** 도구 노출 후보. 필수 스코프 요구사항과 도구 객체를 함께 보관한다. */
data class ToolCandidate(
    val name: String,
    val requiredScopes: Set<String>,
    val requiredAnyScopes: Set<String> = emptySet(),
    val toolObject: Any
)

/** Slack 봇 토큰에 부여된 OAuth 스코프를 조회하는 프로바이더 인터페이스. */
interface SlackScopeProvider {
    fun resolveGrantedScopes(): Set<String>
}

/** auth.test API 응답 헤더에서 OAuth 스코프를 추출하는 프로바이더. */
class SlackAuthTestScopeProvider(
    private val methodsClient: MethodsClient
) : SlackScopeProvider {

    override fun resolveGrantedScopes(): Set<String> {
        val response = methodsClient.authTest(AuthTestRequest.builder().build())
        if (!response.isOk) {
            throw IllegalStateException("Slack auth.test failed: ${response.error ?: "unknown_error"}")
        }

        val rawScopes = response.httpResponseHeaders
            ?.entries
            ?.firstOrNull { (key, _) -> key.equals("x-oauth-scopes", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (rawScopes.isBlank()) {
            return emptySet()
        }

        return rawScopes.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}

/**
 * 부여된 Slack OAuth 스코프에 따라 도구 노출 여부를 결정하는 리졸버.
 *
 * scope-aware가 비활성화되면 모든 도구를 노출하고,
 * 활성화 시 각 도구의 필수 스코프를 확인하여 필터링한다.
 * 스코프 조회 실패 시 fail-open/fail-close 정책을 따른다.
 *
 * @see SlackScopeAwareLocalToolFilter
 */
class ToolExposureResolver(
    private val properties: SlackToolsProperties,
    private val slackScopeProvider: SlackScopeProvider
) {

    fun resolveToolObjects(candidates: List<ToolCandidate>): List<Any> {
        if (!properties.toolExposure.scopeAwareEnabled) {
            return candidates.map { it.toolObject }
        }

        val scopeResult = runCatching { slackScopeProvider.resolveGrantedScopes() }
        if (scopeResult.isFailure) {
            val ex = scopeResult.exceptionOrNull()
            if (properties.toolExposure.failOpenOnScopeResolutionError) {
                logger.warn(ex) {
                    "Scope-aware tool exposure failed to resolve scopes; fail-open enabled, exposing all tools."
                }
                return candidates.map { it.toolObject }
            }
            logger.error(ex) {
                "Scope-aware tool exposure failed to resolve scopes; fail-open disabled, exposing no tools."
            }
            return emptyList()
        }

        val grantedScopes = scopeResult.getOrNull().orEmpty()
        if (grantedScopes.isEmpty()) {
            if (properties.toolExposure.failOpenOnScopeResolutionError) {
                logger.warn {
                    "Scope-aware tool exposure resolved empty scope set; fail-open enabled, exposing all tools."
                }
                return candidates.map { it.toolObject }
            }
            logger.warn {
                "Scope-aware tool exposure resolved empty scope set; fail-open disabled, exposing no tools."
            }
            return emptyList()
        }

        val exposed = candidates.filter { candidate ->
            val requiredAllSatisfied = candidate.requiredScopes.all { it in grantedScopes }
            val requiredAnySatisfied = candidate.requiredAnyScopes.isEmpty() ||
                candidate.requiredAnyScopes.any { it in grantedScopes }
            requiredAllSatisfied && requiredAnySatisfied
        }
        val blocked = candidates.map { it.name }.toSet() - exposed.map { it.name }.toSet()
        if (blocked.isNotEmpty()) {
            logger.info {
                "Scope-aware tool exposure filtered tools by granted Slack scopes. " +
                    "exposed=${exposed.size}/${candidates.size}, blocked=${blocked.sorted()}"
            }
        } else {
            logger.info { "Scope-aware tool exposure enabled: all ${candidates.size} tools exposed." }
        }
        return exposed.map { it.toolObject }
    }
}
