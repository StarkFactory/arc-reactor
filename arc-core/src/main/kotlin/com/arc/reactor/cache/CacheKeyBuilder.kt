package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand
import java.security.MessageDigest

/**
 * 에이전트 커맨드와 도구 이름으로부터 결정적(deterministic) 캐시 키를 빌드한다.
 *
 * SHA-256 해시를 사용한다: systemPrompt + userPrompt + 정렬된 도구 이름.
 *
 * ## 왜 SHA-256인가?
 * - 충돌 확률이 사실상 0에 가까움
 * - 입력 크기에 관계없이 고정 길이(64자 hex) 출력
 * - 캐시 키로 사용하기에 적합한 성능
 */
object CacheKeyBuilder {
    private const val SESSION_ID_KEY = "sessionId"
    private const val TENANT_ID_KEY = "tenantId"
    /** 요청자 신원을 식별하기 위한 메타데이터 키 목록 (우선순위순) */
    private val IDENTITY_METADATA_KEYS = listOf(
        "requesterAccountId",
        "requesterEmail",
        "userEmail",
        "slackUserEmail"
    )

    /**
     * 커맨드와 도구 목록으로부터 정확한(exact) 캐시 키를 빌드한다.
     * 스코프 핑거프린트와 사용자 프롬프트를 합쳐서 SHA-256 해시한다.
     */
    fun buildKey(command: AgentCommand, toolNames: List<String>): String {
        val scopeFingerprint = buildScopeFingerprint(command, toolNames)
        val raw = listOf(scopeFingerprint, command.userPrompt).joinToString("|")
        return sha256(raw)
    }

    /**
     * 의미적(semantic) 캐시에서 사용하는 안정적인 스코프 핑거프린트를 빌드한다.
     *
     * 사용자 프롬프트가 의미적으로 유사하더라도 답변을 변경할 수 있는 모든 필드를 포함한다:
     * - 페르소나/시스템 프롬프트, 모델, 구조화된 출력 계약, 도구 세트
     * - 신원(identity)/테넌트/세션 경계
     *
     * 이를 통해 cross-tenant/cross-user/cross-session 캐시 누출을 방지한다.
     */
    fun buildScopeFingerprint(command: AgentCommand, toolNames: List<String>): String {
        val parts = buildList {
            add(command.systemPrompt.orEmpty())
            add(toolNames.sorted().joinToString(","))
            add(command.model.orEmpty())
            add(command.mode.name)
            add(command.responseFormat.name)
            add(command.responseSchema.orEmpty())
            add(command.userId.orEmpty())
            add(command.metadata[SESSION_ID_KEY]?.toString().orEmpty())
            add(command.metadata[TENANT_ID_KEY]?.toString().orEmpty())
            add(resolveIdentityScope(command))
        }
        return sha256(parts.joinToString("|"))
    }

    /**
     * 메타데이터에서 요청자 신원을 해소한다.
     * 우선순위 순서로 첫 번째 유효한 값을 사용한다.
     */
    private fun resolveIdentityScope(command: AgentCommand): String {
        return IDENTITY_METADATA_KEYS.asSequence()
            .mapNotNull { key -> command.metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.lowercase()
            .orEmpty()
    }

    /** SHA-256 해시를 계산하여 16진수 문자열로 반환한다. 룩업 테이블로 String.format 오버헤드 제거. */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return buildString(hash.size * 2) {
            for (b in hash) {
                append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                append(HEX_CHARS[b.toInt() and 0x0F])
            }
        }
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
