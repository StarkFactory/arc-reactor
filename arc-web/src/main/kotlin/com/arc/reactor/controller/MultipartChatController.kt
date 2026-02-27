package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.persona.PersonaStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux

/**
 * Multipart Chat Controller — file upload endpoint for multimodal LLMs.
 *
 * Validates file count and per-file size before loading bytes into memory,
 * preventing DoS via unbounded file uploads.
 */
@Tag(name = "Chat", description = "AI agent chat endpoints")
@RestController
@RequestMapping("/api/chat")
class MultipartChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null,
    private val authProperties: AuthProperties = AuthProperties(),
    private val properties: AgentProperties = AgentProperties()
) {
    private val systemPromptResolver = SystemPromptResolver(personaStore = personaStore)

    /**
     * Multipart chat - send a message with file attachments (images, audio, etc.)
     *
     * Supports multimodal LLMs by accepting file uploads alongside the text prompt.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat/multipart \
     *   -F "message=What's in this image?" \
     *   -F "files=@photo.png"
     * ```
     */
    @Operation(summary = "Multipart chat with file attachments")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Chat response"),
        ApiResponse(responseCode = "400", description = "Invalid file or multimodal disabled")
    ])
    @PostMapping("/multipart", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun chatMultipart(
        @RequestPart("message") message: String,
        @RequestPart("files") files: List<FilePart>,
        @RequestPart("model", required = false) model: String?,
        @RequestPart("systemPrompt", required = false) systemPrompt: String?,
        @RequestPart("personaId", required = false) personaId: String?,
        @RequestPart("userId", required = false) userId: String?,
        exchange: ServerWebExchange
    ): ChatResponse {
        validateMultimodalEnabled()
        validateFileCount(files.size)

        val mediaAttachments = files.map { file -> readFileWithSizeLimit(file) }

        val resolvedSystemPrompt = systemPromptResolver.resolve(
            personaId = personaId,
            promptTemplateId = null,
            systemPrompt = systemPrompt
        )
        val resolvedUserId = resolveUserId(exchange, userId)
        val resolvedTenantId = TenantContextResolver.resolveTenantId(exchange, authProperties.enabled)

        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = resolvedSystemPrompt,
                userPrompt = message,
                model = model,
                userId = resolvedUserId,
                metadata = mapOf(
                    "channel" to "web",
                    "tenantId" to resolvedTenantId
                ),
                media = mediaAttachments
            )
        )
        return ChatResponse(
            content = result.content,
            success = result.success,
            model = model,
            toolsUsed = result.toolsUsed,
            errorMessage = result.errorMessage
        )
    }

    private fun validateMultimodalEnabled() {
        if (!properties.multimodal.enabled) {
            throw FileSizeLimitException("Multimodal file upload is disabled")
        }
    }

    private fun validateFileCount(count: Int) {
        val max = properties.multimodal.maxFilesPerRequest
        if (count > max) {
            throw FileSizeLimitException("Too many files: $count exceeds limit of $max")
        }
    }

    private suspend fun readFileWithSizeLimit(file: FilePart): MediaAttachment {
        val maxBytes = properties.multimodal.maxFileSizeBytes
        val bytes = collectWithSizeLimit(file.content(), maxBytes, file.filename())

        val mimeType = file.headers().contentType?.let { MimeType(it.type, it.subtype) }
            ?: MimeType("application", "octet-stream")

        return MediaAttachment(
            mimeType = mimeType,
            data = bytes,
            name = file.filename()
        )
    }

    private suspend fun collectWithSizeLimit(
        content: Flux<DataBuffer>,
        maxBytes: Long,
        filename: String
    ): ByteArray {
        var accumulated = 0L
        val checkedContent = content.doOnNext { buffer ->
            accumulated += buffer.readableByteCount()
            if (accumulated > maxBytes) {
                throw FileSizeLimitException("File '$filename' exceeds size limit of ${maxBytes}B")
            }
        }
        return DataBufferUtils.join(checkedContent)
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .awaitSingle()
    }
}

/**
 * Exception thrown when a file upload violates size or count limits.
 * Mapped to HTTP 400 Bad Request by [GlobalExceptionHandler].
 */
class FileSizeLimitException(message: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, message)
