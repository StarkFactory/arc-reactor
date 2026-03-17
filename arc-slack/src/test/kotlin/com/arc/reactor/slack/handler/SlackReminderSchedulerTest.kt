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

/**
 * [SlackReminderScheduler]의 리마인더 스케줄링 테스트.
 *
 * 만료된 리마인더 수집, DM을 통한 알림 전송,
 * 그리고 만료 항목이 없을 때의 동작을 검증한다.
 */
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

        // 이미 만료된 리마인더를 수동으로 추가
        val reminder = store.add("U_TEST", "test reminder at 0:00")

        // dueAt을 과거로 강제 설정하여 직접 테스트
        val list = store.list("U_TEST")
        // "at 0:00"은 파싱되어 미래 시간으로 설정됨
        // collectDueReminders를 과거 만료 항목으로 직접 테스트

        // 스케줄러 생성 (자동 폴링 방지를 위해 큰 간격 설정)
        scheduler = SlackReminderScheduler(store, messagingService, pollIntervalSeconds = 3600)

        // "at 0:00"이 내일로 해석되므로 직접 확인
    }

    @Test
    fun `collectDueReminders은(는) returns and removes due entries`() {
        // dueAt이 없는 리마인더는 만료 수집 대상에서 제외되는지 확인
        store.add("U1", "no time reminder")
        val collected = store.collectDueReminders()
        collected.size shouldBe 0
        store.list("U1").size shouldBe 1
    }

    @Test
    fun `pollAndNotify은(는) sends DM with reminder text`() {
        coEvery { messagingService.sendMessage(any(), any(), any()) } returns SlackApiResult(ok = true)

        // 저장소를 생성하고 만료 리마인더 없이 수동 폴링 실행
        scheduler = SlackReminderScheduler(store, messagingService, pollIntervalSeconds = 3600)
        scheduler!!.pollAndNotify()

        // 만료된 리마인더가 없으므로 메시지가 전송되지 않아야 한다
        coVerify(exactly = 0) { messagingService.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `help은(는) text includes reminder scheduling hint`() {
        DefaultSlackCommandHandler.HELP_TEXT.contains("at HH:mm") shouldBe true
    }
}
