package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SlackSlashIntentParserHelpTest {

    @Test
    fun `parses help keyword`() {
        SlackSlashIntentParser.parse("help").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `parses help case insensitive`() {
        SlackSlashIntentParser.parse("HELP").shouldBeInstanceOf<SlackSlashIntent.Help>()
        SlackSlashIntentParser.parse("Help").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `parses Korean help`() {
        SlackSlashIntentParser.parse("도움말").shouldBeInstanceOf<SlackSlashIntent.Help>()
        SlackSlashIntentParser.parse("도움").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `parses commands keyword`() {
        SlackSlashIntentParser.parse("commands").shouldBeInstanceOf<SlackSlashIntent.Help>()
    }

    @Test
    fun `help with extra text falls through to agent`() {
        val result = SlackSlashIntentParser.parse("help me with deployment")
        result.shouldBeInstanceOf<SlackSlashIntent.Agent>()
    }
}
