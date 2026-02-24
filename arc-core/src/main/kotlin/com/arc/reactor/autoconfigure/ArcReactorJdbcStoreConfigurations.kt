package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.JdbcPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.JdbcAdminAuditStore
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.feedback.JdbcFeedbackStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.mcp.JdbcMcpServerStore
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * JDBC Memory Store Configuration (only loaded when JDBC is on classpath)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcMemoryStoreConfiguration {

    @Bean
    @Primary
    fun jdbcMemoryStore(
        jdbcTemplate: JdbcTemplate,
        tokenEstimator: TokenEstimator
    ): MemoryStore = JdbcMemoryStore(jdbcTemplate = jdbcTemplate, tokenEstimator = tokenEstimator)

    @Bean
    @Primary
    fun jdbcPersonaStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): PersonaStore = JdbcPersonaStore(jdbcTemplate = jdbcTemplate, transactionTemplate = transactionTemplate)

    @Bean
    @Primary
    fun jdbcPromptTemplateStore(
        jdbcTemplate: JdbcTemplate
    ): PromptTemplateStore = JdbcPromptTemplateStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    fun jdbcMcpServerStore(
        jdbcTemplate: JdbcTemplate
    ): McpServerStore = JdbcMcpServerStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    fun jdbcOutputGuardRuleStore(
        jdbcTemplate: JdbcTemplate
    ): OutputGuardRuleStore = JdbcOutputGuardRuleStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    fun jdbcOutputGuardRuleAuditStore(
        jdbcTemplate: JdbcTemplate
    ): OutputGuardRuleAuditStore = JdbcOutputGuardRuleAuditStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    fun jdbcAdminAuditStore(
        jdbcTemplate: JdbcTemplate
    ): AdminAuditStore = JdbcAdminAuditStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.scheduler", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcScheduledJobStore(
        jdbcTemplate: JdbcTemplate
    ): ScheduledJobStore = JdbcScheduledJobStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
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
    @ConditionalOnProperty(
        prefix = "arc.reactor.feedback", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcFeedbackStore(
        jdbcTemplate: JdbcTemplate
    ): FeedbackStore = JdbcFeedbackStore(jdbcTemplate = jdbcTemplate)

    @Bean
    @Primary
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
 * JDBC Tool Policy Store (dynamic tool policy only).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcToolPolicyStoreConfiguration {

    @Bean
    @Primary
    fun jdbcToolPolicyStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): ToolPolicyStore = JdbcToolPolicyStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )
}

/**
 * JDBC RAG ingestion policy store (dynamic policy only).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcRagIngestionPolicyStoreConfiguration {

    @Bean
    @Primary
    fun jdbcRagIngestionPolicyStore(
        jdbcTemplate: JdbcTemplate,
        transactionTemplate: TransactionTemplate
    ): RagIngestionPolicyStore = JdbcRagIngestionPolicyStore(
        jdbcTemplate = jdbcTemplate,
        transactionTemplate = transactionTemplate
    )
}
