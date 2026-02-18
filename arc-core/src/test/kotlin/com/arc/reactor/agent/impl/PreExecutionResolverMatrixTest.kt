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
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@Tag("matrix")
class PreExecutionResolverMatrixTest {

    @Test
    fun `guard rejection should short-circuit hook across 100 cases`() = runBlocking {
        val guard = mockk<RequestGuard>()
        val hookExecutor = mockk<HookExecutor>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        var index = 0

        coEvery { guard.guard(any()) } coAnswers {
            val i = index++
            GuardResult.Rejected(
                reason = "blocked-$i",
                category = RejectionCategory.INVALID_INPUT,
                stage = if (i % 2 == 0) null else "stage-$i"
            )
        }
        coEvery { hookExecutor.executeBeforeAgentStart(any()) } returns HookResult.Reject("hook blocked")

        val resolver = PreExecutionResolver(
            guard = guard,
            hookExecutor = hookExecutor,
            intentResolver = null,
            blockedIntents = emptySet(),
            agentMetrics = metrics,
            nowMs = { 2_000L }
        )

        repeat(100) { i ->
            val result = resolver.checkGuardAndHooks(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "line-$i"),
                hookContext = HookContext(runId = "run-$i", userId = "u", userPrompt = "line-$i"),
                startTime = 1_000L
            )

            assertEquals(AgentErrorCode.GUARD_REJECTED, result?.errorCode)
            assertEquals("blocked-$i", result?.errorMessage)
            assertEquals(1_000L, result?.durationMs)
        }

        coVerify(exactly = 100) { guard.guard(any()) }
        coVerify(exactly = 0) { hookExecutor.executeBeforeAgentStart(any()) }
    }

    @Test
    fun `hook rejection should apply when guard allows across 120 cases`() = runBlocking {
        val guard = mockk<RequestGuard>()
        val hookExecutor = mockk<HookExecutor>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        var index = 0

        coEvery { guard.guard(any()) } returns GuardResult.Allowed.DEFAULT
        coEvery { hookExecutor.executeBeforeAgentStart(any()) } coAnswers {
            HookResult.Reject("hook-reject-${index++}")
        }

        val resolver = PreExecutionResolver(
            guard = guard,
            hookExecutor = hookExecutor,
            intentResolver = null,
            blockedIntents = emptySet(),
            agentMetrics = metrics,
            nowMs = { 5_000L }
        )

        repeat(120) { i ->
            val result = resolver.checkGuardAndHooks(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hook-$i"),
                hookContext = HookContext(runId = "run-$i", userId = "u", userPrompt = "hook-$i"),
                startTime = 4_000L
            )

            assertEquals(AgentErrorCode.HOOK_REJECTED, result?.errorCode)
            assertEquals("hook-reject-$i", result?.errorMessage)
            assertEquals(1_000L, result?.durationMs)
        }

        coVerify(exactly = 120) { guard.guard(any()) }
        coVerify(exactly = 120) { hookExecutor.executeBeforeAgentStart(any()) }
    }

    @Test
    fun `resolveIntent should fail-open to original command across 150 resolver errors`() = runBlocking {
        val resolverMock = mockk<IntentResolver>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        var index = 0
        coEvery { resolverMock.resolve(any(), any()) } coAnswers {
            throw RuntimeException("classifier-failure-${index++}")
        }

        val resolver = PreExecutionResolver(
            guard = null,
            hookExecutor = null,
            intentResolver = resolverMock,
            blockedIntents = emptySet(),
            agentMetrics = metrics
        )

        repeat(150) { i ->
            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "intent-$i",
                metadata = mapOf("channel" to if (i % 2 == 0) "web" else i)
            )
            val resolved = resolver.resolveIntent(command)
            assertSame(command, resolved)
        }

        coVerify(exactly = 150) { resolverMock.resolve(any(), any()) }
    }

    @Test
    fun `resolveIntent should throw blocked exception across blocked intent matrix`() = runBlocking {
        val resolverMock = mockk<IntentResolver>()
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val blockedNames = (0 until 40).map { "blocked-$it" }.toSet()
        var index = 0

        coEvery { resolverMock.resolve(any(), any()) } coAnswers {
            val name = "blocked-${index++ % 40}"
            ResolvedIntent(
                intentName = name,
                profile = IntentProfile(),
                result = IntentResult(
                    primary = ClassifiedIntent(name, 0.95),
                    classifiedBy = "rule"
                )
            )
        }
        every { resolverMock.applyProfile(any(), any()) } answers { firstArg() }

        val resolver = PreExecutionResolver(
            guard = null,
            hookExecutor = null,
            intentResolver = resolverMock,
            blockedIntents = blockedNames,
            agentMetrics = metrics
        )

        repeat(80) { i ->
            val ex = assertThrows(BlockedIntentException::class.java) {
                runBlocking {
                    resolver.resolveIntent(
                        AgentCommand(
                            systemPrompt = "sys",
                            userPrompt = "query-$i",
                            metadata = mapOf("channel" to "web")
                        )
                    )
                }
            }
            assertEquals("blocked-${i % 40}", ex.intentName)
        }

        coVerify(exactly = 80) {
            resolverMock.resolve(any(), any<ClassificationContext>())
        }
        coVerify(exactly = 0) { resolverMock.applyProfile(any(), any()) }
    }
}
