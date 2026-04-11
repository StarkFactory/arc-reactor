package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
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
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import io.micrometer.core.instrument.MeterRegistry
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
     * MCP 매니저
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpManager(
        properties: AgentProperties,
        mcpServerStore: McpServerStore,
        mcpSecurityPolicyProvider: McpSecurityPolicyProvider,
        meterRegistryProvider: ObjectProvider<MeterRegistry>
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
            allowPrivateAddresses = properties.mcp.allowPrivateAddresses,
            meterRegistry = meterRegistryProvider.ifAvailable
        )
    }

    /**
     * MCP 시작 초기화기
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
     * 훅 실행기
     *
     * R249: `evaluationMetricsCollectorProvider`를 통해 Hook 실행 실패를
     * `execution.error{stage="hook"}` 메트릭으로 자동 기록한다. 빈 미등록 시 NoOp fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    fun hookExecutor(
        beforeStartHooks: List<BeforeAgentStartHook>,
        beforeToolCallHooks: List<BeforeToolCallHook>,
        afterToolCallHooks: List<AfterToolCallHook>,
        afterCompleteHooks: List<AfterAgentCompleteHook>,
        arcReactorTracerProvider: ObjectProvider<ArcReactorTracer>,
        evaluationMetricsCollectorProvider: ObjectProvider<EvaluationMetricsCollector>
    ): HookExecutor = HookExecutor(
        beforeStartHooks = beforeStartHooks,
        beforeToolCallHooks = beforeToolCallHooks,
        afterToolCallHooks = afterToolCallHooks,
        afterCompleteHooks = afterCompleteHooks,
        tracer = arcReactorTracerProvider.getIfAvailable { NoOpArcReactorTracer() },
        evaluationMetricsCollector = evaluationMetricsCollectorProvider.getIfAvailable {
            NoOpEvaluationMetricsCollector
        }
    )

    /**
     * 웹훅 알림 훅 (arc.reactor.webhook.enabled=true일 때만 활성화)
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
     * 쓰기 도구 차단 훅 (arc.reactor.tool-policy.enabled=true일 때만 활성화)
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
     * RAG 수집 캡처 훅 (Q&A -> 후보 큐).
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
        vectorStore = vectorStoreProvider.ifUnique,
        documentChunker = documentChunkerProvider.ifAvailable
    )

    /**
     * MCP 헬스 핑어 (arc.reactor.mcp.health.enabled=true일 때만 활성화)
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
        // R283 fix: ownsScope=true로 close() 시 SupervisorJob까지 cancel.
        // 이 anonymous scope는 이 bean 외에는 참조되지 않으므로 pinger가 lifecycle을
        // 책임지는 것이 안전하다. 이전에는 close()가 child pingJob만 cancel하여
        // 부모 SupervisorJob이 컨테이너 종료 후에도 leak되었다.
        val pinger = McpHealthPinger(
            mcpManager = mcpManager,
            properties = properties.mcp.health,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ownsScope = true
        )
        pinger.start()
        return pinger
    }

    /**
     * MCP 도구 가용성 검사기
     *
     * ReAct 루프 진입 전 도구 가용성을 사전검사한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun mcpToolAvailabilityChecker(
        mcpManager: McpManager
    ): McpToolAvailabilityChecker = McpToolAvailabilityChecker(mcpManager)
}
