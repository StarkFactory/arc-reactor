package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SlackSlashIntentParserTest {

    @Test
    fun `default prompt parses as general agent intent`() {
        val intent = SlackSlashIntentParser.parse("What should I focus on today?") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.GENERAL
        intent.prompt shouldBe "What should I focus on today?"
    }

    @Test
    fun `brief command parses as brief intent`() {
        val intent = SlackSlashIntentParser.parse("brief sprint planning") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.BRIEF
        intent.prompt shouldContain "Create a personal daily brief for the user."
        intent.prompt shouldContain "Focus: sprint planning"
    }

    @Test
    fun `my-work command parses as my work intent`() {
        val intent = SlackSlashIntentParser.parse("my-work frontend board") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.MY_WORK
        intent.prompt shouldContain "Summarize my work status as my personal assistant."
        intent.prompt shouldContain "Scope: frontend board"
    }

    @Test
    fun `remind list parses as reminder list intent`() {
        SlackSlashIntentParser.parse("remind") shouldBe SlackSlashIntent.ReminderList
        SlackSlashIntentParser.parse("remind list") shouldBe SlackSlashIntent.ReminderList
        SlackSlashIntentParser.parse("리마인드 목록") shouldBe SlackSlashIntent.ReminderList
    }

    @Test
    fun `remind clear parses as reminder clear intent`() {
        SlackSlashIntentParser.parse("remind clear") shouldBe SlackSlashIntent.ReminderClear
        SlackSlashIntentParser.parse("리마인드 전체삭제") shouldBe SlackSlashIntent.ReminderClear
    }

    @Test
    fun `remind done parses as reminder done intent`() {
        SlackSlashIntentParser.parse("remind done 12") shouldBe SlackSlashIntent.ReminderDone(12)
    }

    @Test
    fun `remind text parses as reminder add intent`() {
        SlackSlashIntentParser.parse("remind follow up with PM at 4")
            .shouldBe(SlackSlashIntent.ReminderAdd("follow up with PM at 4"))
    }
}
