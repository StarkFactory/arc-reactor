package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.impl.OutputBlockPattern
import com.arc.reactor.guard.output.impl.PatternAction
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegexPatternOutputGuardTest {

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @Nested
    inner class MaskAction {

        @Test
        fun `matching pattern를 마스킹한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(
                        name = "Password",
                        pattern = "(?i)password\\s*[:=]\\s*\\S+",
                        action = PatternAction.MASK
                    )
                )
            )

            val result = guard.check("Your password: secret123", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask password pattern"
            }
            assertFalse(modified.content.contains("secret123")) {
                "Password value should be masked"
            }
            assertTrue(modified.content.contains("[REDACTED]")) {
                "Should contain [REDACTED] placeholder"
            }
        }

        @Test
        fun `없는 match returns Allowed`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(
                        name = "Password",
                        pattern = "(?i)password\\s*[:=]\\s*\\S+",
                        action = PatternAction.MASK
                    )
                )
            )

            val result = guard.check("This is clean content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "No match should return Allowed"
            }
        }

        @Test
        fun `multiple occurrences를 마스킹한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(
                        name = "Secret",
                        pattern = "SECRET_\\w+",
                        action = PatternAction.MASK
                    )
                )
            )

            val content = "Keys: SECRET_ABC and SECRET_XYZ"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask all occurrences"
            }
            assertFalse(modified.content.contains("SECRET_ABC")) { "First secret should be masked" }
            assertFalse(modified.content.contains("SECRET_XYZ")) { "Second secret should be masked" }
        }
    }

    @Nested
    inner class RejectAction {

        @Test
        fun `matching pattern를 거부한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(
                        name = "Internal Only",
                        pattern = "(?i)internal\\s+use\\s+only",
                        action = PatternAction.REJECT
                    )
                )
            )

            val result = guard.check("This document is for INTERNAL USE ONLY.", defaultContext)
            val rejected = assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "Should reject matching content"
            }
            assertTrue(rejected.reason.contains("Internal Only")) {
                "Rejection reason should include pattern name"
            }
            assertEquals(OutputRejectionCategory.POLICY_VIOLATION, rejected.category) {
                "Category should be POLICY_VIOLATION"
            }
        }
    }

    @Nested
    inner class MixedActions {

        @Test
        fun `takes priority over mask when reject pattern matches를 거부한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "MaskThis", pattern = "maskme", action = PatternAction.MASK),
                    OutputBlockPattern(name = "BlockThis", pattern = "blockme", action = PatternAction.REJECT)
                )
            )

            val result = guard.check("maskme and blockme", defaultContext)
            assertInstanceOf(OutputGuardResult.Rejected::class.java, result) {
                "REJECT should take priority when both match"
            }
        }

        @Test
        fun `applies when only mask pattern matches를 마스킹한다`() = runTest {
            val guard = RegexPatternOutputGuard(
                listOf(
                    OutputBlockPattern(name = "MaskThis", pattern = "maskme", action = PatternAction.MASK),
                    OutputBlockPattern(name = "BlockThis", pattern = "blockme", action = PatternAction.REJECT)
                )
            )

            val result = guard.check("only maskme here", defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask when only mask pattern matches"
            }
            assertTrue(modified.content.contains("[REDACTED]")) {
                "Should contain redacted placeholder"
            }
        }
    }

    @Nested
    inner class EmptyPatterns {

        @Test
        fun `없는 patterns always returns Allowed`() = runTest {
            val guard = RegexPatternOutputGuard(emptyList())
            val result = guard.check("any content", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Empty patterns should always allow"
            }
        }
    }

    @Nested
    inner class StageMetadata {

        @Test
        fun `stage name은(는) RegexPattern이다`() {
            val guard = RegexPatternOutputGuard(emptyList())
            assertEquals("RegexPattern", guard.stageName) { "Stage name should be RegexPattern" }
        }

        @Test
        fun `order은(는) 20이다`() {
            val guard = RegexPatternOutputGuard(emptyList())
            assertEquals(20, guard.order) { "Order should be 20" }
        }
    }
}
