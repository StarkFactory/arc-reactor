package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.impl.RagIngestionCaptureHook
import com.arc.reactor.hook.impl.WebhookNotificationHook
import com.arc.reactor.hook.impl.WebhookProperties
import com.arc.reactor.hook.impl.WriteToolBlockHook
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ArcReactorHookAndMcpConfiguration {

    /**
     * MCP Manager
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpManager(properties: AgentProperties, mcpServerStore: McpServerStore): McpManager {
        val mcpSecurity = properties.mcp.security
        return DefaultMcpManager(
            connectionTimeoutMs = properties.mcp.connectionTimeoutMs,
            securityConfig = McpSecurityConfig(
                allowedServerNames = mcpSecurity.allowedServerNames,
                maxToolOutputLength = mcpSecurity.maxToolOutputLength
            ),
            store = mcpServerStore,
            reconnectionProperties = properties.mcp.reconnection
        )
    }

    /**
     * MCP Startup Initializer
     *
     * Seeds yml-defined servers to store and auto-connects servers on startup.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["mcpStartupInitializer"])
    fun mcpStartupInitializer(
        properties: AgentProperties,
        mcpManager: McpManager,
        mcpServerStore: McpServerStore
    ): McpStartupInitializer = McpStartupInitializer(properties, mcpManager, mcpServerStore)

    /**
     * Hook Executor
     */
    @Bean
    @ConditionalOnMissingBean
    fun hookExecutor(
        beforeStartHooks: List<BeforeAgentStartHook>,
        beforeToolCallHooks: List<BeforeToolCallHook>,
        afterToolCallHooks: List<AfterToolCallHook>,
        afterCompleteHooks: List<AfterAgentCompleteHook>
    ): HookExecutor = HookExecutor(
        beforeStartHooks = beforeStartHooks,
        beforeToolCallHooks = beforeToolCallHooks,
        afterToolCallHooks = afterToolCallHooks,
        afterCompleteHooks = afterCompleteHooks
    )

    /**
     * Webhook Notification Hook (only when arc.reactor.webhook.enabled=true)
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.webhook", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun webhookNotificationHook(properties: AgentProperties): WebhookNotificationHook {
        val config = properties.webhook
        return WebhookNotificationHook(
            webhookProperties = WebhookProperties(
                enabled = config.enabled,
                url = config.url,
                timeoutMs = config.timeoutMs,
                includeConversation = config.includeConversation
            )
        )
    }

    /**
     * Write Tool Block Hook (only when arc.reactor.tool-policy.enabled=true)
     */
    @Bean
    @ConditionalOnMissingBean(name = ["writeToolBlockHook"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool-policy", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun writeToolBlockHook(toolPolicyProvider: ToolPolicyProvider): WriteToolBlockHook =
        WriteToolBlockHook(toolPolicyProvider)

    /**
     * RAG ingestion capture hook (Q&A -> candidate queue).
     */
    @Bean
    @ConditionalOnMissingBean(name = ["ragIngestionCaptureHook"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.rag.ingestion", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun ragIngestionCaptureHook(
        policyProvider: RagIngestionPolicyProvider,
        candidateStore: RagIngestionCandidateStore,
        vectorStoreProvider: ObjectProvider<VectorStore>
    ): RagIngestionCaptureHook = RagIngestionCaptureHook(
        policyProvider = policyProvider,
        candidateStore = candidateStore,
        vectorStore = vectorStoreProvider.ifAvailable
    )
}
