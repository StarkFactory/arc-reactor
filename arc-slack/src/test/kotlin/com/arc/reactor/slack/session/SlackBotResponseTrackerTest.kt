package com.arc.reactor.slack.session

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SlackBotResponseTracker]의 봇 응답 추적 테스트.
 *
 * 응답 추적/조회, TTL 만료, 빈 입력 무시,
 * 최대 항목 초과 시 오래된 항목 제거를 검증한다.
 */
class SlackBotResponseTrackerTest {

    @Test
    fun `and looks up bot response를 추적한다`() {
        val tracker = SlackBotResponseTracker()
        tracker.track("C1", "1.001", "session-1", "What is Kotlin?")

        val result = tracker.lookup("C1", "1.001")
        result.shouldNotBeNull()
        result.sessionId shouldBe "session-1"
        result.userPrompt shouldBe "What is Kotlin?"
    }

    @Test
    fun `untracked message에 대해 null를 반환한다`() {
        val tracker = SlackBotResponseTracker()
        tracker.lookup("C1", "999.999").shouldBeNull()
    }

    @Test
    fun `expired entry에 대해 null를 반환한다`() {
        val tracker = SlackBotResponseTracker(ttlSeconds = 1)
        tracker.track("C1", "1.001", "session-1", "hello")
        Thread.sleep(1100)
        tracker.lookup("C1", "1.001").shouldBeNull()
    }

    @Test
    fun `blank inputs를 무시한다`() {
        val tracker = SlackBotResponseTracker()
        tracker.track("", "1.001", "s", "p")
        tracker.track("C1", "", "s", "p")
        tracker.lookup("", "1.001").shouldBeNull()
        tracker.lookup("C1", "").shouldBeNull()
    }

    @Test
    fun `oldest when max entries exceeded를 제거한다`() {
        val tracker = SlackBotResponseTracker(maxEntries = 2)
        tracker.track("C1", "1.0", "s1", "p1")
        tracker.track("C1", "2.0", "s2", "p2")
        tracker.track("C1", "3.0", "s3", "p3")

        tracker.lookup("C1", "3.0").shouldNotBeNull()
        tracker.lookup("C1", "2.0").shouldNotBeNull()
    }
}
