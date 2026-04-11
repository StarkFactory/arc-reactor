package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.approval.InMemoryPendingApprovalStore
import com.arc.reactor.approval.JdbcPendingApprovalStore
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.InMemoryOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.impl.WebhookNotificationHook
import com.arc.reactor.mcp.DefaultMcpManager
import com.arc.reactor.mcp.InMemoryMcpServerStore
import com.arc.reactor.mcp.JdbcMcpServerStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.mcp.McpServerStore
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.InMemoryMemoryStore
import com.arc.reactor.memory.JdbcMemoryStore
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.memory.TokenEstimator
import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.impl.JdbcUserMemoryStore
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.memory.summary.InMemoryConversationSummaryStore
import com.arc.reactor.memory.summary.JdbcConversationSummaryStore
import com.arc.reactor.memory.summary.LlmConversationSummaryService
import com.arc.reactor.persona.InMemoryPersonaStore
import com.arc.reactor.persona.JdbcPersonaStore
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.InMemoryPromptTemplateStore
import com.arc.reactor.prompt.JdbcPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.rag.DocumentReranker
import com.arc.reactor.rag.DocumentRetriever
import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.HyDEQueryTransformer
import com.arc.reactor.rag.impl.PassthroughQueryTransformer
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.auth.InMemoryUserStore
import com.arc.reactor.auth.UserStore
import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import io.mockk.mockk
import org.springframework.ai.vectorstore.VectorStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment

/**
 * ArcReactorAutoConfiguration에 대한 테스트.
 *
 * 자동 설정 빈 등록과 조건부 로딩을 검증합니다.
 */
class ArcReactorAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        }
        .withPropertyValues(
            "arc.reactor.postgres.required=false",
            "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
        )
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    private val jdbcContextRunner = contextRunner
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                JdbcTemplateAutoConfiguration::class.java,
                DataSourceTransactionManagerAutoConfiguration::class.java,
                TransactionAutoConfiguration::class.java
            )
        )
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:autoConfigTest;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver"
        )

    // ── Default Beans ───────────────────────────────────────────────

    @Nested
    inner class DefaultBeans {

        @Test
        fun `register AllToolSelector by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(AllToolSelector::class.java, context.getBean(ToolSelector::class.java),
                    "Default ToolSelector should be AllToolSelector")
            }
        }

        @Test
        fun `register InMemoryMemoryStore by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryMemoryStore::class.java, context.getBean(MemoryStore::class.java),
                    "Default MemoryStore should be InMemoryMemoryStore")
            }
        }

        @Test
        fun `register InMemoryPersonaStore by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryPersonaStore::class.java, context.getBean(PersonaStore::class.java),
                    "Default PersonaStore should be InMemoryPersonaStore")
            }
        }

        @Test
        fun `register InMemoryPromptTemplateStore by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryPromptTemplateStore::class.java, context.getBean(PromptTemplateStore::class.java),
                    "Default PromptTemplateStore should be InMemoryPromptTemplateStore")
            }
        }

        @Test
        fun `register InMemoryMcpServerStore by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryMcpServerStore::class.java, context.getBean(McpServerStore::class.java),
                    "Default McpServerStore should be InMemoryMcpServerStore")
            }
        }

        @Test
        fun `register DefaultErrorMessageResolver by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultErrorMessageResolver::class.java, context.getBean(ErrorMessageResolver::class.java),
                    "Default ErrorMessageResolver should be DefaultErrorMessageResolver")
            }
        }

        @Test
        fun `register NoOpAgentMetrics by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(NoOpAgentMetrics::class.java, context.getBean(AgentMetrics::class.java),
                    "Default AgentMetrics should be NoOpAgentMetrics")
            }
        }

        @Test
        fun `register DefaultTokenEstimator by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultTokenEstimator::class.java, context.getBean(TokenEstimator::class.java),
                    "Default TokenEstimator should be DefaultTokenEstimator")
            }
        }

        @Test
        fun `register DefaultConversationManager by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultConversationManager::class.java, context.getBean(ConversationManager::class.java),
                    "Default ConversationManager should be DefaultConversationManager")
            }
        }

        @Test
        fun `register DefaultMcpManager by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultMcpManager::class.java, context.getBean(McpManager::class.java),
                    "Default McpManager should be DefaultMcpManager")
            }
        }

        @Test
        fun `register HookExecutor by default해야 한다`() {
            contextRunner.run { context ->
                assertNotNull(context.getBean(HookExecutor::class.java)) {
                    "HookExecutor should be registered by default"
                }
            }
        }

        @Test
        fun `register in-memory output guard stores by default해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(
                    InMemoryOutputGuardRuleStore::class.java,
                    context.getBean(OutputGuardRuleStore::class.java),
                    "OutputGuardRuleStore should default to in-memory"
                )
                assertInstanceOf(
                    InMemoryOutputGuardRuleAuditStore::class.java,
                    context.getBean(OutputGuardRuleAuditStore::class.java),
                    "OutputGuardRuleAuditStore should default to in-memory"
                )
            }
        }

        @Test
        fun `approval is enabled without datasource일 때 register in-memory pending approval store해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.approval.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        InMemoryPendingApprovalStore::class.java,
                        context.getBean(PendingApprovalStore::class.java),
                        "PendingApprovalStore should fall back to in-memory when approval is enabled without JDBC"
                    )
                }
        }

        @Test
        fun `approval is enabled with datasource일 때 prefer jdbc pending approval store해야 한다`() {
            jdbcContextRunner
                .withPropertyValues("arc.reactor.approval.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        JdbcPendingApprovalStore::class.java,
                        context.getBean(PendingApprovalStore::class.java),
                        "PendingApprovalStore should use JDBC when datasource is configured"
                    )
                }
        }
    }

    // ── ConditionalOnMissingBean Override ────────────────────────────

    @Nested
    inner class ConditionalOnMissingBeanOverride {

        @Test
        fun `user provides one일 때 use custom ToolSelector해야 한다`() {
            contextRunner
                .withUserConfiguration(CustomToolSelectorConfig::class.java)
                .run { context ->
                    val bean = context.getBean(ToolSelector::class.java)
                    assertFalse(bean is AllToolSelector) {
                        "Custom ToolSelector should replace default AllToolSelector"
                    }
                }
        }

        @Test
        fun `user provides one일 때 use custom MemoryStore해야 한다`() {
            contextRunner
                .withUserConfiguration(CustomMemoryStoreConfig::class.java)
                .run { context ->
                    val bean = context.getBean(MemoryStore::class.java)
                    assertFalse(bean is InMemoryMemoryStore) {
                        "Custom MemoryStore should replace default InMemoryMemoryStore"
                    }
                }
        }

        @Test
        fun `user provides one일 때 use custom AgentMetrics해야 한다`() {
            contextRunner
                .withUserConfiguration(CustomAgentMetricsConfig::class.java)
                .run { context ->
                    val bean = context.getBean(AgentMetrics::class.java)
                    assertFalse(bean is NoOpAgentMetrics) {
                        "Custom AgentMetrics should replace default NoOpAgentMetrics"
                    }
                }
        }
    }

    // ── Guard Configuration ─────────────────────────────────────────

    @Nested
    inner class GuardConfigurationTests {
        private fun inputValidationMaxLength(context: org.springframework.context.ApplicationContext): Int {
            val stage = context.getBean("inputValidationStage", DefaultInputValidationStage::class.java)
            val maxLengthField = DefaultInputValidationStage::class.java.getDeclaredField("maxLength")
            maxLengthField.isAccessible = true
            return maxLengthField.getInt(stage)
        }

        @Test
        fun `register guard beans by default해야 한다`() {
            contextRunner.run { context ->
                assertTrue(context.containsBean("requestGuard")) {
                    "RequestGuard should be registered (guard.enabled defaults to true)"
                }
                assertInstanceOf(GuardPipeline::class.java, context.getBean(RequestGuard::class.java),
                    "Default RequestGuard should be GuardPipeline")
                assertTrue(context.containsBean("rateLimitStage")) {
                    "rateLimitStage should be registered by default"
                }
                assertTrue(context.containsBean("inputValidationStage")) {
                    "inputValidationStage should be registered by default"
                }
                assertTrue(context.containsBean("injectionDetectionStage")) {
                    "injectionDetectionStage should be registered by default"
                }
            }
        }

        @Test
        fun `disabled일 때 not register guard beans해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.guard.enabled=false")
                .run { context ->
                    assertFalse(context.containsBean("requestGuard")) {
                        "RequestGuard should not exist when guard is disabled"
                    }
                    assertFalse(context.containsBean("rateLimitStage")) {
                        "rateLimitStage should not exist when guard is disabled"
                    }
                    assertFalse(context.containsBean("inputValidationStage")) {
                        "inputValidationStage should not exist when guard is disabled"
                    }
                    assertFalse(context.containsBean("injectionDetectionStage")) {
                        "injectionDetectionStage should not exist when guard is disabled"
                    }
                }
        }

        @Test
        fun `disabled separately일 때 not register InjectionDetectionStage해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.guard.injection-detection-enabled=false")
                .run { context ->
                    assertTrue(context.containsBean("requestGuard")) {
                        "RequestGuard should still exist"
                    }
                    assertTrue(context.containsBean("rateLimitStage")) {
                        "rateLimitStage should still exist"
                    }
                    assertTrue(context.containsBean("inputValidationStage")) {
                        "inputValidationStage should still exist"
                    }
                    assertFalse(context.containsBean("injectionDetectionStage")) {
                        "injectionDetectionStage should not exist when injection detection is disabled"
                    }
                }
        }

        @Test
        fun `apply boundaries input max chars to input validation stage해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.boundaries.input-max-chars=8765"
                )
                .run { context ->
                    assertEquals(8765, inputValidationMaxLength(context)) {
                        "inputValidationStage should use boundaries.input-max-chars"
                    }
                }
        }

        @Test
        fun `R287 type 기반 conditional은 다른 이름의 RateLimitStage bean이 default를 비활성화한다`() {
            // R287 fix 검증: 이전 name 기반 conditional은 사용자가 다른 이름의 bean을 등록하면
            // default도 함께 등록되어 double-rate-limiting 발생. type 기반으로 변경한 후에는
            // 사용자가 어떤 이름이든 RateLimitStage bean을 제공하면 default가 자동 비활성화.
            contextRunner
                .withUserConfiguration(CustomRateLimitStageConfig::class.java)
                .run { context ->
                    val rateLimitBeans = context.getBeansOfType(
                        com.arc.reactor.guard.RateLimitStage::class.java
                    )
                    assertEquals(1, rateLimitBeans.size) {
                        "R287 fix: 다른 이름의 RateLimitStage 사용자 bean 등록 시 RateLimitStage 인스턴스는 " +
                            "정확히 1개여야 하지만 ${rateLimitBeans.size}개 등록됨: ${rateLimitBeans.keys}. " +
                            "name 기반 conditional이라면 default도 함께 등록되어 2개가 된다."
                    }
                    assertTrue(rateLimitBeans.containsKey("customRateLimit")) {
                        "사용자 bean 'customRateLimit'이 등록되어야 한다"
                    }
                    assertFalse(rateLimitBeans.containsKey("rateLimitStage")) {
                        "R287 fix: 사용자 override 시 default 'rateLimitStage'는 등록되지 않아야 한다"
                    }
                }
        }
    }

    // ── Feedback Store Configuration (R289) ─────────────────────────

    @Nested
    inner class FeedbackStoreDefaults {

        @Test
        fun `R289 default JDBC 배포에서 JdbcFeedbackStore가 자동 등록되어야 한다`() {
            // R289 fix 검증: 이전 구현은 FeedbackMetadataCaptureHook이 matchIfMissing=true로
            // default ON이지만 JdbcFeedbackStore가 matchIfMissing=false로 default OFF여서
            // hook이 capture한 데이터가 in-memory fallback에 저장되어 restart 시 silent loss.
            // R289에서 JdbcFeedbackStore의 matchIfMissing=true로 정렬하여 default JDBC 배포에서
            // 자동 영속 저장.
            jdbcContextRunner
                .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:r289FeedbackDefault;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run { context ->
                    assertTrue(context.containsBean("jdbcFeedbackStore")) {
                        "R289 fix: default JDBC 배포에서 jdbcFeedbackStore가 자동 등록되어야 한다 " +
                            "(matchIfMissing=true). 이전 false 설정에서는 등록 안 됨 → " +
                            "FeedbackMetadataCaptureHook이 capture한 데이터가 in-memory에 저장 → " +
                            "restart 시 silent data loss."
                    }
                    val store = context.getBean(
                        com.arc.reactor.feedback.FeedbackStore::class.java
                    )
                    assertInstanceOf(
                        com.arc.reactor.feedback.JdbcFeedbackStore::class.java,
                        store
                    ) {
                        "R289 fix: default 활성화된 FeedbackStore는 JdbcFeedbackStore여야 한다"
                    }
                }
        }

        @Test
        fun `R289 feedback enabled false 명시 시 JdbcFeedbackStore가 비활성화되어야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.feedback.enabled=false",
                    "spring.datasource.url=jdbc:h2:mem:r289FeedbackDisabled;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run { context ->
                    assertFalse(context.containsBean("jdbcFeedbackStore")) {
                        "R289 fix: arc.reactor.feedback.enabled=false 명시 시 JdbcFeedbackStore는 " +
                            "등록되지 않아야 한다 (operator opt-out 보존)"
                    }
                }
        }
    }

    /** R287 회귀 테스트용 — 다른 이름으로 등록되는 RateLimitStage 사용자 구성. */
    @Configuration
    class CustomRateLimitStageConfig {
        @Bean
        fun customRateLimit(): com.arc.reactor.guard.RateLimitStage =
            object : com.arc.reactor.guard.RateLimitStage {
                override suspend fun enforce(
                    command: com.arc.reactor.guard.model.GuardCommand
                ): com.arc.reactor.guard.model.GuardResult =
                    com.arc.reactor.guard.model.GuardResult.Allowed.DEFAULT
            }
    }

    // ── Webhook ─────────────────────────────────────────────────────

    @Nested
    inner class WebhookTests {

        @Test
        fun `not register webhook hook by default해야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("webhookNotificationHook")) {
                    "WebhookNotificationHook should not exist by default (opt-in)"
                }
            }
        }

        @Test
        fun `enabled일 때 register webhook hook해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.webhook.enabled=true",
                    "arc.reactor.webhook.url=http://test-webhook"
                )
                .run { context ->
                    assertTrue(context.containsBean("webhookNotificationHook")) {
                        "WebhookNotificationHook should exist when webhook is enabled"
                    }
                    assertInstanceOf(
                        WebhookNotificationHook::class.java,
                        context.getBean("webhookNotificationHook"),
                        "Bean should be WebhookNotificationHook"
                    )
                }
        }
    }

    // ── RAG Configuration ───────────────────────────────────────────

    @Nested
    inner class RagConfigurationTests {

        @Test
        fun `not register RAG beans by default해야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("ragPipeline")) {
                    "RagPipeline should not exist by default (opt-in)"
                }
                assertFalse(context.containsBean("documentRetriever")) {
                    "DocumentRetriever should not exist by default"
                }
                assertFalse(context.containsBean("documentReranker")) {
                    "DocumentReranker should not exist by default"
                }
            }
        }

        @Test
        fun `VectorStore is present일 때 register RAG beans해야 한다`() {
            contextRunner
                .withUserConfiguration(MockVectorStoreConfig::class.java)
                .withPropertyValues("arc.reactor.rag.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        DefaultRagPipeline::class.java, context.getBean(RagPipeline::class.java),
                        "Default RagPipeline should be DefaultRagPipeline"
                    )
                    assertInstanceOf(
                        SpringAiVectorStoreRetriever::class.java, context.getBean(DocumentRetriever::class.java),
                        "Default DocumentRetriever should be SpringAiVectorStoreRetriever when VectorStore is present"
                    )
                    assertInstanceOf(
                        SimpleScoreReranker::class.java, context.getBean(DocumentReranker::class.java),
                        "Default DocumentReranker should be SimpleScoreReranker"
                    )
                    assertInstanceOf(
                        PassthroughQueryTransformer::class.java, context.getBean(QueryTransformer::class.java),
                        "Default QueryTransformer should be PassthroughQueryTransformer"
                    )
                }
        }

        @Test
        fun `configured일 때 register HyDE query transformer해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java, MockVectorStoreConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.rag.enabled=true",
                    "arc.reactor.rag.query-transformer=hyde"
                )
                .run { context ->
                    assertInstanceOf(
                        HyDEQueryTransformer::class.java, context.getBean(QueryTransformer::class.java),
                        "QueryTransformer should be HyDEQueryTransformer when configured"
                    )
                }
        }
    }

    // ── Agent Executor ──────────────────────────────────────────────

    @Nested
    inner class AgentExecutorTests {

        @Test
        fun `not register AgentExecutor without ChatClient해야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("agentExecutor")) {
                    "AgentExecutor should not exist without ChatClient bean"
                }
            }
        }

        @Test
        fun `ChatClient is available일 때 register AgentExecutor해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java)
                .run { context ->
                    assertTrue(context.containsBean("agentExecutor")) {
                        "AgentExecutor should exist when ChatClient is available"
                    }
                    assertNotNull(context.getBean(AgentExecutor::class.java)) {
                        "AgentExecutor bean should not be null"
                    }
                }
        }

        @Test
        fun `only ChatModel is available일 때 register AgentExecutor해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatModelOnlyConfig::class.java)
                .run { context ->
                    assertTrue(context.containsBean("chatClient")) {
                        "chatClient should be auto-configured from ChatModel"
                    }
                    assertTrue(context.containsBean("agentExecutor")) {
                        "AgentExecutor should exist when ChatModel is available"
                    }
                    assertNotNull(context.getBean(AgentExecutor::class.java)) {
                        "AgentExecutor bean should not be null"
                    }
                }
        }
    }

    // ── JDBC Store Overrides ────────────────────────────────────────

    @Nested
    inner class JdbcStoreOverrideTests {

        @Test
        fun `datasource is configured일 때 use JDBC stores해야 한다`() {
            jdbcContextRunner.run { context ->
                assertInstanceOf(
                    JdbcMemoryStore::class.java, context.getBean(MemoryStore::class.java),
                    "MemoryStore should be JdbcMemoryStore when datasource is configured"
                )
                assertInstanceOf(
                    JdbcPersonaStore::class.java, context.getBean(PersonaStore::class.java),
                    "PersonaStore should be JdbcPersonaStore when datasource is configured"
                )
                assertInstanceOf(
                    JdbcPromptTemplateStore::class.java, context.getBean(PromptTemplateStore::class.java),
                    "PromptTemplateStore should be JdbcPromptTemplateStore when datasource is configured"
                )
                assertInstanceOf(
                    JdbcMcpServerStore::class.java, context.getBean(McpServerStore::class.java),
                    "McpServerStore should be JdbcMcpServerStore when datasource is configured"
                )
                assertInstanceOf(
                    JdbcOutputGuardRuleStore::class.java, context.getBean(OutputGuardRuleStore::class.java),
                    "OutputGuardRuleStore should be JDBC when datasource is configured"
                )
                assertInstanceOf(
                    JdbcOutputGuardRuleAuditStore::class.java,
                    context.getBean(OutputGuardRuleAuditStore::class.java),
                    "OutputGuardRuleAuditStore should be JDBC when datasource is configured"
                )
            }
        }

        @Test
        fun `use in-memory stores without datasource해야 한다`() {
            contextRunner.run { context ->
                assertInstanceOf(
                    InMemoryMemoryStore::class.java, context.getBean(MemoryStore::class.java),
                    "MemoryStore should be InMemoryMemoryStore without datasource"
                )
                assertInstanceOf(
                    InMemoryPersonaStore::class.java, context.getBean(PersonaStore::class.java),
                    "PersonaStore should be InMemoryPersonaStore without datasource"
                )
                assertInstanceOf(
                    InMemoryPromptTemplateStore::class.java, context.getBean(PromptTemplateStore::class.java),
                    "PromptTemplateStore should be InMemoryPromptTemplateStore without datasource"
                )
                assertInstanceOf(
                    InMemoryMcpServerStore::class.java, context.getBean(McpServerStore::class.java),
                    "McpServerStore should be InMemoryMcpServerStore without datasource"
                )
                assertInstanceOf(
                    InMemoryOutputGuardRuleStore::class.java, context.getBean(OutputGuardRuleStore::class.java),
                    "OutputGuardRuleStore should be InMemoryOutputGuardRuleStore without datasource"
                )
                assertInstanceOf(
                    InMemoryOutputGuardRuleAuditStore::class.java,
                    context.getBean(OutputGuardRuleAuditStore::class.java),
                    "OutputGuardRuleAuditStore should be InMemoryOutputGuardRuleAuditStore without datasource"
                )
            }
        }

        @Test
        fun `datasource url is blank일 때 keep in-memory stores해야 한다`() {
            contextRunner
                .withPropertyValues("spring.datasource.url=")
                .run { context ->
                    assertNull(context.startupFailure) {
                        "Context should start when spring.datasource.url is blank"
                    }
                    assertInstanceOf(
                        InMemoryMemoryStore::class.java, context.getBean(MemoryStore::class.java),
                        "MemoryStore should stay in-memory when datasource URL is blank"
                    )
                    assertInstanceOf(
                        InMemoryPersonaStore::class.java, context.getBean(PersonaStore::class.java),
                        "PersonaStore should stay in-memory when datasource URL is blank"
                    )
                    assertInstanceOf(
                        InMemoryPromptTemplateStore::class.java, context.getBean(PromptTemplateStore::class.java),
                        "PromptTemplateStore should stay in-memory when datasource URL is blank"
                    )
                    assertInstanceOf(
                        InMemoryMcpServerStore::class.java, context.getBean(McpServerStore::class.java),
                        "McpServerStore should stay in-memory when datasource URL is blank"
                    )
                    assertInstanceOf(
                        InMemoryOutputGuardRuleStore::class.java,
                        context.getBean(OutputGuardRuleStore::class.java),
                        "OutputGuardRuleStore should stay in-memory when datasource URL is blank"
                    )
                    assertInstanceOf(
                        InMemoryOutputGuardRuleAuditStore::class.java,
                        context.getBean(OutputGuardRuleAuditStore::class.java),
                        "OutputGuardRuleAuditStore should stay in-memory when datasource URL is blank"
                    )
                }
        }
    }

    // ── Runtime Preflight Configuration ───────────────────────────

    @Nested
    inner class RuntimePreflightConfigurationTests {

        @Test
        fun `legacy auth toggle is explicitly false일 때 fail startup해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.auth.enabled=false")
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail when arc.reactor.auth.enabled=false is provided"
                    }
                    assertTrue(failureContains(context.startupFailure, "no longer supported")) {
                        "Failure message should explain that auth.enabled=false is not supported"
                    }
                }
        }

        @Test
        fun `legacy auth toggle is true일 때 keep startup healthy해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.auth.enabled=true")
                .run { context ->
                    assertNull(context.startupFailure) {
                        "Context should still start when legacy auth.enabled=true is provided"
                    }
                }
        }

        @Test
        fun `default tenant id is invalid일 때 fail startup해야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.auth.default-tenant-id=tenant invalid")
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail when default tenant id is invalid"
                    }
                    assertTrue(failureContains(context.startupFailure, "default-tenant-id")) {
                        "Failure should reference arc.reactor.auth.default-tenant-id"
                    }
                }
        }

        @Test
        fun `postgres required and datasource username is blank일 때 fail startup해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.postgres.required=true",
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/arc",
                    "spring.datasource.username=",
                    "spring.datasource.password=arc"
                )
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail when username is missing under postgres-required mode"
                    }
                    assertTrue(failureContains(context.startupFailure, "spring.datasource.username")) {
                        "Failure should clearly point to spring.datasource.username"
                    }
                }
        }

        @Test
        fun `postgres required and datasource password is blank일 때 fail startup해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.postgres.required=true",
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/arc",
                    "spring.datasource.username=arc",
                    "spring.datasource.password="
                )
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail when password is missing under postgres-required mode"
                    }
                    assertTrue(failureContains(context.startupFailure, "spring.datasource.password")) {
                        "Failure should clearly point to spring.datasource.password"
                    }
                }
        }

        private fun failureContains(throwable: Throwable?, fragment: String): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current.message?.contains(fragment) == true) return true
                current = current.cause
            }
            return false
        }
    }

    // ── Auth Configuration ──────────────────────────────────────────

    @Nested
    inner class AuthConfigurationTests {

        @Test
        fun `register auth beans by default해야 한다`() {
            contextRunner.run { context ->
                assertTrue(context.containsBean("userStore")) {
                    "UserStore should exist by default because auth is mandatory"
                }
                assertTrue(context.containsBean("jwtTokenProvider")) {
                    "JwtTokenProvider should exist by default"
                }
                assertTrue(context.containsBean("adminInitializer")) {
                    "AdminInitializer should exist by default"
                }
            }
        }

        @Test
        fun `jdbc datasource로 register auth beans해야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
                )
                .run { context ->
                    assertNotNull(context.getBean(UserStore::class.java)) {
                        "UserStore should exist when auth is enabled"
                    }
                    assertTrue(context.containsBean("authProvider")) {
                        "AuthProvider should exist when auth is enabled"
                    }
                    assertTrue(context.containsBean("jwtTokenProvider")) {
                        "JwtTokenProvider should exist when auth is enabled"
                    }
                    assertTrue(context.containsBean("adminInitializer")) {
                        "AdminInitializer should exist when auth is enabled"
                    }
                }
        }

        @Test
        fun `datasource url is blank일 때 use in-memory user store해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "spring.datasource.url=",
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
                )
                .run { context ->
                    assertNull(context.startupFailure) {
                        "Context should start when datasource URL is blank"
                    }
                    assertInstanceOf(
                        InMemoryUserStore::class.java,
                        context.getBean(UserStore::class.java),
                        "UserStore should fallback to in-memory when datasource URL is blank"
                    )
                }
        }

        @Test
        fun `expose actuator health by default해야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
                )
                .run { context ->
                    val props = context.getBean(com.arc.reactor.auth.AuthProperties::class.java)
                    assertTrue(props.publicPaths.any { it == "/actuator/health" }) {
                        "publicPaths should include /actuator/health by default"
                    }
                }
        }

        @Test
        fun `enabled일 때 append actuator health to public paths해야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough",
                    "arc.reactor.auth.public-actuator-health=true"
                )
                .run { context ->
                    val props = context.getBean(com.arc.reactor.auth.AuthProperties::class.java)
                    assertTrue(props.publicPaths.any { it == "/actuator/health" }) {
                        "publicPaths should include /actuator/health when arc.reactor.auth.public-actuator-health=true"
                    }
                }
        }

        @Test
        fun `disable self-registration by default해야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
                )
                .run { context ->
                    val props = context.getBean(com.arc.reactor.auth.AuthProperties::class.java)
                    assertFalse(props.selfRegistrationEnabled) {
                        "selfRegistrationEnabled should default to false for conservative deployments"
                    }
                    assertFalse(props.publicPaths.any { it == "/api/auth/register" }) {
                        "publicPaths should not include /api/auth/register when self registration is disabled"
                    }
                }
        }

        @Test
        fun `self-registration is enabled일 때 expose register path해야 한다`() {
            jdbcContextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough",
                    "arc.reactor.auth.self-registration-enabled=true"
                )
                .run { context ->
                    val props = context.getBean(com.arc.reactor.auth.AuthProperties::class.java)
                    assertTrue(props.selfRegistrationEnabled) {
                        "selfRegistrationEnabled should follow arc.reactor.auth.self-registration-enabled"
                    }
                    assertTrue(props.publicPaths.any { it == "/api/auth/register" }) {
                        "publicPaths should include /api/auth/register when self-registration is enabled"
                    }
                }
        }

        @Test
        fun `auth enabled with empty jwt secret일 때 fail context startup해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret="
                )
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail to start when jwt-secret is empty"
                    }
                    val message = context.startupFailure!!.message ?: ""
                    assertTrue(message.contains("arc.reactor.auth.jwt-secret") || causeChainContains(context.startupFailure!!, "arc.reactor.auth.jwt-secret")) {
                        "Failure cause should reference arc.reactor.auth.jwt-secret"
                    }
                }
        }

        @Test
        fun `auth enabled with short jwt secret일 때 fail context startup해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.auth.jwt-secret=tooshort"
                )
                .run { context ->
                    assertTrue(context.startupFailure != null) {
                        "Context should fail to start when jwt-secret is shorter than 32 bytes"
                    }
                }
        }

        private fun causeChainContains(throwable: Throwable, substring: String): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current.message?.contains(substring) == true) return true
                current = current.cause
            }
            return false
        }
    }

    // ── Memory Summary Configuration ────────────────────────────────

    @Nested
    inner class MemorySummaryConfigurationTests {

        @Test
        fun `not register summary beans by default해야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("conversationSummaryStore")) {
                    "ConversationSummaryStore should not exist by default (summary is opt-in)"
                }
                assertFalse(context.containsBean("conversationSummaryService")) {
                    "ConversationSummaryService should not exist by default"
                }
            }
        }

        @Test
        fun `enabled with ChatClient일 때 register InMemory summary store해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.memory.summary.enabled=true",
                    "arc.reactor.llm.default-provider=chatModel"
                )
                .run { context ->
                    assertInstanceOf(
                        InMemoryConversationSummaryStore::class.java,
                        context.getBean(ConversationSummaryStore::class.java),
                        "Default summary store should be InMemoryConversationSummaryStore"
                    )
                    assertInstanceOf(
                        LlmConversationSummaryService::class.java,
                        context.getBean(ConversationSummaryService::class.java),
                        "Summary service should be LlmConversationSummaryService"
                    )
                }
        }

        @Test
        fun `datasource is configured and summary enabled일 때 use JDBC summary store해야 한다`() {
            contextRunner
                .withConfiguration(
                    AutoConfigurations.of(
                        DataSourceAutoConfiguration::class.java,
                        JdbcTemplateAutoConfiguration::class.java,
                        DataSourceTransactionManagerAutoConfiguration::class.java,
                        TransactionAutoConfiguration::class.java
                    )
                )
                .withUserConfiguration(MockChatClientConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.memory.summary.enabled=true",
                    "arc.reactor.llm.default-provider=chatModel",
                    "spring.datasource.url=jdbc:h2:mem:summaryTest;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run { context ->
                    assertInstanceOf(
                        JdbcConversationSummaryStore::class.java,
                        context.getBean(ConversationSummaryStore::class.java),
                        "Summary store should be JDBC when datasource is available"
                    )
                }
        }

        @Test
        fun `enabled일 때 inject summary dependencies into ConversationManager해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.memory.summary.enabled=true",
                    "arc.reactor.llm.default-provider=chatModel"
                )
                .run { context ->
                    val manager = context.getBean(ConversationManager::class.java)
                    assertInstanceOf(DefaultConversationManager::class.java, manager,
                        "ConversationManager should be DefaultConversationManager")

                    // summary store and service exist as separate beans 확인
                    assertTrue(context.containsBean("conversationSummaryStore")) {
                        "ConversationSummaryStore bean must exist when summary is enabled"
                    }
                    assertTrue(context.containsBean("conversationSummaryService")) {
                        "ConversationSummaryService bean must exist when summary is enabled"
                    }
                }
        }

        @Test
        fun `datasource url is blank일 때 use in-memory summary store해야 한다`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java)
                .withPropertyValues(
                    "spring.datasource.url=",
                    "arc.reactor.memory.summary.enabled=true",
                    "arc.reactor.llm.default-provider=chatModel"
                )
                .run { context ->
                    assertNull(context.startupFailure) {
                        "Context should start when summary is enabled and datasource URL is blank"
                    }
                    assertInstanceOf(
                        InMemoryConversationSummaryStore::class.java,
                        context.getBean(ConversationSummaryStore::class.java),
                        "Summary store should fallback to in-memory when datasource URL is blank"
                    )
                }
        }
    }

    // ── User Memory Configuration ──────────────────────────────────

    @Nested
    inner class UserMemoryConfigurationTests {

        @Test
        fun `datasource url is blank일 때 use in-memory user memory store해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "spring.datasource.url=",
                    "arc.reactor.memory.user.enabled=true"
                )
                .run { context ->
                    assertNull(context.startupFailure) {
                        "Context should start when user memory is enabled and datasource URL is blank"
                    }
                    assertInstanceOf(
                        InMemoryUserMemoryStore::class.java,
                        context.getBean(UserMemoryStore::class.java),
                        "UserMemoryStore should fallback to in-memory when datasource URL is blank"
                    )
                }
        }

        @Test
        fun `datasource is configured일 때 use jdbc user memory store해야 한다`() {
            jdbcContextRunner
                .withPropertyValues("arc.reactor.memory.user.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        JdbcUserMemoryStore::class.java,
                        context.getBean(UserMemoryStore::class.java),
                        "UserMemoryStore should be JDBC when datasource is configured"
                    )
                }
        }
    }

    // ── Test Configuration Classes ──────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    class CustomToolSelectorConfig {
        @Bean
        fun toolSelector(): ToolSelector = object : ToolSelector {
            override fun select(
                prompt: String,
                availableTools: List<ToolCallback>
            ): List<ToolCallback> = emptyList()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomMemoryStoreConfig {
        @Bean
        fun memoryStore(): MemoryStore = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class CustomAgentMetricsConfig {
        @Bean
        fun agentMetrics(): AgentMetrics = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class MockChatClientConfig {
        @Bean
        fun chatModel(): ChatModel = mockk(relaxed = true)

        @Bean
        fun chatClient(chatModel: ChatModel): ChatClient = ChatClient.create(chatModel)
    }

    @Configuration(proxyBeanMethods = false)
    class MockChatModelOnlyConfig {
        @Bean
        fun chatModel(): ChatModel = mockk(relaxed = true)
    }

    @Configuration(proxyBeanMethods = false)
    class MockVectorStoreConfig {
        @Bean
        fun vectorStore(): VectorStore = mockk(relaxed = true)
    }
}
