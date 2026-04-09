package com.arc.reactor.identity

import com.arc.reactor.support.throwIfCancellation
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

private val logger = KotlinLogging.logger {}

/**
 * Jira REST API를 사용하여 이메일 주소로 Jira accountId를 조회한다.
 *
 * API Gateway 모드와 직접 접근 모드를 지원하며,
 * 조회 성공 시 [UserIdentityStore]에 자동 저장한다.
 *
 * @param username Atlassian 계정 이메일 (Basic Auth용)
 * @param jiraToken Jira API 토큰
 * @param cloudId Atlassian Cloud ID
 * @param useApiGateway true이면 API Gateway 경로 사용
 * @param userIdentityStore 조회 결과를 저장할 저장소 (선택)
 */
class JiraAccountIdResolver(
    username: String,
    jiraToken: String,
    private val cloudId: String,
    private val useApiGateway: Boolean = true,
    private val userIdentityStore: UserIdentityStore? = null
) {

    private val webClient: WebClient = buildWebClient(username, jiraToken)

    /**
     * 이메일로 Jira accountId를 조회한다.
     *
     * @param email 조회할 이메일 주소
     * @return Jira accountId. 미발견 또는 에러 시 null.
     */
    suspend fun resolveAccountId(email: String): String? {
        return try {
            val baseUrl = if (useApiGateway) {
                "https://api.atlassian.com/ex/jira/$cloudId"
            } else {
                "https://$cloudId.atlassian.net"
            }
            val users = webClient.get()
                .uri("$baseUrl/rest/api/3/user/search?query={email}", email)
                .retrieve()
                .awaitBody<List<JiraUserResponse>>()

            val accountId = users.firstOrNull()?.accountId
            if (accountId != null) {
                logger.debug { "Jira accountId 조회 성공: email=$email, accountId=$accountId" }
            } else {
                logger.debug { "Jira accountId 미발견: email=$email" }
            }
            accountId
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Jira accountId 조회 실패: email=$email" }
            null
        }
    }

    /**
     * 이메일로 Jira accountId를 조회하고, 성공 시 DB에 업데이트한다.
     *
     * @param email 조회할 이메일 주소
     * @param slackUserId DB 업데이트 시 사용할 Slack User ID
     * @return Jira accountId. 미발견 또는 에러 시 null.
     */
    suspend fun resolveAndStore(email: String, slackUserId: String): String? {
        val accountId = resolveAccountId(email) ?: return null
        updateIdentityStore(slackUserId, email, accountId)
        return accountId
    }

    /** DB에 jiraAccountId를 업데이트한다. */
    private fun updateIdentityStore(
        slackUserId: String,
        email: String,
        jiraAccountId: String
    ) {
        val store = userIdentityStore ?: return
        try {
            val existing = store.findBySlackUserId(slackUserId)
            val updated = existing?.copy(jiraAccountId = jiraAccountId)
                ?: UserIdentity(
                    slackUserId = slackUserId,
                    email = email,
                    jiraAccountId = jiraAccountId
                )
            store.save(updated)
            logger.debug { "Jira accountId DB 저장 완료: slackUserId=$slackUserId, accountId=$jiraAccountId" }
        } catch (e: Exception) {
            logger.warn(e) { "Jira accountId DB 저장 실패: slackUserId=$slackUserId" }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JiraUserResponse(
        @JsonProperty("accountId")
        val accountId: String? = null,
        @JsonProperty("emailAddress")
        val emailAddress: String? = null,
        @JsonProperty("displayName")
        val displayName: String? = null
    )

    companion object {
        /** Basic Auth 헤더를 구성한 WebClient를 생성한다. */
        private fun buildWebClient(username: String, jiraToken: String): WebClient {
            val credentials = java.util.Base64.getEncoder()
                .encodeToString("$username:$jiraToken".toByteArray())
            return WebClient.builder()
                .defaultHeader("Authorization", "Basic $credentials")
                .defaultHeader("Accept", "application/json")
                .build()
        }
    }
}
