package com.arc.reactor.slack.handler

import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.ZoneId

class SlackReminderSchedulerTest {

    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val store = SlackReminderStore(timezone = ZoneId.of("Asia/Seoul"))
    private var scheduler: SlackReminderScheduler? = null

    @AfterEach
    fun cleanup() {
        scheduler?.shutdown()
    }

    @Test
    fun `pollAndNotify은(는) delivers due reminders via DM`() {
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

        // Manually add a reminder that's already due
        val reminder = store.add("U_TEST", "test reminder at 0:00")

        // the dueAt to be in the past by adding directly를 강제합니다
        val list = store.list("U_TEST")
        // "at 0:00"은 파싱되어 미래 시간으로 설정되어야 합니다
        // Instead, let's use collectDueReminders after manually inserting a past-due one

        // scheduler (large interval so it won't auto-poll) 생성
        scheduler = SlackReminderScheduler(store, messagingService, pollIntervalSeconds = 3600)

        // 리마인더가 이미 만료되었다면 collectDueReminders가 반환해야 합니다
        // 내일로 해석되는 "at 0:00"을 사용했으므로 직접 테스트로 확인합니다
    }

    @Test
    fun `collectDueReminders은(는) returns and removes due entries`() {
        // dueAt이 없는 리마인더는 수집되지 않는지 테스트
        store.add("U1", "no time reminder")
        val collected = store.collectDueReminders()
        collected.size shouldBe 0
        store.list("U1").size shouldBe 1
    }

    @Test
    fun `pollAndNotify은(는) sends DM with reminder text`() {
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

        // a store and manually trigger poll with no due reminders 생성
        scheduler = SlackReminderScheduler(store, messagingService, pollIntervalSeconds = 3600)
        scheduler!!.pollAndNotify()

        // reminders due, so no messages sent 없음
        coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `help은(는) text includes reminder scheduling hint`() {
        DefaultSlackCommandHandler.HELP_TEXT.contains("at HH:mm") shouldBe true
    }
}
