package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.GuardPipeline
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
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.PassthroughQueryTransformer
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.tool.AllToolSelector
import com.arc.reactor.tool.ToolCallback
import com.arc.reactor.tool.ToolSelector
import io.mockk.mockk
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

class ArcReactorAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcReactorAutoConfiguration::class.java))

    // ── Default Beans ───────────────────────────────────────────────

    @Nested
    inner class DefaultBeans {

        @Test
        fun `should register AllToolSelector by default`() {
            contextRunner.run { context ->
                assertInstanceOf(AllToolSelector::class.java, context.getBean(ToolSelector::class.java),
                    "Default ToolSelector should be AllToolSelector")
            }
        }

        @Test
        fun `should register InMemoryMemoryStore by default`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryMemoryStore::class.java, context.getBean(MemoryStore::class.java),
                    "Default MemoryStore should be InMemoryMemoryStore")
            }
        }

        @Test
        fun `should register InMemoryPersonaStore by default`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryPersonaStore::class.java, context.getBean(PersonaStore::class.java),
                    "Default PersonaStore should be InMemoryPersonaStore")
            }
        }

        @Test
        fun `should register InMemoryPromptTemplateStore by default`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryPromptTemplateStore::class.java, context.getBean(PromptTemplateStore::class.java),
                    "Default PromptTemplateStore should be InMemoryPromptTemplateStore")
            }
        }

        @Test
        fun `should register InMemoryMcpServerStore by default`() {
            contextRunner.run { context ->
                assertInstanceOf(InMemoryMcpServerStore::class.java, context.getBean(McpServerStore::class.java),
                    "Default McpServerStore should be InMemoryMcpServerStore")
            }
        }

        @Test
        fun `should register DefaultErrorMessageResolver by default`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultErrorMessageResolver::class.java, context.getBean(ErrorMessageResolver::class.java),
                    "Default ErrorMessageResolver should be DefaultErrorMessageResolver")
            }
        }

        @Test
        fun `should register NoOpAgentMetrics by default`() {
            contextRunner.run { context ->
                assertInstanceOf(NoOpAgentMetrics::class.java, context.getBean(AgentMetrics::class.java),
                    "Default AgentMetrics should be NoOpAgentMetrics")
            }
        }

        @Test
        fun `should register DefaultTokenEstimator by default`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultTokenEstimator::class.java, context.getBean(TokenEstimator::class.java),
                    "Default TokenEstimator should be DefaultTokenEstimator")
            }
        }

        @Test
        fun `should register DefaultConversationManager by default`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultConversationManager::class.java, context.getBean(ConversationManager::class.java),
                    "Default ConversationManager should be DefaultConversationManager")
            }
        }

        @Test
        fun `should register DefaultMcpManager by default`() {
            contextRunner.run { context ->
                assertInstanceOf(DefaultMcpManager::class.java, context.getBean(McpManager::class.java),
                    "Default McpManager should be DefaultMcpManager")
            }
        }

        @Test
        fun `should register HookExecutor by default`() {
            contextRunner.run { context ->
                assertNotNull(context.getBean(HookExecutor::class.java)) {
                    "HookExecutor should be registered by default"
                }
            }
        }
    }

    // ── ConditionalOnMissingBean Override ────────────────────────────

    @Nested
    inner class ConditionalOnMissingBeanOverride {

        @Test
        fun `should use custom ToolSelector when user provides one`() {
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
        fun `should use custom MemoryStore when user provides one`() {
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
        fun `should use custom AgentMetrics when user provides one`() {
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
        fun `should register guard beans by default`() {
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
        fun `should not register guard beans when disabled`() {
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
        fun `should not register InjectionDetectionStage when disabled separately`() {
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
        fun `should use legacy guard max input length when boundaries input max is not set`() {
            contextRunner
                .withPropertyValues("arc.reactor.guard.max-input-length=4321")
                .run { context ->
                    assertEquals(4321, inputValidationMaxLength(context)) {
                        "Legacy guard max input length should be applied for backward compatibility"
                    }
                }
        }

        @Test
        fun `should prioritize boundaries input max over legacy guard max input length`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.guard.max-input-length=4321",
                    "arc.reactor.boundaries.input-max-chars=8765"
                )
                .run { context ->
                    assertEquals(8765, inputValidationMaxLength(context)) {
                        "boundaries.input-max-chars should take precedence over legacy guard.max-input-length"
                    }
                }
        }
    }

    // ── Webhook ─────────────────────────────────────────────────────

    @Nested
    inner class WebhookTests {

        @Test
        fun `should not register webhook hook by default`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("webhookNotificationHook")) {
                    "WebhookNotificationHook should not exist by default (opt-in)"
                }
            }
        }

        @Test
        fun `should register webhook hook when enabled`() {
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
        fun `should not register RAG beans by default`() {
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
        fun `should register RAG beans with in-memory retriever when enabled`() {
            contextRunner
                .withPropertyValues("arc.reactor.rag.enabled=true")
                .run { context ->
                    assertInstanceOf(
                        DefaultRagPipeline::class.java, context.getBean(RagPipeline::class.java),
                        "Default RagPipeline should be DefaultRagPipeline"
                    )
                    assertInstanceOf(
                        InMemoryDocumentRetriever::class.java, context.getBean(DocumentRetriever::class.java),
                        "Default DocumentRetriever should be InMemoryDocumentRetriever (no VectorStore)"
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
        fun `should register HyDE query transformer when configured`() {
            contextRunner
                .withUserConfiguration(MockChatClientConfig::class.java)
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
        fun `should not register AgentExecutor without ChatClient`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("agentExecutor")) {
                    "AgentExecutor should not exist without ChatClient bean"
                }
            }
        }

        @Test
        fun `should register AgentExecutor when ChatClient is available`() {
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
        fun `should register AgentExecutor when only ChatModel is available`() {
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

        @Test
        fun `should use JDBC stores when datasource is configured`() {
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
            }
        }

        @Test
        fun `should use in-memory stores without datasource`() {
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
            }
        }
    }

    // ── Auth Configuration ──────────────────────────────────────────

    @Nested
    inner class AuthConfigurationTests {

        @Test
        fun `should not register auth beans by default`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("userStore")) {
                    "UserStore should not exist when auth is disabled"
                }
                assertFalse(context.containsBean("jwtTokenProvider")) {
                    "JwtTokenProvider should not exist when auth is disabled"
                }
                assertFalse(context.containsBean("adminInitializer")) {
                    "AdminInitializer should not exist when auth is disabled"
                }
            }
        }

        @Test
        fun `should register auth beans when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.auth.enabled=true",
                    "arc.reactor.auth.jwt-secret=test-secret-key-for-hmac-sha256-that-is-long-enough"
                )
                .run { context ->
                    assertTrue(context.containsBean("userStore")) {
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
        fun `should append actuator health to public paths when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.auth.enabled=true",
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
}
