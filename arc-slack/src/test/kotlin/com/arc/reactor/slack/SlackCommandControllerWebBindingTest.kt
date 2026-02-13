package com.arc.reactor.slack

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.controller.SlackCommandController
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackCommandAckResponse
import com.arc.reactor.slack.service.SlackMessagingService
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class SlackCommandControllerWebBindingTest {

    private val commandHandler = mockk<SlackCommandHandler>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)
    private val controller = SlackCommandController(
        commandHandler = commandHandler,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder,
        properties = SlackProperties(enabled = true)
    )
    private val webTestClient = WebTestClient.bindToController(controller).build()

    @Test
    fun `accepts slash command form-urlencoded body`() {
        webTestClient.post()
            .uri("/api/slack/commands")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(
                "command=%2Fjarvis&text=hello+there&user_id=U123" +
                    "&user_name=alice&channel_id=C456&channel_name=general" +
                    "&response_url=https%3A%2F%2Fexample.com%2Fresponse"
            )
            .exchange()
            .expectStatus().isOk
            .expectBody(SlackCommandAckResponse::class.java)
            .consumeWith { result ->
                result.responseBody?.responseType shouldBe "ephemeral"
            }

        verify(timeout = 2000) { metricsRecorder.recordInbound(entrypoint = "slash_command") }
        coVerify(timeout = 2000) { commandHandler.handleSlashCommand(any()) }
    }
}
