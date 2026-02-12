package com.arc.reactor.line.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * LINE webhook event payload (top-level wrapper).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LineWebhookPayload(
    val events: List<LineEvent> = emptyList(),
    val destination: String? = null
)

/**
 * LINE webhook event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LineEvent(
    val type: String = "",
    @param:JsonProperty("replyToken")
    val replyToken: String? = null,
    val source: LineSource? = null,
    val message: LineMessage? = null,
    val timestamp: Long? = null
)

/**
 * LINE event source (user, group, or room).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LineSource(
    val type: String = "",
    val userId: String? = null,
    val groupId: String? = null,
    val roomId: String? = null
)

/**
 * LINE message object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LineMessage(
    val type: String = "",
    val id: String = "",
    val text: String? = null
)

/**
 * Internal command for LINE event processing.
 */
data class LineEventCommand(
    val userId: String,
    val groupId: String? = null,
    val roomId: String? = null,
    val text: String,
    val replyToken: String,
    val sourceType: String,
    val messageId: String
)
