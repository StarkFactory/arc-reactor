package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.ResolvedIntent
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PreExecutionResolverTest {

    @Test
    fun `should use anonymous userId when command userId is missing`() = runBlocking {
        val guard = mockk<RequestGuard>()
        coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT
        val resolver = PreExecutionResolver(
            guard = guard,
            hookExecutor = null,
            intentResolver = null,
            blockedIntents = emptySet(),
            agentMetrics = mockk(relaxed = true)
        )

        val rejection = resolver.checkGuard(AgentCommand(systemPrompt = "sys", userPrompt = "hi", userId = null))

        assertNull(rejection, "Guard should pass (null rejection) when guard returns Pass result")
        coVerify(exactly = 1) { guard.guard(match { it.userId == "anonymous" && it.text == "hi" }) }
    }

    @Test
    fun `should return GUARD_REJECTED result when guard rejects`() = runBlocking {
        val guard = mockk<RequestGuard>()
        coEvery { guard.guard(any()) } returns GuardResult.Rejected(
            reason = "blocked",
            category = RejectionCategory.INVALID_INPUT,
            stage = "InputValidation"
        )
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val resolver = PreExecutionResolver(
            guard = guard,
            hookExecutor = null,
            intentResolver = null,
            blockedIntents = emptySet(),
            agentMetrics = metrics,
            nowMs = { 1_500L }
        )

        val result = resolver.checkGuardAndHooks(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            startTime = 1_000L
        )

        assertEquals(AgentErrorCode.GUARD_REJECTED, result?.errorCode)
        assertEquals("blocked", result?.errorMessage)
        assertEquals(500L, result?.durationMs)
        verify(exactly = 1) { metrics.recordGuardRejection("InputValidation", "blocked", any()) }
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.GUARD_REJECTED }) }
    }

    @Test
    fun `should return HOOK_REJECTED result when before hook rejects`() = runBlocking {
        val hookExecutor = mockk<HookExecutor>()
        coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("hook blocked")
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val resolver = PreExecutionResolver(
            guard = null,
            hookExecutor = hookExecutor,
            intentResolver = null,
            blockedIntents = emptySet(),
            agentMetrics = metrics,
            nowMs = { 2_000L }
        )

        val result = resolver.checkGuardAndHooks(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            startTime = 1_000L
        )

        assertEquals(AgentErrorCode.HOOK_REJECTED, result?.errorCode)
        assertEquals("hook blocked", result?.errorMessage)
        assertEquals(1_000L, result?.durationMs)
    }

    @Test
    fun `resolveIntent should throw when intent is blocked`() = runBlocking {
        val intentResolver = mockk<IntentResolver>()
        coEvery { intentResolver.resolve(any(), any()) } returns ResolvedIntent(
            intentName = "finance",
            profile = IntentProfile(),
            result = IntentResult(
                primary = ClassifiedIntent("finance", 0.95),
                classifiedBy = "rule"
            )
        )
        val resolver = PreExecutionResolver(
            guard = null,
            hookExecutor = null,
            intentResolver = intentResolver,
            blockedIntents = setOf("finance"),
            agentMetrics = mockk(relaxed = true)
        )

        assertThrows(BlockedIntentException::class.java) {
            runBlocking {
                resolver.resolveIntent(AgentCommand(systemPrompt = "sys", userPrompt = "hi"))
            }
        }
    }

    @Test
    fun `resolveIntent should return original command when resolver throws`() = runBlocking {
        val intentResolver = mockk<IntentResolver>()
        coEvery { intentResolver.resolve(any(), any()) } throws RuntimeException("down")
        val resolver = PreExecutionResolver(
            guard = null,
            hookExecutor = null,
            intentResolver = intentResolver,
            blockedIntents = emptySet(),
            agentMetrics = mockk(relaxed = true)
        )
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")

        val resolved = resolver.resolveIntent(command)

        assertSame(command, resolved)
    }
}
