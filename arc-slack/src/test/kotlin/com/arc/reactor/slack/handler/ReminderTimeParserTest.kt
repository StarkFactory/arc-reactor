package com.arc.reactor.slack.handler

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [ReminderTimeParser]의 시간 파싱 테스트.
 *
 * 영문(at HH:mm) 및 한글(N시 N분) 시간 형식 파싱,
 * 유효하지 않은 시간 처리, 과거 시간의 익일 변환을 검증한다.
 */
class ReminderTimeParserTest {

    private val tz = ZoneId.of("Asia/Seoul")

    @Test
    fun `at HH-mm format를 파싱한다`() {
        val result = ReminderTimeParser.parse("Follow up with design review at 15:30", tz)
        result.cleanText shouldBe "Follow up with design review"
        result.dueAt.shouldNotBeNull()
    }

    @Test
    fun `at H-mm format를 파싱한다`() {
        val result = ReminderTimeParser.parse("Check email at 9:00", tz)
        result.cleanText shouldBe "Check email"
        result.dueAt.shouldNotBeNull()
    }

    @Test
    fun `Korean time format를 파싱한다`() {
        val result = ReminderTimeParser.parse("회의 준비 15시 30분에", tz)
        result.cleanText shouldBe "회의 준비"
        result.dueAt.shouldNotBeNull()
    }

    @Test
    fun `Korean time without minutes를 파싱한다`() {
        val result = ReminderTimeParser.parse("점심 먹기 12시", tz)
        result.cleanText shouldBe "점심 먹기"
        result.dueAt.shouldNotBeNull()
    }

    @Test
    fun `없는 time returns null dueAt`() {
        val result = ReminderTimeParser.parse("Buy groceries", tz)
        result.cleanText shouldBe "Buy groceries"
        result.dueAt.shouldBeNull()
    }

    @Test
    fun `유효하지 않은 hour returns null dueAt`() {
        val result = ReminderTimeParser.parse("Check at 25:00", tz)
        result.dueAt.shouldBeNull()
    }

    @Test
    fun `time은(는) in the past resolves to tomorrow`() {
        val now = ZonedDateTime.now(tz)
        val pastHour = if (now.hour > 0) now.hour - 1 else 23
        val result = ReminderTimeParser.parse("test at ${pastHour}:00", tz)
        result.dueAt.shouldNotBeNull()
        val dueZoned = result.dueAt.atZone(tz)
        if (pastHour < now.hour) {
            dueZoned.dayOfYear shouldBe now.plusDays(1).dayOfYear
        }
    }
}
