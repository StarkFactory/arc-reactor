package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SlackSlashIntentParser]의 Loop/Search/Who/Ask/Summarize/Translate 파싱 테스트.
 */
class SlackSlashIntentParserLoopTest {

    @Nested
    inner class LoopParsing {

        @Test
        fun `loop create를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("loop 9am 내 이슈 요약해줘")
            intent.shouldBeInstanceOf<SlackSlashIntent.LoopCreate>()
            intent.interval shouldBe "9am"
            intent.prompt shouldBe "내 이슈 요약해줘"
        }

        @Test
        fun `loop list를 파싱한다`() {
            SlackSlashIntentParser.parse("loop list").shouldBeInstanceOf<SlackSlashIntent.LoopList>()
            SlackSlashIntentParser.parse("loop 목록").shouldBeInstanceOf<SlackSlashIntent.LoopList>()
            SlackSlashIntentParser.parse("루프 list").shouldBeInstanceOf<SlackSlashIntent.LoopList>()
        }

        @Test
        fun `loop stop을 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("loop stop 2")
            intent.shouldBeInstanceOf<SlackSlashIntent.LoopStop>()
            intent.id shouldBe 2
        }

        @Test
        fun `loop clear를 파싱한다`() {
            SlackSlashIntentParser.parse("loop clear").shouldBeInstanceOf<SlackSlashIntent.LoopClear>()
            SlackSlashIntentParser.parse("반복 전체삭제").shouldBeInstanceOf<SlackSlashIntent.LoopClear>()
        }

        @Test
        fun `유효하지 않은 인터벌은 일반 에이전트로 폴백한다`() {
            val intent = SlackSlashIntentParser.parse("loop abc 뭔가 해줘")
            intent.shouldBeInstanceOf<SlackSlashIntent.Agent>()
        }
    }

    @Nested
    inner class EnterpriseCommandParsing {

        @Test
        fun `search를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("search 연차 정책")
            intent.shouldBeInstanceOf<SlackSlashIntent.Search>()
            intent.query shouldBe "연차 정책"
        }

        @Test
        fun `검색 한국어를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("검색 배포 프로세스")
            intent.shouldBeInstanceOf<SlackSlashIntent.Search>()
            intent.query shouldBe "배포 프로세스"
        }

        @Test
        fun `who를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("who 최진안")
            intent.shouldBeInstanceOf<SlackSlashIntent.Who>()
            intent.query shouldBe "최진안"
        }

        @Test
        fun `ask를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("ask 재택근무 가능한가요?")
            intent.shouldBeInstanceOf<SlackSlashIntent.Ask>()
            intent.question shouldBe "재택근무 가능한가요?"
        }

        @Test
        fun `summarize를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("summarize 긴 텍스트입니다")
            intent.shouldBeInstanceOf<SlackSlashIntent.Summarize>()
            intent.text shouldBe "긴 텍스트입니다"
        }

        @Test
        fun `summarize 텍스트 없이를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("요약")
            intent.shouldBeInstanceOf<SlackSlashIntent.Summarize>()
            intent.text shouldBe ""
        }

        @Test
        fun `translate를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("translate Hello world")
            intent.shouldBeInstanceOf<SlackSlashIntent.Translate>()
            intent.text shouldBe "Hello world"
        }

        @Test
        fun `번역 한국어를 파싱한다`() {
            val intent = SlackSlashIntentParser.parse("번역 안녕하세요")
            intent.shouldBeInstanceOf<SlackSlashIntent.Translate>()
            intent.text shouldBe "안녕하세요"
        }
    }
}
