package com.arc.reactor.slack.handler

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * [LoopIntervalParser]의 인터벌 → cron 변환 테스트.
 */
class LoopIntervalParserTest {

    @Nested
    inner class ToCron {

        @Test
        fun `분 단위 파싱`() {
            LoopIntervalParser.toCron("30m") shouldBe "0 */30 * * * *"
            LoopIntervalParser.toCron("45m") shouldBe "0 */45 * * * *"
        }

        @Test
        fun `시간 단위 파싱`() {
            LoopIntervalParser.toCron("1h") shouldBe "0 0 */1 * * *"
            LoopIntervalParser.toCron("2h") shouldBe "0 0 */2 * * *"
        }

        @Test
        fun `AM PM 시각 파싱`() {
            LoopIntervalParser.toCron("9am") shouldBe "0 0 9 * * *"
            LoopIntervalParser.toCron("2pm") shouldBe "0 0 14 * * *"
            LoopIntervalParser.toCron("12am") shouldBe "0 0 0 * * *"
            LoopIntervalParser.toCron("12pm") shouldBe "0 0 12 * * *"
        }

        @Test
        fun `24시간 시각 파싱`() {
            LoopIntervalParser.toCron("14:30") shouldBe "0 30 14 * * *"
            LoopIntervalParser.toCron("9:00") shouldBe "0 0 9 * * *"
        }

        @Test
        fun `한국어 시각 파싱`() {
            LoopIntervalParser.toCron("9시") shouldBe "0 0 9 * * *"
            LoopIntervalParser.toCron("14시30분") shouldBe "0 30 14 * * *"
        }

        @Test
        fun `키워드 파싱`() {
            LoopIntervalParser.toCron("daily") shouldBe "0 0 9 * * *"
            LoopIntervalParser.toCron("매일") shouldBe "0 0 9 * * *"
            LoopIntervalParser.toCron("weekly") shouldBe "0 0 9 * * MON"
            LoopIntervalParser.toCron("weekday") shouldBe "0 0 9 * * MON-FRI"
            LoopIntervalParser.toCron("평일") shouldBe "0 0 9 * * MON-FRI"
        }

        @Test
        fun `유효하지 않은 입력은 null`() {
            LoopIntervalParser.toCron("abc").shouldBeNull()
            LoopIntervalParser.toCron("0m").shouldBeNull()
            LoopIntervalParser.toCron("25:00").shouldBeNull()
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `30분 미만은 거부`() {
            assertFalse(LoopIntervalParser.isValidMinInterval("10m")) { "10분은 거부해야 합니다" }
            assertFalse(LoopIntervalParser.isValidMinInterval("29m")) { "29분은 거부해야 합니다" }
        }

        @Test
        fun `30분 이상은 허용`() {
            assertTrue(LoopIntervalParser.isValidMinInterval("30m")) { "30분은 허용해야 합니다" }
            assertTrue(LoopIntervalParser.isValidMinInterval("1h")) { "1시간은 허용해야 합니다" }
            assertTrue(LoopIntervalParser.isValidMinInterval("daily")) { "daily는 허용해야 합니다" }
        }
    }

    @Nested
    inner class Description {

        @Test
        fun `설명 생성`() {
            LoopIntervalParser.toDescription("30m") shouldBe "30분마다"
            LoopIntervalParser.toDescription("1h") shouldBe "1시간마다"
            LoopIntervalParser.toDescription("9am") shouldBe "매일 09:00 KST"
            LoopIntervalParser.toDescription("14:30") shouldBe "매일 14:30 KST"
            LoopIntervalParser.toDescription("daily") shouldBe "매일 09:00 KST"
            LoopIntervalParser.toDescription("weekly") shouldBe "매주 월요일 09:00 KST"
            LoopIntervalParser.toDescription("weekday") shouldBe "평일 09:00 KST"
        }
    }
}
