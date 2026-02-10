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
        fun `should create MediaAttachment from URI`() {
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_PNG,
                uri = URI("https://example.com/photo.png")
            )

            assertNotNull(attachment.uri) { "URI should not be null" }
            assertNull(attachment.data) { "Data should be null for URI-based attachment" }
            assertEquals(MimeTypeUtils.IMAGE_PNG, attachment.mimeType) { "MimeType mismatch" }
        }

        @Test
        fun `should create MediaAttachment from byte array`() {
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
        fun `should reject MediaAttachment with neither data nor URI`() {
            val exception = assertThrows(IllegalArgumentException::class.java) {
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_PNG)
            }
            assertTrue(exception.message!!.contains("Either data or uri")) {
                "Expected validation message, got: ${exception.message}"
            }
        }
    }

    @Nested
    inner class MediaConverterTest {

        @Test
        fun `buildUserMessage without media returns simple UserMessage`() {
            val msg = MediaConverter.buildUserMessage("Hello")

            assertInstanceOf(UserMessage::class.java, msg) { "Should return UserMessage" }
            assertEquals("Hello", msg.text) { "Text content mismatch" }
            assertTrue(msg.media.isEmpty()) { "Expected no media, got: ${msg.media.size}" }
        }

        @Test
        fun `buildUserMessage with URI media returns UserMessage with media`() {
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
        fun `buildUserMessage with byte data media returns UserMessage with media`() {
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
        fun `buildUserMessage with multiple media returns all attachments`() {
            val media = listOf(
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_PNG, uri = URI("https://example.com/1.png")),
                MediaAttachment(mimeType = MimeTypeUtils.IMAGE_JPEG, uri = URI("https://example.com/2.jpg"))
            )

            val msg = MediaConverter.buildUserMessage("Compare these images", media)

            assertEquals(2, msg.media.size) { "Expected 2 media attachments" }
        }

        @Test
        fun `toSpringAiMedia converts URI attachment correctly`() {
            val attachment = MediaAttachment(
                mimeType = MimeTypeUtils.IMAGE_PNG,
                uri = URI("https://example.com/image.png")
            )

            val springMedia = MediaConverter.toSpringAiMedia(attachment)

            assertEquals(MimeTypeUtils.IMAGE_PNG, springMedia.mimeType) { "MimeType mismatch" }
        }

        @Test
        fun `toSpringAiMedia converts byte data attachment correctly`() {
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
        fun `execute with media should pass UserMessage with media to ChatClient`() = runBlocking {
            // Arrange
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

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are a vision assistant.",
                    userPrompt = "What is in this image?",
                    media = media
                )
            )

            // Assert
            result.assertSuccess()
            assertEquals("I see an image of a cat.", result.content) { "Response content mismatch" }

            // Verify UserMessage was constructed with media
            assertTrue(messagesSlot.isCaptured) { "Messages should be captured" }
            val capturedMessages = messagesSlot.captured
            val userMsg = capturedMessages.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present in messages" }
            assertEquals(1, userMsg!!.media.size) { "UserMessage should have 1 media attachment" }
            assertEquals(MimeTypeUtils.IMAGE_PNG, userMsg.media[0].mimeType) { "Media mimeType mismatch" }
        }

        @Test
        fun `execute without media should pass text-only UserMessage`() = runBlocking {
            // Arrange
            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Hello!")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hi"
                )
            )

            // Assert
            result.assertSuccess()

            val capturedMessages = messagesSlot.captured
            val userMsg = capturedMessages.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present" }
            assertTrue(userMsg!!.media.isEmpty()) { "UserMessage should have no media, got: ${userMsg.media.size}" }
        }

        @Test
        fun `execute with multiple media attachments should include all`() = runBlocking {
            // Arrange
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

            // Act
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Compare images.",
                    userPrompt = "Compare these two images",
                    media = media
                )
            )

            // Assert
            result.assertSuccess()
            val userMsg = messagesSlot.captured.filterIsInstance<UserMessage>().lastOrNull()
            assertNotNull(userMsg) { "UserMessage should be present" }
            assertEquals(2, userMsg!!.media.size) { "Expected 2 media attachments" }
        }
    }
}
