package com.arc.reactor.slack.tools.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SlackToolsProperties] 유효성 검증 테스트.
 *
 * enabled=true 상태에서 각 필수 프로퍼티 누락 또는 잘못된 값 입력 시
 * [IllegalArgumentException]이 발생하는지, enabled=false 시 검증을 건너뛰는지 검증한다.
 */
class SlackToolsPropertiesTest {

    /** 최소한의 유효한 프로퍼티 기본값을 반환한다. */
    private fun validProperties() = SlackToolsProperties(
        enabled = true,
        botToken = "xoxb-valid-token",
        writeIdempotency = WriteIdempotencyProperties(enabled = true, ttlSeconds = 30, maxEntries = 5000),
        resilience = ResilienceProperties(
            timeoutMs = 5_000,
            circuitBreaker = CircuitBreakerProperties(
                enabled = true,
                failureThreshold = 3,
                openStateDurationMs = 30_000
            )
        ),
        canvas = CanvasToolsProperties(enabled = false, maxOwnedCanvasIds = 5000)
    )

    @Nested
    inner class `비활성 상태` {

        @Test
        fun `disabled일 때 botToken이 빈 문자열이어도 validate는 통과한다`() {
            val props = SlackToolsProperties(enabled = false, botToken = "")

            assertDoesNotThrow({ props.validate() }) {
                "enabled=false 일 때는 모든 프로퍼티 검증을 건너뛰어야 한다"
            }
        }

        @Test
        fun `disabled일 때 ttlSeconds가 0이어도 validate는 통과한다`() {
            val props = SlackToolsProperties(
                enabled = false,
                botToken = "",
                writeIdempotency = WriteIdempotencyProperties(ttlSeconds = 0)
            )

            assertDoesNotThrow({ props.validate() }) {
                "enabled=false 일 때는 ttlSeconds 검증을 건너뛰어야 한다"
            }
        }
    }

    @Nested
    inner class `botToken 검증` {

        @Test
        fun `botToken이 비어있으면 예외 없이 조기 반환한다`() {
            val props = validProperties().copy(botToken = "")
            assertDoesNotThrow({ props.validate() }) {
                "botToken이 비어있으면 경고만 출력하고 예외 없이 반환해야 한다"
            }
        }

        @Test
        fun `botToken이 공백만 있으면 예외 없이 조기 반환한다`() {
            val props = validProperties().copy(botToken = "   ")
            assertDoesNotThrow({ props.validate() }) {
                "공백 전용 botToken도 경고만 출력하고 예외 없이 반환해야 한다"
            }
        }

        @Test
        fun `유효한 botToken이면 validate가 통과한다`() {
            assertDoesNotThrow({ validProperties().validate() }) {
                "올바른 botToken으로 validate가 예외 없이 완료되어야 한다"
            }
        }
    }

    @Nested
    inner class `writeIdempotency 검증` {

        @Test
        fun `ttlSeconds가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                writeIdempotency = WriteIdempotencyProperties(ttlSeconds = 0)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("ttl-seconds") == true) {
                "예외 메시지에 ttl-seconds 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `ttlSeconds가 음수이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                writeIdempotency = WriteIdempotencyProperties(ttlSeconds = -1)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("ttl-seconds") == true) {
                "음수 ttlSeconds도 유효하지 않아야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `maxEntries가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                writeIdempotency = WriteIdempotencyProperties(ttlSeconds = 30, maxEntries = 0)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("max-entries") == true) {
                "예외 메시지에 max-entries 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `maxEntries가 음수이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                writeIdempotency = WriteIdempotencyProperties(ttlSeconds = 30, maxEntries = -10)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("max-entries") == true) {
                "음수 maxEntries도 유효하지 않아야 한다. actual: ${ex.message}"
            }
        }
    }

    @Nested
    inner class `resilience 검증` {

        @Test
        fun `timeoutMs가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                resilience = ResilienceProperties(timeoutMs = 0)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("timeout-ms") == true) {
                "예외 메시지에 timeout-ms 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `failureThreshold가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                resilience = ResilienceProperties(
                    timeoutMs = 5_000,
                    circuitBreaker = CircuitBreakerProperties(failureThreshold = 0)
                )
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("failure-threshold") == true) {
                "예외 메시지에 failure-threshold 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `openStateDurationMs가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                resilience = ResilienceProperties(
                    timeoutMs = 5_000,
                    circuitBreaker = CircuitBreakerProperties(failureThreshold = 3, openStateDurationMs = 0)
                )
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("open-state-duration-ms") == true) {
                "예외 메시지에 open-state-duration-ms 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }
    }

    @Nested
    inner class `canvas 검증` {

        @Test
        fun `maxOwnedCanvasIds가 0이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                canvas = CanvasToolsProperties(enabled = true, maxOwnedCanvasIds = 0)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("max-owned-canvas-ids") == true) {
                "예외 메시지에 max-owned-canvas-ids 안내가 포함되어야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `maxOwnedCanvasIds가 음수이면 IllegalArgumentException이 발생한다`() {
            val props = validProperties().copy(
                canvas = CanvasToolsProperties(enabled = true, maxOwnedCanvasIds = -5)
            )

            val ex = assertThrows(IllegalArgumentException::class.java) { props.validate() }
            assertTrue(ex.message?.contains("max-owned-canvas-ids") == true) {
                "음수 maxOwnedCanvasIds도 유효하지 않아야 한다. actual: ${ex.message}"
            }
        }

        @Test
        fun `maxOwnedCanvasIds가 1 이상이면 validate가 통과한다`() {
            val props = validProperties().copy(
                canvas = CanvasToolsProperties(enabled = true, maxOwnedCanvasIds = 1)
            )

            assertDoesNotThrow({ props.validate() }) {
                "maxOwnedCanvasIds=1 은 최솟값이므로 유효해야 한다"
            }
        }
    }

    @Nested
    inner class `기본값 검증` {

        @Test
        fun `기본 WriteIdempotencyProperties는 유효한 값을 갖는다`() {
            val defaults = WriteIdempotencyProperties()

            assertTrue(defaults.ttlSeconds > 0) {
                "기본 ttlSeconds는 0보다 커야 한다. actual: ${defaults.ttlSeconds}"
            }
            assertTrue(defaults.maxEntries > 0) {
                "기본 maxEntries는 0보다 커야 한다. actual: ${defaults.maxEntries}"
            }
        }

        @Test
        fun `기본 ResilienceProperties는 유효한 값을 갖는다`() {
            val defaults = ResilienceProperties()

            assertTrue(defaults.timeoutMs > 0) {
                "기본 timeoutMs는 0보다 커야 한다. actual: ${defaults.timeoutMs}"
            }
            assertTrue(defaults.circuitBreaker.failureThreshold > 0) {
                "기본 failureThreshold는 0보다 커야 한다. actual: ${defaults.circuitBreaker.failureThreshold}"
            }
            assertTrue(defaults.circuitBreaker.openStateDurationMs > 0) {
                "기본 openStateDurationMs는 0보다 커야 한다. actual: ${defaults.circuitBreaker.openStateDurationMs}"
            }
        }

        @Test
        fun `기본 CanvasToolsProperties는 유효한 maxOwnedCanvasIds를 갖는다`() {
            val defaults = CanvasToolsProperties()

            assertTrue(defaults.maxOwnedCanvasIds > 0) {
                "기본 maxOwnedCanvasIds는 0보다 커야 한다. actual: ${defaults.maxOwnedCanvasIds}"
            }
        }
    }
}
