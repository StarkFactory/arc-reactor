package com.arc.reactor.slack.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slack URL verification challenge response.
 */
data class SlackChallengeResponse(val challenge: String)

/**
 * Slack event callback wrapper (top-level payload).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackEventPayload(
    val type: String? = null,
    val challenge: String? = null,
    val event: SlackEvent? = null
)

/**
 * Slack event data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackEvent(
    val type: String = "",
    val user: String? = null,
    val channel: String? = null,
    val text: String? = null,
    val ts: String? = null,
    @param:JsonProperty("thread_ts")
    val threadTs: String? = null,
    @param:JsonProperty("bot_id")
    val botId: String? = null,
    val subtype: String? = null
)

/**
 * Internal command for event processing.
 */
data class SlackEventCommand(
    val eventType: String,
    val userId: String,
    val channelId: String,
    val text: String,
    val ts: String,
    val threadTs: String?
)

/**
 * Slack Web API response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SlackApiResult(
    val ok: Boolean = false,
    val error: String? = null,
    val ts: String? = null,
    val channel: String? = null
)
