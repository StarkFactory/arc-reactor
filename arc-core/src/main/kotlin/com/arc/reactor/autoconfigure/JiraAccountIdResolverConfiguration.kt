package com.arc.reactor.autoconfigure

import com.arc.reactor.identity.JiraAccountIdResolver
import com.arc.reactor.identity.UserIdentityStore
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata

private val logger = KotlinLogging.logger {}

/**
 * Jira accountId 자동 조회기 설정.
 *
 * 환경변수 `ATLASSIAN_USERNAME`, `JIRA_API_TOKEN`, `ATLASSIAN_CLOUD_ID`가
 * 모두 설정되어 있을 때만 빈을 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(JiraAccountIdResolverConfiguration.JiraEnvCondition::class)
class JiraAccountIdResolverConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun jiraAccountIdResolver(
        userIdentityStore: ObjectProvider<UserIdentityStore>
    ): JiraAccountIdResolver {
        val username = System.getenv("ATLASSIAN_USERNAME").orEmpty()
        val jiraToken = System.getenv("JIRA_API_TOKEN").orEmpty()
        val cloudId = System.getenv("ATLASSIAN_CLOUD_ID").orEmpty()
        val useApiGateway = System.getenv("JIRA_USE_API_GATEWAY")
            ?.equals("true", ignoreCase = true) ?: true

        logger.info { "JiraAccountIdResolver 빈 등록: cloudId=$cloudId, useApiGateway=$useApiGateway" }
        return JiraAccountIdResolver(
            username = username,
            jiraToken = jiraToken,
            cloudId = cloudId,
            useApiGateway = useApiGateway,
            userIdentityStore = userIdentityStore.ifAvailable
        )
    }

    /**
     * Jira 연동에 필요한 환경변수가 모두 설정되었는지 확인하는 조건.
     */
    class JiraEnvCondition : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
            val username = System.getenv("ATLASSIAN_USERNAME")
            val token = System.getenv("JIRA_API_TOKEN")
            val cloudId = System.getenv("ATLASSIAN_CLOUD_ID")
            return !username.isNullOrBlank() && !token.isNullOrBlank() && !cloudId.isNullOrBlank()
        }
    }
}
