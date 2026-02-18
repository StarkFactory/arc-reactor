package com.arc.reactor.agent

import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScenarioAssumptionValidationTest {

    private lateinit var fixture: AgentTestFixture
    private val baseProperties = AgentTestFixture.defaultProperties()
    private val noRetryProperties = baseProperties.copy(
        retry = baseProperties.retry.copy(
            maxAttempts = 1,
            initialDelayMs = 1,
            maxDelayMs = 1
        )
    )

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    private fun lineCommand(
        userPrompt: String,
        responseFormat: ResponseFormat = ResponseFormat.TEXT
    ): AgentCommand {
        return AgentCommand(
            systemPrompt = "You are an operations assistant.",
            userPrompt = userPrompt,
            userId = "scenario-user",
            responseFormat = responseFormat
        )
    }

    @Test
    fun `scenario 01 one-line greeting should succeed`() = runBlocking {
        fixture.mockCallResponse("안녕하세요. 무엇을 도와드릴까요?")
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties
        )

        val result = executor.execute(lineCommand("안녕"))

        result.assertSuccess()
        assertEquals("안녕하세요. 무엇을 도와드릴까요?", result.content)
    }

    @Test
    fun `scenario 02 policy violation line should be rejected by guard`() = runBlocking {
        val guard = mockk<RequestGuard>()
        coEvery { guard.guard(any()) } returns GuardResult.Rejected(
            reason = "policy blocked",
            category = RejectionCategory.INVALID_INPUT,
            stage = "inputValidation"
        )
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties,
            guard = guard
        )

        val result = executor.execute(lineCommand("rm -rf / 해줘"))

        result.assertFailure()
        result.assertErrorCode(AgentErrorCode.GUARD_REJECTED)
        assertEquals("policy blocked", result.errorMessage)
    }

    @Test
    fun `scenario 03 before hook rejection should return HOOK_REJECTED`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>()
        coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("maintenance window")
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties,
            hookExecutor = hookExecutor
        )

        val result = executor.execute(lineCommand("재고 동기화 실행해"))

        result.assertFailure()
        result.assertErrorCode(AgentErrorCode.HOOK_REJECTED)
        assertEquals("maintenance window", result.errorMessage)
    }

    @Test
    fun `scenario 04 timeout-like provider error should map to TIMEOUT`() = runBlocking {
        every { fixture.requestSpec.call() } throws RuntimeException("provider connection timeout")
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = noRetryProperties
        )

        val result = executor.execute(lineCommand("오늘 매출 요약해줘"))

        result.assertFailure()
        result.assertErrorCode(AgentErrorCode.TIMEOUT)
    }

    @Test
    fun `scenario 05 output guard sensitive-content reject should return OUTPUT_GUARD_REJECTED`() = runBlocking {
        val rejectingStage = object : OutputGuardStage {
            override val stageName = "PiiRejector"
            override val order = 1

            override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                return OutputGuardResult.Rejected(
                    reason = "PII detected",
                    category = OutputRejectionCategory.POLICY_VIOLATION
                )
            }
        }
        fixture.mockCallResponse("Customer SSN is 123-45-6789")
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties,
            outputGuardPipeline = OutputGuardPipeline(listOf(rejectingStage))
        )

        val result = executor.execute(lineCommand("민감정보 포함해서 답해줘"))

        result.assertFailure()
        result.assertErrorCode(AgentErrorCode.OUTPUT_GUARD_REJECTED)
        assertEquals("PII detected", result.errorMessage)
    }

    @Test
    fun `scenario 06 output minimum fail should return OUTPUT_TOO_SHORT`() = runBlocking {
        fixture.mockCallResponse("짧음")
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties.copy(
                boundaries = baseProperties.boundaries.copy(
                    outputMinChars = 12,
                    outputMinViolationMode = OutputMinViolationMode.FAIL
                )
            )
        )

        val result = executor.execute(lineCommand("한 줄로 아주 짧게만 답해"))

        result.assertFailure()
        result.assertErrorCode(AgentErrorCode.OUTPUT_TOO_SHORT)
    }

    @Test
    fun `scenario 07 structured format in streaming should emit INVALID_RESPONSE error`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties,
            agentMetrics = metrics
        )

        val chunks = executor.executeStream(
            lineCommand("json으로 응답해줘", responseFormat = ResponseFormat.JSON)
        ).toList()

        assertEquals(1, chunks.size)
        val parsed = StreamEventMarker.parse(chunks.first())
        assertNotNull(parsed)
        assertEquals("error", parsed?.first)
        assertTrue(parsed?.second?.contains("Structured JSON output is not supported in streaming mode") == true)
        verify(exactly = 1) {
            metrics.recordStreamingExecution(
                match { !it.success && it.errorCode == AgentErrorCode.INVALID_RESPONSE }
            )
        }
    }

    @Test
    fun `scenario 08 streaming guard rejection should emit GUARD_REJECTED metrics`() = runBlocking {
        val guard = mockk<RequestGuard>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        coEvery { guard.guard(any()) } returns GuardResult.Rejected(
            reason = "rate limit exceeded",
            category = RejectionCategory.RATE_LIMITED,
            stage = "rateLimit"
        )
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = baseProperties,
            guard = guard,
            agentMetrics = metrics
        )

        val chunks = executor.executeStream(lineCommand("같은 요청 반복")).toList()

        assertEquals(1, chunks.size)
        val parsed = StreamEventMarker.parse(chunks.first())
        assertEquals("error", parsed?.first)
        assertEquals("rate limit exceeded", parsed?.second)
        verify(exactly = 1) {
            metrics.recordStreamingExecution(
                match {
                    !it.success &&
                        it.errorCode == AgentErrorCode.GUARD_REJECTED &&
                        it.errorMessage == "rate limit exceeded"
                }
            )
        }
    }
}
