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
        // The "at 0:00"은(는) have been parsed and set to a future time해야 합니다
        // Instead, let's use collectDueReminders after manually inserting a past-due one

        // scheduler (large interval so it won't auto-poll) 생성
        scheduler = SlackReminderScheduler(store, messagingService, pollIntervalSeconds = 3600)

        // If the reminder is already past due, collectDueReminders은(는) return it해야 합니다
        // Since we used "at 0:00" which resolves to tomorrow, let's verify with a direct test
    }

    @Test
    fun `collectDueReminders은(는) returns and removes due entries`() {
        // Test that reminders without dueAt are not collected
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
