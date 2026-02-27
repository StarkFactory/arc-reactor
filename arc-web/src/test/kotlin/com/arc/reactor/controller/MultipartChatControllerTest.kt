package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.MultimodalProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.auth.AuthProperties
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Flux

class MultipartChatControllerTest {

    private val agentExecutor = mockk<AgentExecutor>()

    private fun mockExchange(
        attributes: MutableMap<String, Any> = mutableMapOf(),
        headers: HttpHeaders = HttpHeaders()
    ): ServerWebExchange {
        val request = mockk<ServerHttpRequest>()
        every { request.headers } returns headers
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns attributes
        every { exchange.request } returns request
        return exchange
    }

    private fun mockFilePart(
        fileName: String = "photo.png",
        contentType: MediaType = MediaType.IMAGE_PNG,
        content: ByteArray = "png".toByteArray()
    ): FilePart {
        val file = mockk<FilePart>()
        val headers = HttpHeaders()
        headers.contentType = contentType
        every { file.headers() } returns headers
        every { file.filename() } returns fileName

        val buffer = DefaultDataBufferFactory.sharedInstance.wrap(content)
        every { file.content() } returns Flux.just(buffer)
        return file
    }

    private fun controllerWith(multimodal: MultimodalProperties = MultimodalProperties()): MultipartChatController =
        MultipartChatController(
            agentExecutor = agentExecutor,
            properties = AgentProperties(multimodal = multimodal)
        )

    @Nested
    inner class TenantAndRouting {

        @Test
        fun `should include tenant metadata in multipart command`() = runTest {
            val controller = MultipartChatController(agentExecutor = agentExecutor)
            val headers = HttpHeaders()
            headers.add("X-Tenant-Id", "tenant-multipart")
            val exchange = mockExchange(headers = headers)

            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val response = controller.chatMultipart(
                message = "describe image",
                files = listOf(mockFilePart()),
                model = "gemini-2.0-flash",
                systemPrompt = null,
                personaId = null,
                userId = null,
                exchange = exchange
            )

            assertTrue(response.success) { "Controller should return successful response from executor" }
            assertEquals("web", commandSlot.captured.metadata["channel"]) {
                "Multipart command should include web channel metadata"
            }
            assertEquals("tenant-multipart", commandSlot.captured.metadata["tenantId"]) {
                "Multipart command should include resolved tenant metadata"
            }
        }

        @Test
        fun `should reject multipart request when auth enabled and tenant is missing`() = runTest {
            val strictController = MultipartChatController(
                agentExecutor = agentExecutor,
                authProperties = AuthProperties(enabled = true)
            )

            val exception = try {
                strictController.chatMultipart(
                    message = "describe image",
                    files = listOf(mockFilePart()),
                    model = null,
                    systemPrompt = null,
                    personaId = null,
                    userId = null,
                    exchange = mockExchange()
                )
                throw AssertionError("Auth-enabled multipart should reject missing tenant context")
            } catch (e: ServerWebInputException) {
                e
            }

            assertTrue(exception.reason?.contains("Missing tenant context") == true) {
                "Auth-enabled multipart requests should fail-close without tenant context"
            }
        }
    }

    @Nested
    inner class FileSizeValidation {

        @Test
        @Tag("regression")
        fun `should reject file exceeding configured size limit`() = runTest {
            val oversizedContent = ByteArray(1025) { 0x42 }
            val controller = controllerWith(MultimodalProperties(maxFileSizeBytes = 1024))

            val ex = assertThrows<FileSizeLimitException>("Expected FileSizeLimitException for oversized file") {
                controller.chatMultipart(
                    message = "describe image",
                    files = listOf(mockFilePart(content = oversizedContent)),
                    model = null,
                    systemPrompt = null,
                    personaId = null,
                    userId = null,
                    exchange = mockExchange()
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode) {
                "Oversized file upload should result in 400 Bad Request"
            }
            assertTrue(ex.reason?.contains("exceeds size limit") == true) {
                "Error reason should mention size limit exceeded"
            }
        }

        @Test
        fun `should process file within maxFileSizeBytes`() = runTest {
            val smallContent = ByteArray(512) { 0x42 }
            val controller = controllerWith(MultimodalProperties(maxFileSizeBytes = 1024))

            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val response = controller.chatMultipart(
                message = "describe image",
                files = listOf(mockFilePart(content = smallContent)),
                model = null,
                systemPrompt = null,
                personaId = null,
                userId = null,
                exchange = mockExchange()
            )

            assertTrue(response.success) { "File within size limit should be processed successfully" }
        }
    }

    @Nested
    inner class FileCountValidation {

        @Test
        @Tag("regression")
        fun `should reject request when file count exceeds configured limit`() = runTest {
            val controller = controllerWith(MultimodalProperties(maxFilesPerRequest = 2))
            val files = listOf(mockFilePart("a.png"), mockFilePart("b.png"), mockFilePart("c.png"))

            val ex = assertThrows<FileSizeLimitException>("Expected FileSizeLimitException for too many files") {
                controller.chatMultipart(
                    message = "describe images",
                    files = files,
                    model = null,
                    systemPrompt = null,
                    personaId = null,
                    userId = null,
                    exchange = mockExchange()
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode) {
                "Exceeding file count limit should result in 400 Bad Request"
            }
            assertTrue(ex.reason?.contains("Too many files") == true) {
                "Error reason should mention file count exceeded"
            }
        }

        @Test
        fun `should allow request at maxFilesPerRequest boundary`() = runTest {
            val controller = controllerWith(MultimodalProperties(maxFilesPerRequest = 2))
            val files = listOf(mockFilePart("a.png"), mockFilePart("b.png"))

            coEvery { agentExecutor.execute(any()) } returns AgentResult.success("ok")

            val response = controller.chatMultipart(
                message = "describe images",
                files = files,
                model = null,
                systemPrompt = null,
                personaId = null,
                userId = null,
                exchange = mockExchange()
            )

            assertTrue(response.success) { "Request at file count limit should be processed successfully" }
        }
    }

    @Nested
    inner class MultimodalDisabled {

        @Test
        fun `should return 400 when multimodal is disabled`() = runTest {
            val controller = controllerWith(MultimodalProperties(enabled = false))

            val ex = assertThrows<FileSizeLimitException>(
                "Expected FileSizeLimitException when multimodal is disabled"
            ) {
                controller.chatMultipart(
                    message = "describe image",
                    files = listOf(mockFilePart()),
                    model = null,
                    systemPrompt = null,
                    personaId = null,
                    userId = null,
                    exchange = mockExchange()
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode) {
                "Disabled multimodal endpoint should result in 400 Bad Request"
            }
            assertTrue(ex.reason?.contains("disabled") == true) {
                "Error reason should indicate multimodal is disabled"
            }
        }
    }
}
