package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.arc.reactor.slack.tools.config.WriteIdempotencyProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class InMemoryWriteOperationIdempotencyServiceTest {

    @Test
    fun `replays cached result for duplicate write payload`() {
        val service = newService(enabled = true, ttlSeconds = 5)
        val counter = AtomicInteger(0)

        val first = service.execute(
            toolName = "send_message",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "hello", "")
        ) { "result-${counter.incrementAndGet()}" }

        val second = service.execute(
            toolName = "send_message",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "hello", "")
        ) { "result-${counter.incrementAndGet()}" }

        assertEquals("result-1", first)
        assertEquals("result-1", second)
        assertEquals(1, counter.get())
    }

    @Test
    fun `explicit idempotency key deduplicates even when payload differs`() {
        val service = newService(enabled = true, ttlSeconds = 5)
        val counter = AtomicInteger(0)

        val first = service.execute(
            toolName = "upload_file",
            explicitIdempotencyKey = "same-key",
            keyParts = listOf("C123", "report-a.txt")
        ) { "result-${counter.incrementAndGet()}" }

        val second = service.execute(
            toolName = "upload_file",
            explicitIdempotencyKey = "same-key",
            keyParts = listOf("C123", "report-b.txt")
        ) { "result-${counter.incrementAndGet()}" }

        assertEquals("result-1", first)
        assertEquals("result-1", second)
        assertEquals(1, counter.get())
    }

    @Test
    fun `expired cache entry allows a fresh write`() {
        val service = newService(enabled = true, ttlSeconds = 1)
        val counter = AtomicInteger(0)

        val first = service.execute(
            toolName = "add_reaction",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "1234.5678", "thumbsup")
        ) { "result-${counter.incrementAndGet()}" }

        Thread.sleep(1_100)

        val second = service.execute(
            toolName = "add_reaction",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "1234.5678", "thumbsup")
        ) { "result-${counter.incrementAndGet()}" }

        assertEquals("result-1", first)
        assertEquals("result-2", second)
        assertEquals(2, counter.get())
    }

    @Test
    fun `disabled mode always executes operation`() {
        val service = newService(enabled = false, ttlSeconds = 5)
        val counter = AtomicInteger(0)

        val first = service.execute(
            toolName = "send_message",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "hello", "")
        ) { "result-${counter.incrementAndGet()}" }

        val second = service.execute(
            toolName = "send_message",
            explicitIdempotencyKey = null,
            keyParts = listOf("C123", "hello", "")
        ) { "result-${counter.incrementAndGet()}" }

        assertEquals("result-1", first)
        assertEquals("result-2", second)
        assertEquals(2, counter.get())
    }

    private fun newService(enabled: Boolean, ttlSeconds: Long): InMemoryWriteOperationIdempotencyService {
        val props = SlackToolsProperties(
            botToken = "xoxb-test-token",
            writeIdempotency = WriteIdempotencyProperties(
                enabled = enabled,
                ttlSeconds = ttlSeconds,
                maxEntries = 100
            )
        )
        return InMemoryWriteOperationIdempotencyService(props)
    }
}
