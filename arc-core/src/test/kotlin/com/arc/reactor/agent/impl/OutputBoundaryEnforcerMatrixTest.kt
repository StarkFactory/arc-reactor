package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@Tag("matrix")
class OutputBoundaryEnforcerMatrixTest {

    private val command = AgentCommand(systemPrompt = "sys", userPrompt = "line")

    @Test
    fun `fail mode should reject all outputs below minimum across wide length matrix`() = runBlocking {
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(
                outputMinChars = 60,
                outputMinViolationMode = OutputMinViolationMode.FAIL
            ),
            agentMetrics = mockk<AgentMetrics>(relaxed = true)
        )

        var checked = 0
        for (len in 0..120) {
            val result = enforcer.apply(
                result = AgentResult.success("x".repeat(len)),
                command = command,
                attemptLongerResponse = { _, _, _ -> null }
            )
            if (len < 60) assertNull(result, "len=$len should fail")
            else assertNotNull(result, "len=$len should pass")
            checked++
        }
        assertEquals(121, checked)
    }

    @Test
    fun `warn mode should preserve non-truncated text and truncate over max across matrix`() = runBlocking {
        val max = 80
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(
                outputMaxChars = max,
                outputMinChars = 40,
                outputMinViolationMode = OutputMinViolationMode.WARN
            ),
            agentMetrics = mockk<AgentMetrics>(relaxed = true)
        )

        var checked = 0
        for (len in 0..160) {
            val source = "a".repeat(len)
            val result = enforcer.apply(
                result = AgentResult.success(source),
                command = command,
                attemptLongerResponse = { _, _, _ -> null }
            )
            assertNotNull(result)
            val content = result?.content.orEmpty()
            if (len > max) {
                assertEquals(source.take(max) + "\n\n[Response truncated]", content, "len=$len")
            } else {
                assertEquals(source, content, "len=$len")
            }
            checked++
        }
        assertEquals(161, checked)
    }

    @Test
    fun `retry once should upgrade short outputs when retry meets minimum across matrix`() = runBlocking {
        val min = 50
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(
                outputMinChars = min,
                outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE
            ),
            agentMetrics = mockk<AgentMetrics>(relaxed = true)
        )

        var retryCalls = 0
        var checked = 0
        for (len in 0..100) {
            val source = "b".repeat(len)
            val result = enforcer.apply(
                result = AgentResult.success(source),
                command = command,
                attemptLongerResponse = { _, requiredMin, _ ->
                    retryCalls++
                    "R".repeat(requiredMin)
                }
            )

            assertNotNull(result)
            val content = result?.content.orEmpty()
            if (len < min) {
                assertEquals(min, content.length, "len=$len should be upgraded by retry")
            } else {
                assertEquals(source, content, "len=$len should remain unchanged")
            }
            checked++
        }
        assertEquals(101, checked)
        assertEquals(min, retryCalls) // len 0..49
    }

    @Test
    fun `retry once should keep original when retry output is still short`() = runBlocking {
        val min = 40
        val enforcer = OutputBoundaryEnforcer(
            boundaries = BoundaryProperties(
                outputMinChars = min,
                outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE
            ),
            agentMetrics = mockk<AgentMetrics>(relaxed = true)
        )

        var checked = 0
        for (len in 0..39) {
            val source = "z".repeat(len)
            val result = enforcer.apply(
                result = AgentResult.success(source),
                command = command,
                attemptLongerResponse = { _, _, _ -> "tiny" }
            )

            assertNotNull(result)
            assertEquals(source, result?.content, "len=$len should remain original when retry fails")
            checked++
        }
        assertEquals(40, checked)
    }
}
