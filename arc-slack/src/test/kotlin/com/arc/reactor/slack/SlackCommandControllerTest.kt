package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackCommandController
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * [SlackCommandController]의 슬래시 커맨드 컨트롤러 테스트.
 *
 * 즉시 200 응답, 비동기 핸들러 디스패치, fail-fast 포화 모드,
 * 큐 모드 타임아웃 시 busy 알림 등을 검증한다.
 */
class SlackCommandControllerTest {

    private val commandHandler = mockk<SlackCommandHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)
    private val properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
    private val processor = SlackCommandProcessor(commandHandler, messagingService, metricsRecorder, properties)
    private val controller = SlackCommandController(processor)

    @Test
    fun `slash command에 대해 200 immediately를 반환한다`() = runTest {
        val response = controller.handleSlashCommand(
            command = "/reactor",
            text = "hello",
            userId = "U123",
            userName = "alice",
            channelId = "C456",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/test",
            triggerId = "trigger-1"
        )

        response.statusCode shouldBe HttpStatus.OK
        response.body?.responseType shouldBe "ephemeral"
    }

    @Test
    fun `slash command to handler asynchronously를 디스패치한다`() = runTest {
        coEvery { commandHandler.handleSlashCommand(any()) } returns Unit

        controller.handleSlashCommand(
            command = "/reactor",
            text = "today tasks",
            userId = "U123",
            userName = "alice",
            channelId = "C456",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/test",
            triggerId = "trigger-1"
        )

        coVerify(timeout = 2000) {
            commandHandler.handleSlashCommand(match<SlackSlashCommand> {
                it.command == "/reactor" &&
                    it.text == "today tasks" &&
                    it.userId == "U123" &&
                    it.channelId == "C456"
            })
        }
    }

    @Test
    fun `saturated in fail fast mode일 때 busy ack immediately를 반환한다`() = runTest {
        val slowHandler = mockk<SlackCommandHandler>()
        val failFastMessagingService = mockk<SlackMessagingService>(relaxed = true)
        val failFastController = SlackCommandController(
            commandProcessor = SlackCommandProcessor(
                commandHandler = slowHandler,
                messagingService = failFastMessagingService,
                metricsRecorder = metricsRecorder,
                properties = SlackProperties(
                    enabled = true,
                    maxConcurrentRequests = 1,
                    failFastOnSaturation = true
                )
            )
        )
        coEvery { slowHandler.handleSlashCommand(any()) } coAnswers { delay(300) }

        failFastController.handleSlashCommand(
            command = "/reactor",
            text = "first",
            userId = "U1",
            userName = "alice",
            channelId = "C1",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/first",
            triggerId = null
        )

        val second = failFastController.handleSlashCommand(
            command = "/reactor",
            text = "second",
            userId = "U2",
            userName = "bob",
            channelId = "C2",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/second",
            triggerId = null
        )

        second.statusCode shouldBe HttpStatus.OK
        second.body?.text.orEmpty().contains(":hourglass:") shouldBe true
    }

    @Test
    fun `busy response_url message when request queue times out in queue mode를 전송한다`() = runTest {
        val slowHandler = mockk<SlackCommandHandler>()
        val timeoutMessagingService = mockk<SlackMessagingService>(relaxed = true)
        val timeoutController = SlackCommandController(
            commandProcessor = SlackCommandProcessor(
                commandHandler = slowHandler,
                messagingService = timeoutMessagingService,
                metricsRecorder = metricsRecorder,
                properties = SlackProperties(
                    enabled = true,
                    maxConcurrentRequests = 1,
                    requestTimeoutMs = 50,
                    failFastOnSaturation = false,
                    notifyOnDrop = true
                )
            )
        )

        coEvery { slowHandler.handleSlashCommand(any()) } coAnswers { delay(300) }
        coEvery { timeoutMessagingService.sendResponseUrl(any(), any(), any()) } returns true

        timeoutController.handleSlashCommand(
            command = "/reactor",
            text = "first",
            userId = "U1",
            userName = "alice",
            channelId = "C1",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/first",
            triggerId = null
        )
        delay(30)
        timeoutController.handleSlashCommand(
            command = "/reactor",
            text = "second",
            userId = "U2",
            userName = "bob",
            channelId = "C2",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/second",
            triggerId = null
        )

        coVerify(timeout = 3000) {
            timeoutMessagingService.sendResponseUrl(
                any(),
                match { it.contains(":hourglass:") },
                any()
            )
        }
    }
}
