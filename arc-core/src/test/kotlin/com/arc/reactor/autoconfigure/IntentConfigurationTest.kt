package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.intent.InMemoryIntentRegistry
import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.IntentResolver
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * R305 회귀: [IntentConfiguration] DataSource 부재 시 InMemoryIntentRegistry 폴백 검증.
 *
 * 이전 동작: `arc.reactor.intent.enabled=true` + DataSource 없음 → `NoSuchBeanDefinitionException`으로
 * 애플리케이션 기동 실패. [JdbcIntentRegistryConfiguration]만 IntentRegistry를 제공하고
 * `@ConditionalOnBean(DataSource::class)` 게이트로 인해 DB 없는 환경에서는 빈이 생성되지 않았다.
 *
 * R305 fix: [IntentConfiguration]에 `@ConditionalOnMissingBean(IntentRegistry::class)` 폴백 빈 추가.
 */
class IntentConfigurationTest {

    /** 테스트용 AgentProperties — 기본값. */
    private fun defaultProperties(): AgentProperties = AgentProperties()

    /** 테스트용 ChatModelProvider mock. */
    private fun mockChatModelProvider(): ChatModelProvider {
        val provider = mockk<ChatModelProvider>()
        val chatClient = mockk<ChatClient>()
        io.mockk.every { provider.getChatClient(any()) } returns chatClient
        return provider
    }

    @Nested
    inner class FallbackRegistry {

        @Test
        fun `DataSource 없이 기동 시 InMemoryIntentRegistry 폴백이 제공되어야 한다`() {
            val runner = ApplicationContextRunner()
                .withBean(AgentProperties::class.java, { defaultProperties() })
                .withBean(ChatModelProvider::class.java, { mockChatModelProvider() })
                .withUserConfiguration(IntentConfiguration::class.java)
                .withPropertyValues("arc.reactor.intent.enabled=true")

            runner.run { context ->
                val startupFailure = context.startupFailure
                assertTrue(startupFailure == null) {
                    "Expected successful startup with InMemory fallback, got: ${startupFailure?.message}"
                }
                val registry = context.getBean(IntentRegistry::class.java)
                assertNotNull(registry) { "Expected IntentRegistry bean to be present" }
                assertTrue(registry is InMemoryIntentRegistry) {
                    "Expected InMemoryIntentRegistry fallback but got ${registry.javaClass.simpleName}"
                }
            }
        }

        @Test
        fun `사용자가 직접 제공한 IntentRegistry는 폴백보다 우선한다`() {
            val customRegistry = InMemoryIntentRegistry(maxEntries = 5)

            val runner = ApplicationContextRunner()
                .withBean(AgentProperties::class.java, { defaultProperties() })
                .withBean(ChatModelProvider::class.java, { mockChatModelProvider() })
                .withBean(IntentRegistry::class.java, { customRegistry })
                .withUserConfiguration(IntentConfiguration::class.java)
                .withPropertyValues("arc.reactor.intent.enabled=true")

            runner.run { context ->
                val registry = context.getBean(IntentRegistry::class.java)
                assertTrue(registry === customRegistry) {
                    "Expected user-provided IntentRegistry to take precedence over fallback"
                }
            }
        }

        @Test
        fun `intent 비활성화 시 IntentConfiguration 전체가 로드되지 않는다`() {
            val runner = ApplicationContextRunner()
                .withBean(AgentProperties::class.java, { defaultProperties() })
                .withBean(ChatModelProvider::class.java, { mockChatModelProvider() })
                .withUserConfiguration(IntentConfiguration::class.java)
                .withPropertyValues("arc.reactor.intent.enabled=false")

            runner.run { context ->
                val hasRegistry = context.beanDefinitionNames
                    .any { it == "intentRegistry" }
                assertFalse(hasRegistry) {
                    "Expected no intentRegistry bean when intent is disabled"
                }
            }
        }

        @Test
        fun `폴백 레지스트리는 IntentClassifier + IntentResolver와 함께 와이어링 된다`() {
            val runner = ApplicationContextRunner()
                .withBean(AgentProperties::class.java, { defaultProperties() })
                .withBean(ChatModelProvider::class.java, { mockChatModelProvider() })
                .withUserConfiguration(IntentConfiguration::class.java)
                .withPropertyValues("arc.reactor.intent.enabled=true")

            runner.run { context ->
                assertNotNull(context.getBean(IntentRegistry::class.java)) { "IntentRegistry 누락" }
                assertNotNull(context.getBean(IntentClassifier::class.java)) { "IntentClassifier 누락" }
                assertNotNull(context.getBean(IntentResolver::class.java)) { "IntentResolver 누락" }
            }
        }
    }
}
