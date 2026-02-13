package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackCommandController
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.model.SlackSlashCommand
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
    private val properties = SlackProperties(enabled = true, maxConcurrentRequests = 5)
    private val controller = SlackCommandController(commandHandler, properties)

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

        delay(200)

        coVerify(timeout = 2000) {
            commandHandler.handleSlashCommand(match<SlackSlashCommand> {
                it.command == "/jarvis" &&
                    it.text == "today tasks" &&
                    it.userId == "U123" &&
                    it.channelId == "C456"
            })
        }
    }
}
