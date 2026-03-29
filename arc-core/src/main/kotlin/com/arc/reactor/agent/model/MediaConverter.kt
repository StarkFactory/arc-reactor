package com.arc.reactor.agent.model

import org.springframework.ai.content.Media
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.core.io.ByteArrayResource

/**
 * Arc Reactor의 [MediaAttachment]를 Spring AI의 [Media] 객체로 변환하고,
 * 멀티모달 [UserMessage] 인스턴스를 생성하는 변환기.
 *
 * @see MediaAttachment Arc Reactor의 미디어 첨부 파일 모델
 */
object MediaConverter {

    /**
     * [MediaAttachment]를 Spring AI [Media]로 변환한다.
     *
     * URI와 data 중 하나를 기반으로 Media 객체를 생성한다.
     *
     * @param attachment 변환할 미디어 첨부 파일
     * @return Spring AI Media 객체
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
            else -> error("MediaAttachment에 data 또는 uri가 필요합니다")
        }
    }

    /**
     * 선택적 미디어 첨부 파일과 함께 [UserMessage]를 생성한다.
     *
     * 미디어가 비어있으면 단순 텍스트 전용 UserMessage를 반환한다.
     * 미디어가 있으면 UserMessage.builder()로 미디어 목록과 함께 생성한다.
     *
     * @param text 사용자 메시지 텍스트
     * @param media 미디어 첨부 파일 목록 (기본: 빈 목록)
     * @return 생성된 UserMessage
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
