package com.arc.reactor.agent

import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.agent.model.MediaConverter
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.util.MimeTypeUtils
import java.net.URI

/**
 * 멀티모달(이미지 첨부) 기능에 대한 테스트.
 *
 * MediaAttachment 모델, MediaConverter, 에이전트 실행 시
 * 미디어 포함 UserMessage 전달을 검증합니다.
 */
class MultimodalTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class MediaAttachmentModel {

        @Test
        fun `URI로 MediaAttachment를 생성해야 한다`() {
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_PNG,
                uri = URI("https://example.com/photo.png")
            )

            assertNotNull(attachment.uri) { "URI should not be null" }
            assertNull(attachment.data) { "Data should be null for URI-based attachment" }
            assertEquals(MimeTypeUtils.IMAGE_PNG, attachment.mimeType) { "MimeType mismatch" }
        }

        @Test
        fun `바이트 배열로 MediaAttachment를 생성해야 한다`() {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_JPEG,
                data = bytes,
                name = "test.jpg"
            )

            assertNotNull(attachment.data) { "Data should not be null" }
            assertNull(attachment.uri) { "URI should be null for byte-based attachment" }
            assertEquals("test.jpg", attachment.name) { "Name mismatch" }
        }

        @Test
        fun `데이터도 URI도 없는 MediaAttachment를 거부해야 한다`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_PNG)
            }
            assertTrue(exception.message.orEmpty().contains("data 또는 uri")) {
                "검증 메시지에 'data 또는 uri'가 포함되어야 한다, 실제: ${exception.message}"
            }
        }
    }

    @Nested
    inner class MediaConverterTest {

        @Test
        fun `미디어 없이 buildUserMessage가 단순 UserMessage를 반환해야 한다`() {
            val msg = MediaConverter.buildUserMessage("Hello")

            assertInstanceOf(UserMessage::class.java, msg) { "Should return UserMessage" }
            assertEquals("Hello", msg.text) { "Text content mismatch" }
            assertTrue(msg.media.isEmpty()) { "Expected no media, got: ${msg.media.size}" }
        }

        @Test
        fun `URI 미디어로 buildUserMessage가 미디어 포함 UserMessage를 반환해야 한다`() {
            val media = listOf(
                MediaAttachment(
                    mimeType = MimeTypeUtils.IMAGE_PNG,
                    uri = URI("https://example.com/image.png")
                )
            )

            val msg = MediaConverter.buildUserMessage("Describe this image", media)

            assertInstanceOf(UserMessage::class.java, msg) { "Should return UserMessage" }
            assertEquals("Describe this image", msg.text) { "Text content mismatch" }
            assertEquals(1, msg.media.size) { "Expected 1 media attachment" }
            assertEquals(MimeTypeUtils.IMAGE_PNG, msg.media[0].mimeType) { "MimeType mismatch" }
        }

        @Test
        fun `바이트 데이터 미디어로 buildUserMessage가 미디어 포함 UserMessage를 반환해야 한다`() {
            val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes
            val media = listOf(
                MediaAttachment(
                    mimeType = MimeTypeUtils.IMAGE_PNG,
                    data = imageBytes,
                    name = "screenshot.png"
                )
            )

            val msg = MediaConverter.buildUserMessage("What is in this screenshot?", media)

            assertEquals(1, msg.media.size) { "Expected 1 media attachment" }
            assertEquals("screenshot.png", msg.media[0].name) { "Media name mismatch" }
        }

        @Test
        fun `다중 미디어로 buildUserMessage가 모든 첨부파일을 반환해야 한다`() {
            val media = listOf(
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_PNG, uri = URI("https://example.com/1.png")),
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_JPEG, uri = URI("https://example.com/2.jpg"))
            )

            val msg = MediaConverter.buildUserMessage("Compare these images", media)

            assertEquals(2, msg.media.size) { "Expected 2 media attachments" }
        }

        @Test
        fun `toSpringAiMedia가 URI 첨부파일을 올바르게 변환해야 한다`() {
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_PNG,
                uri = URI("https://example.com/image.png")
            )

            val springMedia = MediaConverter.toSpringAiMedia(attachment)

            assertEquals(MimeTypeUtils.IMAGE_PNG, springMedia.mimeType) { "MimeType mismatch" }
        }

        @Test
        fun `toSpringAiMedia가 바이트 데이터 첨부파일을 올바르게 변환해야 한다`() {
            val bytes = byteArrayOf(1, 2, 3)
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_JPEG,
                data = bytes,
                name = "test.jpg"
            )

            val springMedia = MediaConverter.toSpringAiMedia(attachment)

            assertEquals(MimeTypeUtils.IMAGE_JPEG, springMedia.mimeType) { "MimeType mismatch" }
            assertEquals("test.jpg", springMedia.name) { "Name mismatch" }
        }
    }

    @Nested
    inner class ExecutorMultimodalIntegration {

        @Test
        fun `미디어 포함 실행 시 미디어 포함 UserMessage를 ChatClient에 전달해야 한다`() = runBlocking {
            // 준비
            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("I see an image of a cat.")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val media = listOf(
                MediaAttachment(
                    mimeType = MimeTypeUtils.IMAGE_PNG,
                    uri = URI("https://example.com/cat.png")
                )
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are a vision assistant.",
                    userPrompt = "What is in this image?",
                    media = media
                )
            )

            // 검증
            result.assertSuccess()
            assertEquals("I see an image of a cat.", result.content) { "Response content mismatch" }

            // UserMessage was constructed with media 확인
            assertTrue(messagesSlot.isCaptured) { "Messages should be captured" }
            val capturedMessages = messagesSlot.captured
            val userMsg = capturedMessages.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present in messages" }
            assertEquals(1, userMsg!!.media.size) { "UserMessage should have 1 media attachment" }
            assertEquals(MimeTypeUtils.IMAGE_PNG, userMsg.media[0].mimeType) { "Media mimeType mismatch" }
        }

        @Test
        fun `미디어 없이 실행 시 텍스트 전용 UserMessage를 전달해야 한다`() = runBlocking {
            // 준비
            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Hello!")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi"
                )
            )

            // 검증
            result.assertSuccess()

            val capturedMessages = messagesSlot.captured
            val userMsg = capturedMessages.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present" }
            assertTrue(userMsg!!.media.isEmpty()) { "UserMessage should have no media, got: ${userMsg.media.size}" }
        }

        @Test
        fun `다중 미디어 첨부파일 실행 시 모두 포함해야 한다`() = runBlocking {
            // 준비
            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("I see two images.")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val media = listOf(
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_PNG, uri = URI("https://example.com/1.png")),
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_JPEG, uri = URI("https://example.com/2.jpg"))
            )

            // 실행
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Compare images.",
                    userPrompt = "Compare these two images",
                    media = media
                )
            )

            // 검증
            result.assertSuccess()
            val userMsg = messagesSlot.captured.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present" }
            assertEquals(2, userMsg!!.media.size) { "Expected 2 media attachments" }
        }
    }
}
