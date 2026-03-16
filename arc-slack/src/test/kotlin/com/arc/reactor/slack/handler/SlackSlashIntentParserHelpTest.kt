package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SlackSlashIntentParserHelpTest {

    @Test
    fun `help keyword를 파싱한다`() {
        SlackSlashIntentParser.parse("help").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `help case insensitive를 파싱한다`() {
        SlackSlashIntentParser.parse("HELP").shouldBeInstanceOf<SlackSlashIntent.Help>()
        SlackSlashIntentParser.parse("Help").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `Korean help를 파싱한다`() {
        SlackSlashIntentParser.parse("도움말").shouldBeInstanceOf<SlackSlashIntent.Help>()
        SlackSlashIntentParser.parse("도움").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `commands keyword를 파싱한다`() {
        SlackSlashIntentParser.parse("commands").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `help은(는) with extra text falls through to agent`() {
        val result = SlackSlashIntentParser.parse("help me with deployment")
        result.shouldBeInstanceOf<SlackSlashIntent.Agent>()
    }
}
