package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * OutputBoundaryEnforcer에 대한 테스트.
 *
 * 출력 경계값(최대 길이 초과 시 잘라내기, 최소 길이 미달 시 WARN/RETRY_ONCE/FAIL) 정책을 검증한다.
 */
class OutputBoundaryEnforcerTest {

    private val metrics = mockk<AgentMetrics>(relaxed = true)
    private val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")

    // ── 최대 길이 초과 (truncate) ──────────────────────────────────────────────

    @Nested
    inner class OutputMaxChars {

        @Test
        fun `최대 길이를 초과하면 잘라내기 후 접미사를 추가해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 5)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "1234567"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals(
                "12345${OutputBoundaryEnforcer.TRUNCATION_SUFFIX}",
                result?.content
            ) { "최대 길이 초과 시 잘라내기 접미사가 포함되어야 한다" }
        }

        @Test
        fun `최대 길이 초과 시 output_too_long 위반을 메트릭에 기록해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 5)

            enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "1234567"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            verify(exactly = 1) {
                metrics.recordBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_LONG, "truncate", 5, 7, any()
                )
            }
        }

        @Test
        fun `정확히 최대 길이이면 잘라내지 않아야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 5)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "12345"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals("12345", result?.content) { "정확히 최대 길이인 경우 잘라내지 않아야 한다" }
        }

        @Test
        fun `outputMaxChars가 0이면 최대 길이 검사를 건너뛰어야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 0)
            val longContent = "a".repeat(100_000)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = longContent),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals(longContent, result?.content) { "outputMaxChars=0 이면 잘라내기가 발생하면 안 된다" }
        }
    }

    // ── 최소 길이 미달 — WARN 모드 ──────────────────────────────────────────────

    @Nested
    inner class OutputMinCharsWarnMode {

        @Test
        fun `WARN 모드에서 최소 미달이면 원본 내용을 그대로 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.WARN)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertNotNull(result) { "WARN 모드에서는 null을 반환하면 안 된다" }
            assertEquals("short", result?.content) { "WARN 모드에서는 원본 내용을 그대로 반환해야 한다" }
        }

        @Test
        fun `WARN 모드에서 output_too_short warn 위반을 메트릭에 기록해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.WARN)

            enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            verify(exactly = 1) {
                metrics.recordBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT, "warn", 10, 5, any()
                )
            }
        }

        @Test
        fun `정확히 최소 길이이면 WARN 위반 없이 그대로 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 5, mode = OutputMinViolationMode.WARN)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "hello"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals("hello", result?.content) { "최소 길이와 정확히 같은 경우 위반이 발생하면 안 된다" }
            verify(exactly = 0) { metrics.recordBoundaryViolation(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `outputMinChars가 0이면 최소 길이 검사를 건너뛰어야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 0, mode = OutputMinViolationMode.WARN)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "x"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertNotNull(result) { "outputMinChars=0 이면 최소 길이 검사가 발생하면 안 된다" }
            assertEquals("x", result?.content) { "outputMinChars=0 이면 원본이 그대로 반환되어야 한다" }
        }
    }

    // ── 최소 길이 미달 — FAIL 모드 ──────────────────────────────────────────────

    @Nested
    inner class OutputMinCharsFailMode {

        @Test
        fun `FAIL 모드에서 최소 미달이면 null을 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.FAIL)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertNull(result) { "FAIL 모드에서 최소 미달 시 null을 반환해야 한다" }
        }

        @Test
        fun `FAIL 모드에서 output_too_short fail 위반을 메트릭에 기록해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.FAIL)

            enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            verify(exactly = 1) {
                metrics.recordBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT, "fail", 10, 5, any()
                )
            }
        }
    }

    // ── 최소 길이 미달 — RETRY_ONCE 모드 ────────────────────────────────────────

    @Nested
    inner class OutputMinCharsRetryOnceMode {

        @Test
        fun `RETRY_ONCE 모드에서 재시도 결과가 충분히 길면 재시도 내용을 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.RETRY_ONCE)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> "long enough response" }
            )

            assertTrue(result?.success == true) { "재시도 결과가 충분히 길면 성공이어야 한다" }
            assertEquals("long enough response", result?.content) { "재시도 결과가 반환되어야 한다" }
        }

        @Test
        fun `RETRY_ONCE 모드에서 재시도 결과도 짧으면 원본 내용을 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.RETRY_ONCE)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> "tiny" }
            )

            assertEquals("short", result?.content) { "재시도 결과도 짧으면 원본 내용을 반환해야 한다" }
        }

        @Test
        fun `RETRY_ONCE 모드에서 재시도 결과가 null이면 원본 내용을 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.RETRY_ONCE)

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals("short", result?.content) { "재시도 결과가 null이면 원본 내용을 반환해야 한다" }
        }

        @Test
        fun `RETRY_ONCE 모드에서 output_too_short retry_once 위반을 메트릭에 기록해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMinChars = 10, mode = OutputMinViolationMode.RETRY_ONCE)

            enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "short"),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> "long enough response" }
            )

            verify(exactly = 1) {
                metrics.recordBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT, "retry_once", 10, 5, any()
                )
            }
        }
    }

    // ── 경계값 없음 (비활성) ─────────────────────────────────────────────────────

    @Nested
    inner class NoBoundaryConfigured {

        @Test
        fun `경계값이 모두 비활성이면 원본 결과를 그대로 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 0, outputMinChars = 0)
            val original = AgentResult.success(content = "normal response")

            val result = enforcer.enforceOutputBoundaries(
                result = original,
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals(original, result) { "경계값이 비활성이면 원본 결과와 동일해야 한다" }
            verify(exactly = 0) { metrics.recordBoundaryViolation(any(), any(), any(), any(), any()) }
        }
    }

    // ── content가 null인 경우 ──────────────────────────────────────────────────

    @Nested
    inner class NullContent {

        @Test
        fun `content가 null이면 경계값 검사 없이 원본 결과를 반환해야 한다`() = runTest {
            val enforcer = enforcerWith(outputMaxChars = 5, outputMinChars = 10, mode = OutputMinViolationMode.FAIL)
            val resultWithNullContent = AgentResult(success = true, content = null)

            val result = enforcer.enforceOutputBoundaries(
                result = resultWithNullContent,
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertEquals(resultWithNullContent, result) { "content가 null이면 원본 결과를 그대로 반환해야 한다" }
            verify(exactly = 0) { metrics.recordBoundaryViolation(any(), any(), any(), any(), any()) }
        }
    }

    // ── 최대 초과 후 최소 미달 복합 시나리오 ──────────────────────────────────────
    //
    // TRUNCATION_SUFFIX("\n\n[Response truncated]")가 20자이므로 max 잘라내기 후 content는
    // 항상 최소 20자가 된다. outputMinChars를 그보다 크게 설정해야 복합 위반이 발생한다.

    @Nested
    inner class MaxThenMinViolation {

        @Test
        fun `잘라낸 결과가 최소 미달이면 FAIL 모드에서 null을 반환해야 한다`() = runTest {
            // 원본 100자 → 최대 1자 잘라내기 후 접미사 포함 21자 → 최소 50자 미달 → FAIL
            val enforcer = enforcerWith(
                outputMaxChars = 1,
                outputMinChars = 50,
                mode = OutputMinViolationMode.FAIL
            )

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "a".repeat(100)),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            assertNull(result) { "잘라낸 결과가 최소 미달이면 FAIL 모드에서 null이어야 한다" }
        }

        @Test
        fun `잘라낸 결과가 최소 미달이면 WARN 모드에서 잘라낸 내용을 반환해야 한다`() = runTest {
            // 원본 100자 → 최대 1자 잘라내기 후 접미사 포함 21자 → 최소 50자 미달이지만 WARN이므로 반환
            val enforcer = enforcerWith(
                outputMaxChars = 1,
                outputMinChars = 50,
                mode = OutputMinViolationMode.WARN
            )

            val result = enforcer.enforceOutputBoundaries(
                result = AgentResult.success(content = "a".repeat(100)),
                command = command,
                metadata = emptyMap(),
                attemptLongerResponse = { _, _, _ -> null }
            )

            val expected = "a${OutputBoundaryEnforcer.TRUNCATION_SUFFIX}"
            assertEquals(expected, result?.content) { "WARN 모드에서 잘라낸 내용이 반환되어야 한다" }
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    private fun enforcerWith(
        outputMaxChars: Int = 0,
        outputMinChars: Int = 0,
        mode: OutputMinViolationMode = OutputMinViolationMode.WARN
    ) = OutputBoundaryEnforcer(
        boundaries = BoundaryProperties(
            outputMaxChars = outputMaxChars,
            outputMinChars = outputMinChars,
            outputMinViolationMode = mode
        ),
        agentMetrics = metrics
    )
}
