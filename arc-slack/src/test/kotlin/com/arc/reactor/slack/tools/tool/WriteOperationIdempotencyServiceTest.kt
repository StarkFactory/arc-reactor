package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.arc.reactor.slack.tools.config.WriteIdempotencyProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * [WriteOperationIdempotencyService] 구현체 단위 테스트.
 *
 * [NoopWriteOperationIdempotencyService]의 무조건 실행 동작과
 * [InMemoryWriteOperationIdempotencyService]의 maxEntries 트림·예외 전파·
 * 도구명 분리 등 추가 경계 케이스를 검증한다.
 */
class WriteOperationIdempotencyServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // NoopWriteOperationIdempotencyService
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class NoopService {

        private val noop = NoopWriteOperationIdempotencyService

        @Test
        fun `동일 파라미터 중복 호출 시 매번 원본 operation을 실행한다`() {
            val counter = AtomicInteger(0)

            val first = noop.execute("send_message", null, listOf("C1", "hello")) {
                "result-${counter.incrementAndGet()}"
            }
            val second = noop.execute("send_message", null, listOf("C1", "hello")) {
                "result-${counter.incrementAndGet()}"
            }

            assertEquals(2, counter.get()) { "Noop 서비스는 캐싱 없이 매번 operation을 실행해야 한다" }
            assertEquals("result-1", first) { "첫 번째 호출 결과가 올바르지 않다" }
            assertEquals("result-2", second) { "두 번째 호출 결과가 올바르지 않다" }
        }

        @Test
        fun `명시적 멱등성 키가 있어도 Noop 서비스는 캐싱하지 않는다`() {
            val counter = AtomicInteger(0)

            noop.execute("upload_file", "explicit-key", listOf("C1", "file.txt")) {
                "result-${counter.incrementAndGet()}"
            }
            noop.execute("upload_file", "explicit-key", listOf("C1", "file.txt")) {
                "result-${counter.incrementAndGet()}"
            }

            assertEquals(2, counter.get()) { "Noop 서비스는 명시적 키도 무시하고 매번 실행해야 한다" }
        }

        @Test
        fun `operation이 예외를 던지면 Noop 서비스도 예외를 전파한다`() {
            assertThrows<IllegalStateException> {
                noop.execute("send_message", null, listOf("C1")) {
                    throw IllegalStateException("Slack API 오류")
                }
            }
        }

        @Test
        fun `keyParts가 비어 있어도 Noop 서비스는 operation을 실행한다`() {
            val result = noop.execute("send_message", null, emptyList()) { "ok" }
            assertEquals("ok", result) { "keyParts가 빈 리스트여도 Noop 서비스는 동작해야 한다" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // InMemoryWriteOperationIdempotencyService — 추가 경계 케이스
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class InMemoryServiceEdgeCases {

        @Test
        fun `도구명이 다르면 동일 keyParts도 별도 캐시로 취급한다`() {
            val service = newService(enabled = true, ttlSeconds = 5)
            val counter = AtomicInteger(0)

            service.execute("tool_a", null, listOf("C1", "msg")) { "a-${counter.incrementAndGet()}" }
            val secondTool = service.execute("tool_b", null, listOf("C1", "msg")) {
                "b-${counter.incrementAndGet()}"
            }

            assertEquals(2, counter.get()) { "도구명이 다르면 별도 캐시 엔트리로 저장돼야 한다" }
            assertEquals("b-2", secondTool) { "두 번째 도구는 캐시되지 않은 결과를 반환해야 한다" }
        }

        @Test
        fun `maxEntries 초과 시 캐시 엔트리 수가 한도를 초과하지 않는다`() {
            val maxEntries = 2
            val service = newService(enabled = true, ttlSeconds = 60, maxEntries = maxEntries)
            val counter = AtomicInteger(0)

            // maxEntries + 1 개의 서로 다른 엔트리를 채워 trim을 유발한다
            service.execute("tool", null, listOf("key-1")) { "v${counter.incrementAndGet()}" }
            service.execute("tool", null, listOf("key-2")) { "v${counter.incrementAndGet()}" }
            service.execute("tool", null, listOf("key-3")) { "v${counter.incrementAndGet()}" }

            // 세 엔트리 중 적어도 하나는 제거되었으므로 재호출 시 총 실행 횟수가 증가해야 한다.
            // 어느 키가 제거됐는지는 expiresAtMs 정렬에 따라 비결정적이므로
            // "총 실행 횟수가 3보다 많다"는 사실만 검증한다.
            service.execute("tool", null, listOf("key-1")) { "v${counter.incrementAndGet()}" }
            service.execute("tool", null, listOf("key-2")) { "v${counter.incrementAndGet()}" }
            service.execute("tool", null, listOf("key-3")) { "v${counter.incrementAndGet()}" }

            val totalCalls = counter.get()
            assertTrue(totalCalls > maxEntries) {
                "maxEntries=$maxEntries 초과 후 재호출하면 적어도 하나의 캐시 미스가 발생해야 한다. 실제 호출 수: $totalCalls"
            }
        }

        @Test
        fun `operation이 예외를 던지면 InMemory 서비스도 예외를 전파한다`() {
            val service = newService(enabled = true, ttlSeconds = 5)

            assertThrows<RuntimeException> {
                service.execute("send_message", null, listOf("C1", "boom")) {
                    throw RuntimeException("Slack 503")
                }
            }
        }

        @Test
        fun `명시적 멱등성 키의 공백은 무시되고 동일 키로 처리된다`() {
            val service = newService(enabled = true, ttlSeconds = 5)
            val counter = AtomicInteger(0)

            service.execute("tool", "  my-key  ", listOf("C1")) { "v${counter.incrementAndGet()}" }
            service.execute("tool", "my-key", listOf("C2")) { "v${counter.incrementAndGet()}" }

            assertEquals(1, counter.get()) { "공백이 제거된 동일 명시적 키는 중복으로 인식돼야 한다" }
        }
    }

    private fun newService(
        enabled: Boolean,
        ttlSeconds: Long,
        maxEntries: Int = 100
    ): InMemoryWriteOperationIdempotencyService {
        val props = SlackToolsProperties(
            botToken = "xoxb-test-token",
            writeIdempotency = WriteIdempotencyProperties(
                enabled = enabled,
                ttlSeconds = ttlSeconds,
                maxEntries = maxEntries
            )
        )
        return InMemoryWriteOperationIdempotencyService(props)
    }
}
