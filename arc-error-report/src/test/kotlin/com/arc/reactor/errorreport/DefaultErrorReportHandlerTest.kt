package com.arc.reactor.errorreport

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.handler.DefaultErrorReportHandler
import com.arc.reactor.errorreport.model.ErrorReportRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultErrorReportHandlerTest {

    private val agentExecutor = mockk<AgentExecutor>()
    private val properties = ErrorReportProperties(
        enabled = true,
        maxToolCalls = 25,
        requestTimeoutMs = 120_000
    )
    private val handler = DefaultErrorReportHandler(agentExecutor, properties)

    private fun request(
        stackTrace: String = "java.lang.NullPointerException\n\tat com.example.Service.run(Service.kt:42)",
        serviceName: String = "my-service",
        repoSlug: String = "my-org/my-service",
        slackChannel: String = "#error-alerts",
        environment: String? = "production",
        timestamp: String? = "2025-01-01T00:00:00Z"
    ) = ErrorReportRequest(
        stackTrace = stackTrace,
        serviceName = serviceName,
        repoSlug = repoSlug,
        slackChannel = slackChannel,
        environment = environment,
        timestamp = timestamp
    )

    @Nested
    inner class AgentCommandConstruction {

        @Test
        fun `user prompt contains all request fields`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            handler.handle("req-1", request())

            val prompt = commandSlot.captured.userPrompt
            prompt shouldContain "my-service"
            prompt shouldContain "my-org/my-service"
            prompt shouldContain "#error-alerts"
            prompt shouldContain "production"
            prompt shouldContain "2025-01-01T00:00:00Z"
            prompt shouldContain "NullPointerException"
        }

        @Test
        fun `system prompt contains key instructions`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            handler.handle("req-1", request())

            val systemPrompt = commandSlot.captured.systemPrompt
            systemPrompt shouldContain "Bitbucket"
            systemPrompt shouldContain "repo_load"
            systemPrompt shouldContain "error_analyze"
            systemPrompt shouldContain "Jira"
            systemPrompt shouldContain "Slack"
        }

        @Test
        fun `maxToolCalls comes from properties`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            handler.handle("req-1", request())

            commandSlot.captured.maxToolCalls shouldBe 25
        }

        @Test
        fun `metadata includes source and requestId`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            handler.handle("req-123", request())

            commandSlot.captured.metadata["source"] shouldBe "error-report"
            commandSlot.captured.metadata["requestId"] shouldBe "req-123"
            commandSlot.captured.metadata["serviceName"] shouldBe "my-service"
        }

        @Test
        fun `optional fields are omitted from prompt when null`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            handler.handle("req-1", request(environment = null, timestamp = null))

            val prompt = commandSlot.captured.userPrompt
            prompt shouldContain "my-service"
            // Should not contain "Environment:" or "Timestamp:" lines
            assert(!prompt.contains("Environment:"))
            assert(!prompt.contains("Timestamp:"))
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `does not throw when agent returns failure`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } returns
                AgentResult.failure("LLM error", AgentErrorCode.UNKNOWN)

            // Should not throw
            handler.handle("req-1", request())

            coVerify(exactly = 1) { agentExecutor.execute(any<AgentCommand>()) }
        }

        @Test
        fun `does not throw when executor throws exception`() = runTest {
            coEvery { agentExecutor.execute(any<AgentCommand>()) } throws RuntimeException("LLM down")

            // Should not throw — exception is caught and logged
            handler.handle("req-1", request())
        }
    }
}
