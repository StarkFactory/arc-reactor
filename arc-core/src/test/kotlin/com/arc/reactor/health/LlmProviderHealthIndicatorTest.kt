package com.arc.reactor.health

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import org.springframework.core.env.Environment

class LlmProviderHealthIndicatorTest {

    private val environment = mockk<Environment>(relaxed = true)

    @Test
    fun `UP when at least one provider은(는) configured이다`() {
        every { environment.getProperty("gemini.api.key") } returns "test-gemini-key"
        every { environment.getProperty("spring.ai.openai.api-key") } returns null
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns null
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "gemini"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["gemini"] shouldBe "configured"
        health.details["openai"] shouldBe "not configured"
        health.details["anthropic"] shouldBe "not configured"
        health.details["configuredCount"] shouldBe 1
    }

    @Test
    fun `DOWN when no provider은(는) configured이다`() {
        every { environment.getProperty("gemini.api.key") } returns null
        every { environment.getProperty("spring.ai.openai.api-key") } returns null
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns null
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "gemini"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.status shouldBe Status.DOWN
        health.details["configuredCount"] shouldBe 0
        health.details["reason"] shouldBe "No LLM provider API key is configured"
    }

    @Test
    fun `여러 프로바이더가 설정된 경우 UP`() {
        every { environment.getProperty("gemini.api.key") } returns "test-gemini-key"
        every { environment.getProperty("spring.ai.openai.api-key") } returns "test-openai-key"
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns "test-anthropic-key"
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "gemini"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["gemini"] shouldBe "configured"
        health.details["openai"] shouldBe "configured"
        health.details["anthropic"] shouldBe "configured"
        health.details["configuredCount"] shouldBe 3
    }

    @Test
    fun `UP when only openai은(는) configured이다`() {
        every { environment.getProperty("gemini.api.key") } returns null
        every { environment.getProperty("spring.ai.openai.api-key") } returns "sk-test"
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns null
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "openai"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["openai"] shouldBe "configured"
        health.details["defaultProvider"] shouldBe "openai"
        health.details["configuredCount"] shouldBe 1
    }

    @Test
    fun `빈 API key treated as not configured`() {
        every { environment.getProperty("gemini.api.key") } returns "   "
        every { environment.getProperty("spring.ai.openai.api-key") } returns ""
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns null
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "gemini"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.status shouldBe Status.DOWN
        health.details["gemini"] shouldBe "not configured"
        health.details["openai"] shouldBe "not configured"
        health.details["anthropic"] shouldBe "not configured"
        health.details["configuredCount"] shouldBe 0
    }

    @Test
    fun `details은(는) include default provider name`() {
        every { environment.getProperty("gemini.api.key") } returns "key"
        every { environment.getProperty("spring.ai.openai.api-key") } returns null
        every { environment.getProperty("spring.ai.anthropic.api-key") } returns null
        every { environment.getProperty("arc.reactor.llm.default-provider", "gemini") } returns "anthropic"

        val indicator = LlmProviderHealthIndicator(environment)
        val health = indicator.health()

        health.details["defaultProvider"] shouldBe "anthropic"
    }
}
