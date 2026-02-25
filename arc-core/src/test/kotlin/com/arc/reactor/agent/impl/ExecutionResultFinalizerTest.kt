package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.ResponseFilterContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ExecutionResultFinalizerTest {

    @Test
    fun `should apply response filter then save history and call hook on success`() = runBlocking {
        val chain = ResponseFilterChain(listOf(object : ResponseFilter {
            override suspend fun filter(content: String, context: ResponseFilterContext): String = "$content!"
        }))
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = chain,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        val result = finalizer.finalize(
            result = AgentResult.success(content = "hello"),
            command = command,
            hookContext = hookContext,
            toolsUsed = listOf("search"),
            startTime = 1_000L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success)
        assertEquals("hello!", result.content)
        coVerify(exactly = 1) {
            conversationManager.saveHistory(command, match { it.success && it.content == "hello!" })
        }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                context = hookContext,
                response = match {
                    it.success && it.response == "hello!" && it.errorMessage == null && it.toolsUsed == listOf("search")
                }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(match { it.success && it.content == "hello!" }) }
    }

    @Test
    fun `should return OUTPUT_GUARD_REJECTED when output guard rejects`() = runBlocking {
        val rejectingStage = object : OutputGuardStage {
            override val stageName = "RejectingStage"
            override val order = 1
            override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                return OutputGuardResult.Rejected(
                    reason = "blocked",
                    category = OutputRejectionCategory.POLICY_VIOLATION
                )
            }
        }
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = OutputGuardPipeline(listOf(rejectingStage)),
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        val result = finalizer.finalize(
            result = AgentResult.success(content = "sensitive"),
            command = command,
            hookContext = hookContext,
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success)
        assertEquals(AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode)
        coVerify(exactly = 0) { conversationManager.saveHistory(command, match { true }) }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                hookContext,
                match { !it.success && it.errorCode == "OUTPUT_GUARD_REJECTED" }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED }) }
    }

    @Test
    fun `should return OUTPUT_TOO_SHORT when boundary mode is FAIL`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.FAIL),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success)
        assertEquals(AgentErrorCode.OUTPUT_TOO_SHORT, result.errorCode)
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT }) }
    }

    @Test
    fun `should retry once for short response when boundary mode is RETRY_ONCE`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> "long enough response" }
        )

        assertTrue(result.success)
        assertEquals("long enough response", result.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "retry_once", 10, 5) }
        verify(exactly = 1) { metrics.recordExecution(match { it.success && it.content == "long enough response" }) }
    }

    @Test
    fun `should rethrow cancellation from after hook`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>()
        coEvery { hookExecutor.executeAfterAgentComplete(any(), any()) } throws CancellationException("cancelled")
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        try {
            finalizer.finalize(
                result = AgentResult.success(content = "hello"),
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = emptyList(),
                startTime = 500L,
                attemptLongerResponse = { _, _, _ -> null }
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
