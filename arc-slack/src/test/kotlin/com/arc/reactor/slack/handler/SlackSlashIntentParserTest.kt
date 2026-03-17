package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [SlackSlashIntentParser]의 슬래시 커맨드 인텐트 파싱 테스트.
 *
 * 일반 프롬프트, brief, my-work, 리마인더(목록/삭제/완료/추가) 등
 * 다양한 입력에 대한 인텐트 분류를 검증한다.
 */
class SlackSlashIntentParserTest {

    @Test
    fun `prompt parses as general agent intent를 기본값으로 한다`() {
        val intent = SlackSlashIntentParser.parse("What should I focus on today?") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.GENERAL
        intent.prompt shouldBe "What should I focus on today?"
    }

    @Test
    fun `brief은(는) command parses as brief intent`() {
        val intent = SlackSlashIntentParser.parse("brief sprint planning") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.BRIEF
        intent.prompt shouldContain "Create a personal daily brief for the user."
        intent.prompt shouldContain "Focus: sprint planning"
    }

    @Test
    fun `my-work 명령이 my work 인텐트로 파싱된다`() {
        val intent = SlackSlashIntentParser.parse("my-work frontend board") as SlackSlashIntent.Agent

        intent.mode shouldBe SlackSlashIntent.Agent.Mode.MY_WORK
        intent.prompt shouldContain "Summarize my work status as my personal assistant."
        intent.prompt shouldContain "Scope: frontend board"
    }

    @Test
    fun `list parses as reminder list intent를 리마인드한다`() {
        SlackSlashIntentParser.parse("remind") shouldBe SlackSlashIntent.ReminderList
        SlackSlashIntentParser.parse("remind list") shouldBe SlackSlashIntent.ReminderList
        SlackSlashIntentParser.parse("리마인드 목록") shouldBe SlackSlashIntent.ReminderList
    }

    @Test
    fun `clear parses as reminder clear intent를 리마인드한다`() {
        SlackSlashIntentParser.parse("remind clear") shouldBe SlackSlashIntent.ReminderClear
        SlackSlashIntentParser.parse("리마인드 전체삭제") shouldBe SlackSlashIntent.ReminderClear
    }

    @Test
    fun `done parses as reminder done intent를 리마인드한다`() {
        SlackSlashIntentParser.parse("remind done 12") shouldBe SlackSlashIntent.ReminderDone(12)
    }

    @Test
    fun `text parses as reminder add intent를 리마인드한다`() {
        SlackSlashIntentParser.parse("remind follow up with PM at 4")
            .shouldBe(SlackSlashIntent.ReminderAdd("follow up with PM at 4"))
    }
}
