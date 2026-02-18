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

class SlackCommandControllerTest {

    private val commandHandler = mockk<SlackCommandHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)
    private val properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
    private val processor = SlackCommandProcessor(commandHandler, messagingService, metricsRecorder, properties)
    private val controller = SlackCommandController(processor)

    @Test
    fun `returns 200 immediately for slash command`() = runTest {
        val response = controller.handleSlashCommand(
            command = "/jarvis",
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
    fun `dispatches slash command to handler asynchronously`() = runTest {
        coEvery { commandHandler.handleSlashCommand(any()) } returns Unit

        controller.handleSlashCommand(
            command = "/jarvis",
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
                it.command == "/jarvis" &&
                    it.text == "today tasks" &&
                    it.userId == "U123" &&
                    it.channelId == "C456"
            })
        }
    }

    @Test
    fun `returns busy ack immediately when saturated in fail fast mode`() = runTest {
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
            command = "/jarvis",
            text = "first",
            userId = "U1",
            userName = "alice",
            channelId = "C1",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/first",
            triggerId = null
        )

        val second = failFastController.handleSlashCommand(
            command = "/jarvis",
            text = "second",
            userId = "U2",
            userName = "bob",
            channelId = "C2",
            channelName = "general",
            responseUrl = "https://hooks.slack.com/commands/second",
            triggerId = null
        )

        second.statusCode shouldBe HttpStatus.OK
        second.body?.text.orEmpty().lowercase().contains("busy") shouldBe true
    }

    @Test
    fun `sends busy response_url message when request queue times out in queue mode`() = runTest {
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
            command = "/jarvis",
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
            command = "/jarvis",
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
                match { it.contains("busy", ignoreCase = true) },
                any()
            )
        }
    }
}
