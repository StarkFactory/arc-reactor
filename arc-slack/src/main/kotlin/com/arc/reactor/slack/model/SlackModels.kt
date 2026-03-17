package com.arc.reactor.slack.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slack URL 검증 챌린지 응답.
 */
data class SlackChallengeResponse(val challenge: String)

/**
 * 이벤트 처리용 내부 커맨드 모델.
 */
data class SlackEventCommand(
    val eventType: String,
    val userId: String,
    val channelId: String,
    val text: String,
    val ts: String,
    val threadTs: String?,
    val channelType: String? = null
) {
    fun isDirectMessageChannel(): Boolean = channelType == "im" || channelType == "mpim"
}

/**
 * 슬래시 명령 처리용 내부 커맨드 모델.
 */
data class SlackSlashCommand(
    val command: String,
    val text: String,
    val userId: String,
    val userName: String?,
    val channelId: String,
    val channelName: String?,
    val responseUrl: String,
    val triggerId: String?
)

/**
 * 슬래시 명령 엔드포인트의 즉시 확인 응답 본문.
 */
data class SlackCommandAckResponse(
    @param:JsonProperty("response_type")
    val responseType: String = "ephemeral",
    val text: String
)

/**
 * Slack Web API 응답 모델.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackApiResult(
    val ok: Boolean = false,
    val error: String? = null,
    val ts: String? = null,
    val channel: String? = null
)
