package com.arc.reactor.hook.impl

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.github.benmanes.caffeine.cache.Ticker
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * FeedbackMetadataCaptureHook에 대한 테���트.
 *
 * Caffeine 기반 캐시의 TTL 만료와 최대 엔트리 퇴거를 검증한다.
 */
class FeedbackMetadataCaptureHookTest {

    private val baseTime = Instant.parse("2026-02-16T12:00:00Z")
    private lateinit var clock: MutableClock
    private lateinit var ticker: MutableTicker
    private lateinit var hook: FeedbackMetadataCaptureHook

    @BeforeEach
    fun setup() {
        clock = MutableClock(baseTime)
        ticker = MutableTicker()
        hook = FeedbackMetadataCaptureHook(clock = clock, ticker = ticker)
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
        fun `order가 250이어야 한다`() {
            assertEquals(250, hook.order) { "웹훅(200) 이후 실행을 위해 order는 250이어야 한다" }
        }

        @Test
        fun `fail-open이어야 한다`() {
            assertFalse(hook.failOnError) { "Hook 실패 시 에이전트 응답을 차단하면 안 된다" }
        }
    }

    @Nested
    inner class MetadataCapture {

        @Test
        fun `runId로 메타데이터를 캡처하고 조회할 수 있어야 한다`() = runTest {
            val context = createContext(runId = "run-42", userId = "user-7", userPrompt = "What is AI?")
            val response = createResponse(
                content = "AI is artificial intelligence.",
                toolsUsed = listOf("search", "calculator"),
                durationMs = 1234
            )

            hook.afterAgentComplete(context, response)

            val metadata = hook.get("run-42")
            assertNotNull(metadata) { "캐시된 메타데이터를 조회할 수 있어야 한다" }
            assertEquals("run-42", metadata?.runId) { "runId가 일치해야 ��다" }
            assertEquals("user-7", metadata?.userId) { "userId가 일치해야 한다" }
            assertEquals("What is AI?", metadata?.userPrompt) { "userPrompt가 일치해야 한다" }
            assertEquals("AI is artificial intelligence.", metadata?.agentResponse) { "agentResponse가 일치해야 한다" }
            assertEquals(listOf("search", "calculator"), metadata?.toolsUsed) { "toolsUsed가 일치해야 한다" }
            assertEquals(1234L, metadata?.durationMs) { "durationMs가 일치해야 한다" }
        }

        @Test
        fun `context metadata에서 sessionId를 캡처해야 한다`() = runTest {
            hook.afterAgentComplete(
                createContext(runId = "run-1", sessionId = "sess-abc"),
                createResponse()
            )

            val metadata = hook.get("run-1")
            assertEquals("sess-abc", metadata?.sessionId) { "sessionId는 context.metadata[\"sessionId\"]에서 가져와야 한다" }
        }

        @Test
        fun `metadata에 없으면 sessionId가 null이어야 한��`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            val metadata = hook.get("run-1")
            assertNull(metadata?.sessionId) { "context metadata에 없으면 sessionId는 null���어야 한다" }
        }

        @Test
        fun `캐시되지 않은 runId에 대해 null을 반환해야 한다`() {
            assertNull(hook.get("nonexistent")) { "알 수 없는 runId에 대해 null을 반환해야 한다" }
        }

        @Test
        fun `동일 runId에 대해 메타데이터를 덮어써야 한다`() = runTest {
            hook.afterAgentComplete(
                createContext(runId = "run-1", userPrompt = "First"),
                createResponse(content = "First response")
            )
            hook.afterAgentComplete(
                createContext(runId = "run-1", userPrompt = "Second"),
                createResponse(content = "Second response")
            )

            val metadata = hook.get("run-1")
            assertEquals("Second", metadata?.userPrompt) { "최신 프롬프트가 저장되어야 한다" }
            assertEquals("Second response", metadata?.agentResponse) { "최신 응답이 저장되어야 한다" }
        }

        @Test
        fun `여러 runId를 독립적으로 캐시해야 한다`() = runTest {
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
            assertNotNull(metaA) { "run-A가 캐시되어야 한다" }
            assertNotNull(metaB) { "run-B가 캐시되어��� 한다" }
            assertEquals("Question A", metaA?.userPrompt) { "run-A 프롬프트가 일치해야 한다" }
            assertEquals("Question B", metaB?.userPrompt) { "run-B 프롬프트가 일치해야 한다" }
        }
    }

    @Nested
    inner class TtlEviction {

        @Test
        fun `만료된 엔트리는 get 시 null을 반환해야 한다`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            // TTL(1시간)을 지나도록 ticker 진행
            ticker.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS + 1))

            assertNull(hook.get("run-1")) { "만료된 엔트리는 null을 반환해야 한다" }
        }

        @Test
        fun `TTL 만료 직전에는 엔트리를 반환��야 한다`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-1"), createResponse())

            // TTL 직전까지 시간 진행
            ticker.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS - 1))

            assertNotNull(hook.get("run-1")) { "TTL 직전에는 엔트리가 존재해야 한다" }
        }

        @Test
        fun `TTL 만료 후 새 엔트리 추가 시 오래된 엔트리가 퇴거되어야 한다`() = runTest {
            hook.afterAgentComplete(createContext(runId = "run-old"), createResponse())

            // TTL을 넘기도록 시간 진행
            ticker.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS + 1))
            clock.advance(Duration.ofSeconds(FeedbackMetadataCaptureHook.TTL_SECONDS + 1))

            // 새 엔트리를 추가하면 Caffeine이 만료 엔트리를 퇴거한다
            hook.afterAgentComplete(createContext(runId = "run-new"), createResponse())

            assertNull(hook.get("run-old")) { "만료된 엔트리는 퇴거되어야 한다" }
            assertNotNull(hook.get("run-new")) { "새 엔트리는 존재해야 한다" }
        }
    }

    @Nested
    inner class MaxEntriesEviction {

        @Test
        fun `최대 엔트리 수를 초과하면 퇴거되어야 한다`() = runTest {
            // 10,001개의 엔트리를 추가한다
            for (i in 1..10_001) {
                hook.afterAgentComplete(
                    createContext(runId = "run-$i"),
                    createResponse()
                )
                ticker.advance(Duration.ofMillis(1))
                clock.advance(Duration.ofMillis(1))
            }

            // Caffeine의 비동기 퇴거를 확정시킨다
            hook.cacheSize() // cleanUp 트리거

            assertTrue(hook.cacheSize() <= 10_000) {
                "캐시가 10,000개를 초과하면 안 된다, 실제: ${hook.cacheSize()}"
            }
            assertNotNull(hook.get("run-10001")) { "가장 최��� 엔트리는 존재해야 한다" }
        }
    }

    /**
     * 시간 의존적 동작을 테스트하기 위한 가변 클록.
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

    /**
     * Caffeine 캐시의 시간 소스를 제어하기 위한 가변 Ticker.
     */
    private class MutableTicker : Ticker {
        private val nanos = AtomicLong(0)

        override fun read(): Long = nanos.get()

        fun advance(duration: Duration) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(duration.seconds) + duration.nano.toLong())
        }
    }
}
