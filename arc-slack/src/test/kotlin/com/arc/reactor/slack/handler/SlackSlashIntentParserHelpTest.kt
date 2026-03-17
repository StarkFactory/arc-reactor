package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [SlackSlashIntentParser]의 도움말 인텐트 파싱 테스트.
 *
 * help, HELP, 도움말, commands 등 다양한 도움말 키워드를 인식하고,
 * "help me with deployment" 같은 일반 문장은 에이전트 인텐트로 분류하는지 검증한다.
 */
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
