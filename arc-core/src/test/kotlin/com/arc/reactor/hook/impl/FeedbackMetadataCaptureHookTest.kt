package com.arc.reactor.hook.impl

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FeedbackMetadataCaptureHookTest {

    private val baseTime = Instant.parse("2026-02-16T12:00:00Z")
    private lateinit var clock: MutableClock
    private lateinit var hook: FeedbackMetadataCaptureHook

    @BeforeEach
    fun setup() {
        clock = MutableClock(baseTime)
        hook = FeedbackMetadataCaptureHook(clock = clock)
    }

    private fun createContext(
        runId: String = "run-1",
        userId: String = "user-1",
        userPrompt: String = "Hello",
        channel: String? = "web",
        sessionId: String? = null
    ): HookContext {
        val context = HookContext(
            runId = runId,
            userId = userId,
            userPrompt = userPrompt,
            channel = channel,
            startedAt = Instant.now(clock)
        )
        sessionId?.let { context.metadata["sessionId"] = it }
        return context
    }

    private fun createResponse(
        content: String = "Agent response",
        toolsUsed: List<String> = listOf("calculator"),
        durationMs: Long = 500
    ): AgentResponse = AgentResponse(
        success = true,
        response = content,
        toolsUsed = toolsUsed,
        totalDurationMs = durationMs
    )

    @Nested
    inner class HookProperties {

        @Test
        fun `should have order 250 for late hook execution`() {
            assertEquals(250, hook.order) { "Hook should have order 250 (after webhooks at 200)" }
        }

        @Test
        fun `should be fail-open`() {
            assertFalse(hook.failOnError) { "Hook should never block the agent on failure" }
        }
    }

    @Nested
    inner class MetadataCapture {

        @Test
        fun `should capture and retrieve metadata by runId`() = runTest {
            val context = createContext(runId = "run-42", userId = "user-7", userPrompt = "What is AI?")
            val response = createResponse(
                content = "AI is artificial intelligence.",
                toolsUsed = listOf("search", "calculator"),
                durationMs = 1234
            )

            hook.afterAgentComplete(context, response)

            val metadata = hook.get("run-42")
            assertNotNull(metadata) { "Should retrieve cached metadata" }
            assertEquals("run-42", metadata!!.runId) { "runId should match" }
            assertEquals("user-7", metadata.userId) { "userId should match" }
            assertEquals("What is AI?", metadata.userPrompt) { "userPrompt should match" }
            assertEquals("AI is artificial intelligence.", metadata.agentResponse) { "agentResponse should match" }
            assertEquals(listOf("search", "calculator"), metadata.toolsUsed) { "toolsUsed should match" }
            assertEquals(1234L, metadata.durationMs) { "durationMs should match" }
        }

        @Test
        fun `should capture sessionId from context metadata`() = runTest {
            hook.afterAgentComplete(
                createContext(runId = "run-1", sessionId = "sess-abc"),
                createResponse()
            )

            val metadata = hook.get("run-1")
            assertEquals("sess-abc", metadata?.sessionId) { "sessionId should come from context.metadata[\"sessionId\"]" }
        }

        @Test
        fun `should have null sessionId when not in metadata`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            val metadata = hook.get("run-1")
            assertNull(metadata?.sessionId) { "sessionId should be null when not in context metadata" }
        }

        @Test
        fun `should return null for uncached runId`() {
            assertNull(hook.get("nonexistent")) { "Should return null for unknown runId" }
        }

        @Test
        fun `should overwrite metadata for same runId`() = runTest {
            hook.afterAgentComplete(
                createContext(runId = "run-1", userPrompt = "First"),
                createResponse(content = "First response")
            )
            hook.afterAgentComplete(
                createContext(runId = "run-1", userPrompt = "Second"),
                createResponse(content = "Second response")
            )

            val metadata = hook.get("run-1")
            assertEquals("Second", metadata?.userPrompt) { "Should have latest prompt" }
            assertEquals("Second response", metadata?.agentResponse) { "Should have latest response" }
        }

        @Test
        fun `should cache multiple run IDs independently`() = runTest {
            hook.afterAgentComplete(
                createContext(runId = "run-A", userPrompt = "Question A"),
                createResponse(content = "Answer A")
            )
            hook.afterAgentComplete(
                createContext(runId = "run-B", userPrompt = "Question B"),
                createResponse(content = "Answer B")
            )

            val metaA = hook.get("run-A")
            val metaB = hook.get("run-B")
            assertNotNull(metaA) { "run-A should be cached" }
            assertNotNull(metaB) { "run-B should be cached" }
            assertEquals("Question A", metaA!!.userPrompt) { "run-A prompt should match" }
            assertEquals("Question B", metaB!!.userPrompt) { "run-B prompt should match" }
        }
    }

    @Nested
    inner class TtlEviction {

        @Test
        fun `should return null for expired entry on get`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            // Advance clock past TTL (1 hour)
            clock.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS + 1))

            assertNull(hook.get("run-1")) { "Expired entry should return null on get" }
        }

        @Test
        fun `should return entry just before TTL expiry`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            // Advance clock to just before TTL
            clock.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS - 1))

            assertNotNull(hook.get("run-1")) { "Entry should still be available just before TTL" }
        }

        @Test
        fun `should evict stale entries during periodic eviction`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-old"), createResponse())

            // Advance past TTL + eviction interval
            clock.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS + 31))

            // Trigger eviction by adding a new entry
            hook.afterAgentComplete(createContext(runId = "run-new"), createResponse())

            assertEquals(1, hook.cacheSize()) { "Should have evicted old entry, keeping only new" }
            assertNotNull(hook.get("run-new")) { "New entry should exist" }
        }
    }

    @Nested
    inner class MaxEntriesEviction {

        @Test
        fun `should not exceed max entries after eviction`() = runTest {
            // Add 10,001 entries (advance clock 31s to trigger eviction)
            for (i in 1..10_000) {
                hook.afterAgentComplete(
                    createContext(runId = "run-$i"),
                    createResponse()
                )
                clock.advance(Duration.ofMillis(1))
            }

            // Advance past eviction interval to trigger eviction on next write
            clock.advance(Duration.ofSeconds(31))

            hook.afterAgentComplete(
                createContext(runId = "run-10001"),
                createResponse()
            )

            assertTrue(hook.cacheSize() <= 10_000) {
                "Cache should not exceed 10,000 entries, got ${hook.cacheSize()}"
            }
            assertNotNull(hook.get("run-10001")) { "Newest entry should exist" }
        }
    }

    /**
     * Mutable clock for testing time-dependent behavior.
     */
    private class MutableClock(
        private var instant: Instant
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = instant

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }
}
