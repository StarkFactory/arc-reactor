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
import com.arc.reactor.mcp.McpHealthPinger
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpSecurityConfig
import com.arc.reactor.mcp.McpSecurityPolicyProvider
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.mcp.McpToolAvailabilityChecker
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.tool.ToolSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun mcpManager(
        properties: AgentProperties,
        mcpServerStore: McpServerStore,
        mcpSecurityPolicyProvider: McpSecurityPolicyProvider
    ): McpManager {
        return DefaultMcpManager(
            connectionTimeoutMs = properties.mcp.connectionTimeoutMs,
            securityConfig = McpSecurityConfig(
                allowedServerNames = properties.mcp.security.allowedServerNames,
                maxToolOutputLength = properties.mcp.security.maxToolOutputLength,
                allowedStdioCommands = properties.mcp.security.allowedStdioCommands
            ),
            securityConfigProvider = { mcpSecurityPolicyProvider.currentConfig() },
            store = mcpServerStore,
            reconnectionProperties = properties.mcp.reconnection,
            allowPrivateAddresses = properties.mcp.allowPrivateAddresses
        )
    }

    /**
     * MCP Startup Initializer
     *
     * 저장소에서 영속된 서버를 복원하고 시작 시 자동 연결한다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["mcpStartupInitializer"])
    fun mcpStartupInitializer(
        mcpManager: McpManager,
        toolSelector: ToolSelector
    ): McpStartupInitializer = McpStartupInitializer(mcpManager, toolSelector)

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
    fun writeToolBlockHook(toolExecutionPolicyEngine: ToolExecutionPolicyEngine): WriteToolBlockHook =
        WriteToolBlockHook(toolExecutionPolicyEngine)

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
        vectorStoreProvider: ObjectProvider<VectorStore>,
        documentChunkerProvider: ObjectProvider<DocumentChunker>
    ): RagIngestionCaptureHook = RagIngestionCaptureHook(
        policyProvider = policyProvider,
        candidateStore = candidateStore,
        vectorStore = vectorStoreProvider.ifAvailable,
        documentChunker = documentChunkerProvider.ifAvailable
    )

    /**
     * MCP Health Pinger (only when arc.reactor.mcp.health.enabled=true)
     *
     * CONNECTED 상태의 MCP 서버를 주기적으로 점검하여
     * 조용히 끊어진 연결을 사전에 감지한다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.mcp.health", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun mcpHealthPinger(
        mcpManager: McpManager,
        properties: AgentProperties
    ): McpHealthPinger {
        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = properties.mcp.health,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
        pinger.start()
        return pinger
    }

    /**
     * MCP Tool Availability Checker
     *
     * ReAct 루프 진입 전 도구 가용성을 사전검사한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpToolAvailabilityChecker(
        mcpManager: McpManager
    ): McpToolAvailabilityChecker = McpToolAvailabilityChecker(mcpManager)
}
