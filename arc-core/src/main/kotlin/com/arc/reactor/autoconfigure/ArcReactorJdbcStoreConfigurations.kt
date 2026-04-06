package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.JdbcPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.JdbcAdminAuditStore
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.feedback.JdbcFeedbackStore
import com.arc.reactor.multibot.JdbcSlackBotInstanceStore
import com.arc.reactor.multibot.SlackBotInstanceStore
import com.arc.reactor.settings.RuntimeSettingsService
import com.arc.reactor.promptlab.ExperimentStore
import com.arc.reactor.promptlab.JdbcExperimentStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.mcp.JdbcMcpServerStore
import com.arc.reactor.mcp.JdbcMcpSecurityPolicyStore
import com.arc.reactor.mcp.McpSecurityPolicyStore
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.memory.JdbcMemoryStore
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.persona.JdbcPersonaStore
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.policy.tool.JdbcToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicyStore
import com.arc.reactor.prompt.JdbcPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.rag.ingestion.JdbcRagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.JdbcRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import com.arc.reactor.scheduler.JdbcScheduledJobStore
import com.arc.reactor.scheduler.ScheduledJobStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * JDBC 메모리 저장소 설정 (JDBC가 클래스패스에 있을 때만 로드)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcMemoryStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcMemoryStore"])
    fun jdbcMemoryStore(
        jdbcTemplate: JdbcTemplate,
        tokenEstimator: TokenEstimator
    ): MemoryStore = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, tokenEstimator = tokenEstimator)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcPersonaStore"])
    fun jdbcPersonaStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): PersonaStore = JdbcPersonaStore(jdbcTemplate = jdbcTemplate, transactionTemplate = transactionTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcPromptTemplateStore"])
    fun jdbcPromptTemplateStore(
        jdbcTemplate: JdbcTemplate
    ): PromptTemplateStore = JdbcPromptTemplateStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcMcpServerStore"])
    fun jdbcMcpServerStore(
        jdbcTemplate: JdbcTemplate
    ): McpServerStore = JdbcMcpServerStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcOutputGuardRuleStore"])
    fun jdbcOutputGuardRuleStore(
        jdbcTemplate: JdbcTemplate
    ): OutputGuardRuleStore = JdbcOutputGuardRuleStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcOutputGuardRuleAuditStore"])
    fun jdbcOutputGuardRuleAuditStore(
        jdbcTemplate: JdbcTemplate
    ): OutputGuardRuleAuditStore = JdbcOutputGuardRuleAuditStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcAdminAuditStore"])
    fun jdbcAdminAuditStore(
        jdbcTemplate: JdbcTemplate
    ): AdminAuditStore = JdbcAdminAuditStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcScheduledJobStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.scheduler", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcScheduledJobStore(
        jdbcTemplate: JdbcTemplate
    ): ScheduledJobStore = JdbcScheduledJobStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcPendingApprovalStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcPendingApprovalStore(
        jdbcTemplate: JdbcTemplate,
        properties: AgentProperties
    ): PendingApprovalStore = JdbcPendingApprovalStore(
        jdbcTemplate = jdbcTemplate,
        defaultTimeoutMs = properties.approval.timeoutMs,
        resolvedRetentionMs = properties.approval.resolvedRetentionMs
    )

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcFeedbackStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.feedback", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcFeedbackStore(
        jdbcTemplate: JdbcTemplate
    ): FeedbackStore = JdbcFeedbackStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcExperimentStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.prompt-lab", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcExperimentStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): ExperimentStore = JdbcExperimentStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcRagIngestionCandidateStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.ingestion", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcRagIngestionCandidateStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): RagIngestionCandidateStore = JdbcRagIngestionCandidateStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )
}

/**
 * JDBC 도구 정책 저장소 (동적 도구 정책에만 사용).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcToolPolicyStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcToolPolicyStore"])
    fun jdbcToolPolicyStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): ToolPolicyStore = JdbcToolPolicyStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )
}

/**
 * JDBC MCP 보안 정책 저장소 (동적 MCP 허용 목록에만 사용).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcMcpSecurityPolicyStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcMcpSecurityPolicyStore"])
    fun jdbcMcpSecurityPolicyStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): McpSecurityPolicyStore = JdbcMcpSecurityPolicyStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )
}

/**
 * JDBC RAG 수집 정책 저장소 (동적 정책에만 사용).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcRagIngestionPolicyStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcRagIngestionPolicyStore"])
    fun jdbcRagIngestionPolicyStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): RagIngestionPolicyStore = JdbcRagIngestionPolicyStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcSlackBotInstanceStore"])
    fun jdbcSlackBotInstanceStore(
        jdbcTemplate: JdbcTemplate
    ): SlackBotInstanceStore = JdbcSlackBotInstanceStore(jdbcTemplate)

    @Bean
    @ConditionalOnMissingBean
    fun runtimeSettingsService(
        jdbcTemplate: JdbcTemplate
    ): RuntimeSettingsService = RuntimeSettingsService(jdbcTemplate)
}
