package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.AlwaysApprovePolicy
import com.arc.reactor.approval.InMemoryPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.policy.tool.DynamicToolApprovalPolicy
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import mu.KotlinLogging
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}
private val tenantIdPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val LEGACY_AUTH_ENABLED_PROPERTY = "arc.reactor.auth.enabled"

/**
 * 런타임 사전 검증 + 정책 관련 빈 설정.
 *
 * [ArcReactorCoreBeansConfiguration]에서 분리하여 파일 크기 제한(350줄)을 준수한다.
 */
@Configuration
class ArcReactorPreflightConfiguration {

    @Bean
    fun runtimePreflightMarker(
        environment: Environment,
        authProperties: com.arc.reactor.auth.AuthProperties
    ): Any {
        validateLegacyAuthToggle(environment)
        validateDefaultTenantId(authProperties.defaultTenantId)
        validatePostgresRequirement(environment)
        warnAboutHealthProbeAccess(environment)
        return Any()
    }

    private fun validatePostgresRequirement(environment: Environment) {
        val postgresRequired = environment.getProperty("arc.reactor.postgres.required", Boolean::class.java, true)
        if (!postgresRequired) return

        val url = environment.getProperty("spring.datasource.url")?.trim().orEmpty()
        if (url.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL when arc.reactor.postgres.required=true. " +
                    "Set spring.datasource.url (jdbc:postgresql://...) or disable this check only for local " +
                    "non-production runs by setting arc.reactor.postgres.required=false."
            )
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw BeanInitializationException(
                "Arc Reactor requires PostgreSQL JDBC URL. Current spring.datasource.url='$url'"
            )
        }
        val username = environment.getProperty("spring.datasource.username")?.trim().orEmpty()
        if (username.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires spring.datasource.username when arc.reactor.postgres.required=true. " +
                    "Set SPRING_DATASOURCE_USERNAME."
            )
        }
        val password = environment.getProperty("spring.datasource.password")?.trim().orEmpty()
        if (password.isBlank()) {
            throw BeanInitializationException(
                "Arc Reactor requires spring.datasource.password when arc.reactor.postgres.required=true. " +
                    "Set SPRING_DATASOURCE_PASSWORD."
            )
        }
    }

    private fun validateLegacyAuthToggle(environment: Environment) {
        val rawValue = environment.getProperty(LEGACY_AUTH_ENABLED_PROPERTY)?.trim() ?: return
        val enabled = rawValue.toBooleanStrictOrNull()
            ?: throw BeanInitializationException(
                "$LEGACY_AUTH_ENABLED_PROPERTY is no longer used and only accepts true/false if present. " +
                    "Current value='$rawValue'. Remove this property and keep only arc.reactor.auth.jwt-secret."
            )
        if (!enabled) {
            throw BeanInitializationException(
                "$LEGACY_AUTH_ENABLED_PROPERTY=false is no longer supported. " +
                    "Authentication is always required in Arc Reactor runtime. " +
                    "Remove $LEGACY_AUTH_ENABLED_PROPERTY and configure arc.reactor.auth.jwt-secret."
            )
        }
        logger.warn {
            "$LEGACY_AUTH_ENABLED_PROPERTY=true is redundant and has no effect. " +
                "Remove this property and keep arc.reactor.auth.jwt-secret only."
        }
    }

    private fun validateDefaultTenantId(defaultTenantId: String) {
        val normalized = defaultTenantId.trim()
        if (!tenantIdPattern.matches(normalized)) {
            throw BeanInitializationException(
                "Invalid arc.reactor.auth.default-tenant-id='$defaultTenantId'. " +
                    "Use 1-64 chars: letters, numbers, hyphen, underscore."
            )
        }
    }

    private fun warnAboutHealthProbeAccess(environment: Environment) {
        val probesEnabled = environment.getProperty(
            "management.endpoint.health.probes.enabled", Boolean::class.java, true
        )
        val publicHealth = environment.getProperty(
            "arc.reactor.auth.public-actuator-health", Boolean::class.java, true
        )
        if (probesEnabled && !publicHealth) {
            logger.warn {
                "Health probes are enabled but arc.reactor.auth.public-actuator-health=false. " +
                    "Unauthenticated liveness/readiness probes may fail with 401. " +
                    "Set ARC_REACTOR_AUTH_PUBLIC_ACTUATOR_HEALTH=true when using unauthenticated probes."
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyStore(properties: AgentProperties): ToolPolicyStore =
        InMemoryToolPolicyStore(initial = ToolPolicy.fromProperties(properties.toolPolicy))

    @Bean
    @ConditionalOnMissingBean
    fun toolPolicyProvider(properties: AgentProperties, toolPolicyStore: ToolPolicyStore): ToolPolicyProvider =
        ToolPolicyProvider(properties = properties.toolPolicy, store = toolPolicyStore)

    @Bean
    @ConditionalOnMissingBean
    fun ragIngestionPolicyStore(properties: AgentProperties): RagIngestionPolicyStore =
        InMemoryRagIngestionPolicyStore(initial = RagIngestionPolicy.fromProperties(properties.rag.ingestion))

    @Bean
    @ConditionalOnMissingBean
    fun toolExecutionPolicyEngine(toolPolicyProvider: ToolPolicyProvider): ToolExecutionPolicyEngine =
        ToolExecutionPolicyEngine(toolPolicyProvider)

    @Bean
    @ConditionalOnMissingBean
    fun ragIngestionPolicyProvider(
        properties: AgentProperties,
        ragIngestionPolicyStore: RagIngestionPolicyStore
    ): RagIngestionPolicyProvider = RagIngestionPolicyProvider(
        properties = properties.rag.ingestion,
        store = ragIngestionPolicyStore
    )

    /**
     * Tool Approval Policy — only created when HITL is enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun toolApprovalPolicy(
        properties: AgentProperties,
        toolExecutionPolicyEngine: ToolExecutionPolicyEngine
    ): ToolApprovalPolicy {
        val staticToolNames = properties.approval.toolNames
        if (properties.toolPolicy.dynamic.enabled) {
            return DynamicToolApprovalPolicy(
                staticToolNames = staticToolNames,
                toolExecutionPolicyEngine = toolExecutionPolicyEngine
            )
        }
        val toolNames = (staticToolNames + properties.toolPolicy.writeToolNames)
        return if (toolNames.isNotEmpty()) {
            DynamicToolApprovalPolicy(
                staticToolNames = staticToolNames,
                toolExecutionPolicyEngine = toolExecutionPolicyEngine
            )
        } else {
            AlwaysApprovePolicy()
        }
    }

    @Bean
    @ConditionalOnMissingBean(PendingApprovalStore::class)
    @ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() == 0")
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun pendingApprovalStore(properties: AgentProperties): PendingApprovalStore =
        InMemoryPendingApprovalStore(defaultTimeoutMs = properties.approval.timeoutMs)
}
