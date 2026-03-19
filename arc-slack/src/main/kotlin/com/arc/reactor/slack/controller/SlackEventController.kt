package com.arc.reactor.slack.controller

import com.arc.reactor.slack.model.SlackChallengeResponse
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * Slack 이벤트 웹훅 엔드포인트 컨트롤러.
 *
 * 지원하는 이벤트:
 * - URL 검증 챌린지 (Slack 앱 설정 시)
 * - `app_mention` (봇이 채널에서 멘션될 때)
 * - `message` (스레드 답글)
 *
 * Slack의 3초 응답 요구사항을 충족하기 위해 모든 처리는 비동기로 수행된다.
 * Events API 전송 모드(`transport-mode=events_api`)에서만 활성화된다.
 *
 * @see SlackEventProcessor
 */
@RestController
@RequestMapping("/api/slack")
@ConditionalOnExpression(
    "\${arc.reactor.slack.enabled:false} and '\${arc.reactor.slack.transport-mode:events_api}' == 'events_api'"
)
@Tag(name = "Slack", description = "Slack event handling endpoints")
class SlackEventController(
    private val objectMapper: ObjectMapper,
    private val eventProcessor: SlackEventProcessor
) {
    @PostMapping("/events")
    @Operation(summary = "Handle Slack events (webhook endpoint)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Event accepted or URL verification challenge response"),
        ApiResponse(responseCode = "400", description = "Invalid payload")
    ])
    suspend fun handleEvent(
        @RequestBody payload: String,
        @RequestHeader(name = "X-Slack-Retry-Num", required = false) retryNum: String? = null,
        @RequestHeader(name = "X-Slack-Retry-Reason", required = false) retryReason: String? = null
    ): ResponseEntity<Any> {
        val json = objectMapper.readTree(payload)

        // URL 검증 챌린지
        if (json.has("challenge")) {
            val challenge = json.path("challenge").asText()
            logger.info { "Slack URL verification challenge received" }
            return ResponseEntity.ok(SlackChallengeResponse(challenge))
        }

        eventProcessor.submitEventCallback(
            payload = json,
            entrypoint = "events_api",
            retryNum = retryNum,
            retryReason = retryReason
        )
        return ResponseEntity.ok().build()
    }
}
