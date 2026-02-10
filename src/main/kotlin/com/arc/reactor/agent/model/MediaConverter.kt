package com.arc.reactor.agent.model

import org.springframework.ai.content.Media
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.core.io.ByteArrayResource

/**
 * Converts Arc Reactor [MediaAttachment] to Spring AI [Media] objects,
 * and builds multimodal [UserMessage] instances.
 */
object MediaConverter {

    /**
     * Convert a [MediaAttachment] to Spring AI [Media].
     */
    fun toSpringAiMedia(attachment: MediaAttachment): Media {
        return when {
            attachment.uri != null -> Media.builder()
                .mimeType(attachment.mimeType)
                .data(attachment.uri)
                .apply { attachment.name?.let { name(it) } }
                .build()
            attachment.data != null -> Media.builder()
                .mimeType(attachment.mimeType)
                .data(ByteArrayResource(attachment.data))
                .apply { attachment.name?.let { name(it) } }
                .build()
            else -> error("MediaAttachment must have either data or uri")
        }
    }

    /**
     * Build a [UserMessage] with optional media attachments.
     *
     * When media is empty, returns a simple text-only UserMessage.
     * When media is present, uses UserMessage.builder() with media list.
     */
    fun buildUserMessage(text: String, media: List<MediaAttachment> = emptyList()): UserMessage {
        if (media.isEmpty()) {
            return UserMessage(text)
        }
        val springMedia = media.map { toSpringAiMedia(it) }
        return UserMessage.builder()
            .text(text)
            .media(springMedia)
            .build()
    }
}
