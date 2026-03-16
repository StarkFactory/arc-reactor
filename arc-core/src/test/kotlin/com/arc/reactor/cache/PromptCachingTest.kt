package com.arc.reactor.cache

import com.arc.reactor.agent.config.PromptCachingProperties
import com.arc.reactor.cache.impl.AnthropicPromptCachingService
import com.arc.reactor.cache.impl.NoOpPromptCachingService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Anthropic 프롬프트 캐싱 지원에 대한 테스트.
 */
class PromptCachingTest {

    private val defaultProperties = PromptCachingProperties(
        enabled = true,
        provider = "anthropic",
        cacheSystemPrompt = true,
        cacheTools = true,
        minCacheableTokens = 1024
    )

    @Nested
    inner class NoOpPromptCachingServiceTests {

        private val service = NoOpPromptCachingService()

        @Test
        fun `applyCaching은(는) returns original options unchanged`() {
            val options: ChatOptions = mockk()

            val result = service.applyCaching(options, "anthropic", 5000)

            assertSame(options, result) {
                "NoOp service must return the same options object without modification"
            }
        }

        @Test
        fun `extractCacheMetrics은(는) always returns null`() {
            val result = service.extractCacheMetrics(Any())

            assertNull(result) {
                "NoOp service must always return null for cache metrics"
            }
        }
    }

    @Nested
    inner class AnthropicPromptCachingServiceTests {

        @Nested
        inner class ProviderFiltering {

            @Test
            fun `non-anthropic provider에 대해 original options를 반환한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val options: ChatOptions = mockk()

                val result = service.applyCaching(options, "gemini", 5000)

                assertSame(options, result) {
                    "Non-anthropic provider must not have cache options applied"
                }
            }

            @Test
            fun `openai provider에 대해 original options를 반환한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val options: ChatOptions = mockk()

                val result = service.applyCaching(options, "openai", 5000)

                assertSame(options, result) {
                    "OpenAI provider must not have cache options applied"
                }
            }

            @Test
            fun `provider matching은(는) case-insensitive이다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "ANTHROPIC", 2000)

                assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "Case-insensitive provider match must apply caching to 'ANTHROPIC'"
                }
            }
        }

        @Nested
        inner class MinCacheableTokensGuard {

            @Test
            fun `tokens below threshold일 때 original options를 반환한다`() {
                val service = AnthropicPromptCachingService(defaultProperties.copy(minCacheableTokens = 1024))
                val options: ChatOptions = mockk()

                val result = service.applyCaching(options, "anthropic", 500)

                assertSame(options, result) {
                    "System prompt with 500 estimated tokens must not be marked for caching " +
                        "(below minCacheableTokens=1024)"
                }
            }

            @Test
            fun `caching when tokens meet threshold exactly를 적용한다`() {
                val service = AnthropicPromptCachingService(defaultProperties.copy(minCacheableTokens = 1024))
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "anthropic", 1024)

                assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "System prompt with exactly 1024 tokens must be marked for caching"
                }
            }

            @Test
            fun `caching when tokens exceed threshold를 적용한다`() {
                val service = AnthropicPromptCachingService(defaultProperties.copy(minCacheableTokens = 1024))
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "anthropic", 5000)

                assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "System prompt with 5000 tokens must be marked for caching"
                }
            }
        }

        @Nested
        inner class CacheStrategySelection {

            @Test
            fun `both flags enabled일 때 SYSTEM_AND_TOOLS strategy`() {
                val props = defaultProperties.copy(cacheSystemPrompt = true, cacheTools = true)
                val service = AnthropicPromptCachingService(props)
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "anthropic", 2000)

                val anthropicOpts = assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "Result must be AnthropicChatOptions when caching is applied"
                }
                val cacheOptions = anthropicOpts.cacheOptions
                assertNotNull(cacheOptions) { "Cache options must be set when caching is applied" }
                assertEquals(AnthropicCacheStrategy.SYSTEM_AND_TOOLS, cacheOptions!!.strategy) {
                    "SYSTEM_AND_TOOLS strategy must be selected when both cacheSystemPrompt and cacheTools are true"
                }
            }

            @Test
            fun `only system prompt caching enabled일 때 SYSTEM_ONLY strategy`() {
                val props = defaultProperties.copy(cacheSystemPrompt = true, cacheTools = false)
                val service = AnthropicPromptCachingService(props)
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "anthropic", 2000)

                val anthropicOpts = assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "Result must be AnthropicChatOptions when caching is applied"
                }
                assertEquals(AnthropicCacheStrategy.SYSTEM_ONLY, anthropicOpts.cacheOptions?.strategy) {
                    "SYSTEM_ONLY strategy must be selected when only cacheSystemPrompt=true"
                }
            }

            @Test
            fun `only tool caching enabled일 때 TOOLS_ONLY strategy`() {
                val props = defaultProperties.copy(cacheSystemPrompt = false, cacheTools = true)
                val service = AnthropicPromptCachingService(props)
                val base = ChatOptions.builder().temperature(0.3).maxTokens(100).build()

                val result = service.applyCaching(base, "anthropic", 2000)

                val anthropicOpts = assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "Result must be AnthropicChatOptions when caching is applied"
                }
                assertEquals(AnthropicCacheStrategy.TOOLS_ONLY, anthropicOpts.cacheOptions?.strategy) {
                    "TOOLS_ONLY strategy must be selected when only cacheTools=true"
                }
            }
        }

        @Nested
        inner class TemperaturePreservation {

            @Test
            fun `temperature from original options를 보존한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val base = ChatOptions.builder().temperature(0.7).maxTokens(512).build()

                val result = service.applyCaching(base, "anthropic", 2000)

                val anthropicOpts = assertInstanceOf(AnthropicChatOptions::class.java, result) {
                    "Result must be AnthropicChatOptions when caching is applied"
                }
                assertEquals(0.7, anthropicOpts.temperature) {
                    "Temperature must be preserved when building cached options"
                }
            }
        }

        @Nested
        inner class CacheMetricsExtraction {

            @Test
            fun `non-Anthropic usage type에 대해 null를 반환한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)

                val result = service.extractCacheMetrics("not-an-anthropic-usage")

                assertNull(result) {
                    "extractCacheMetrics must return null for non-AnthropicApi.Usage objects"
                }
            }

            @Test
            fun `null usage에 대해 null를 반환한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)

                val result = service.extractCacheMetrics(null)

                assertNull(result) {
                    "extractCacheMetrics must return null when nativeUsage is null"
                }
            }

            @Test
            fun `cache creation tokens from Anthropic usage를 파싱한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val usage = mockk<AnthropicApi.Usage>()
                every { usage.cacheCreationInputTokens() } returns 2048
                every { usage.cacheReadInputTokens() } returns 0
                every { usage.inputTokens() } returns 100

                val metrics = service.extractCacheMetrics(usage)

                assertNotNull(metrics) { "Metrics must not be null for AnthropicApi.Usage input" }
                assertEquals(2048, metrics!!.cacheCreationInputTokens) {
                    "cacheCreationInputTokens must match the value from AnthropicApi.Usage"
                }
                assertEquals(0, metrics.cacheReadInputTokens) {
                    "cacheReadInputTokens must be 0 on first request (cache miss)"
                }
            }

            @Test
            fun `cache read tokens from Anthropic usage on cache hit를 파싱한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val usage = mockk<AnthropicApi.Usage>()
                every { usage.cacheCreationInputTokens() } returns 0
                every { usage.cacheReadInputTokens() } returns 2048
                every { usage.inputTokens() } returns 50

                val metrics = service.extractCacheMetrics(usage)

                assertNotNull(metrics) { "Metrics must not be null for AnthropicApi.Usage input" }
                assertEquals(0, metrics!!.cacheCreationInputTokens) {
                    "cacheCreationInputTokens must be 0 on cache hit"
                }
                assertEquals(2048, metrics.cacheReadInputTokens) {
                    "cacheReadInputTokens must match the value from AnthropicApi.Usage on cache hit"
                }
                assertEquals(50, metrics.regularInputTokens) {
                    "regularInputTokens must match the inputTokens from AnthropicApi.Usage"
                }
            }

            @Test
            fun `null cache token values as zero를 파싱한다`() {
                val service = AnthropicPromptCachingService(defaultProperties)
                val usage = mockk<AnthropicApi.Usage>()
                every { usage.cacheCreationInputTokens() } returns null
                every { usage.cacheReadInputTokens() } returns null
                every { usage.inputTokens() } returns null

                val metrics = service.extractCacheMetrics(usage)

                assertNotNull(metrics) { "Metrics must not be null even when all usage values are null" }
                assertEquals(0, metrics!!.cacheCreationInputTokens) {
                    "Null cacheCreationInputTokens must be treated as 0"
                }
                assertEquals(0, metrics.cacheReadInputTokens) {
                    "Null cacheReadInputTokens must be treated as 0"
                }
                assertEquals(0, metrics.regularInputTokens) {
                    "Null inputTokens must be treated as 0"
                }
            }
        }
    }

    @Nested
    inner class PromptCacheMetricsDataClass {

        @Test
        fun `values are all zero를 기본값으로 한다`() {
            val metrics = PromptCacheMetrics()

            assertEquals(0, metrics.cacheCreationInputTokens) {
                "Default cacheCreationInputTokens must be 0"
            }
            assertEquals(0, metrics.cacheReadInputTokens) {
                "Default cacheReadInputTokens must be 0"
            }
            assertEquals(0, metrics.regularInputTokens) {
                "Default regularInputTokens must be 0"
            }
        }

        @Test
        fun `모든 fields populated correctly`() {
            val metrics = PromptCacheMetrics(
                cacheCreationInputTokens = 1024,
                cacheReadInputTokens = 512,
                regularInputTokens = 200
            )

            assertEquals(1024, metrics.cacheCreationInputTokens) {
                "cacheCreationInputTokens must match constructor argument"
            }
            assertEquals(512, metrics.cacheReadInputTokens) {
                "cacheReadInputTokens must match constructor argument"
            }
            assertEquals(200, metrics.regularInputTokens) {
                "regularInputTokens must match constructor argument"
            }
        }
    }
}
