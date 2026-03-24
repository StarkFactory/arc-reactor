package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.PromptTemplateStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactive.asFlow
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
 * Multipart 채팅 컨트롤러 -- 멀티모달 LLM용 파일 업로드 엔드포인트.
 *
 * 파일을 메모리에 로드하기 전에 파일 수와 개별 파일 크기를 검증하여
 * 무제한 파일 업로드로 인한 DoS를 방지합니다.
 *
 * @see AgentExecutor
 */
@Tag(name = "Chat", description = "AI agent chat endpoints")
@RestController
@RequestMapping("/api/chat")
class MultipartChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null,
    private val promptTemplateStore: PromptTemplateStore? = null,
    private val properties: AgentProperties = AgentProperties()
) {
    private val systemPromptResolver = SystemPromptResolver(
        personaStore = personaStore,
        promptTemplateStore = promptTemplateStore
    )

    /**
     * 파일 첨부와 함께 메시지를 전송하는 멀티파트 채팅.
     *
     * 텍스트 프롬프트와 함께 이미지, 오디오 등 파일 업로드를 수용하여
     * 멀티모달 LLM을 지원합니다.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat/multipart \
     *   -F "message=What's in this image?" \
     *   -F "files=@photo.png"
     * ```
     */
    @Operation(summary = "파일 첨부 멀티파트 채팅")
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
        @RequestPart("sessionId", required = false) sessionId: String?,
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
        val resolvedTenantId = TenantContextResolver.resolveTenantId(exchange)

        val metadata = buildMap<String, Any> {
            put("channel", "web")
            put("tenantId", resolvedTenantId)
            sessionId?.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
        }

        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = resolvedSystemPrompt,
                userPrompt = message,
                model = model,
                userId = resolvedUserId,
                metadata = metadata,
                media = mediaAttachments
            )
        )
        return result.toChatResponse(model)
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
        val chunks = mutableListOf<ByteArray>()
        var accumulated = 0L
        content.asFlow().collect { buffer ->
            accumulated += buffer.readableByteCount()
            if (accumulated > maxBytes) {
                DataBufferUtils.release(buffer)
                throw FileSizeLimitException("File '$filename' exceeds size limit of ${maxBytes}B")
            }
            val bytes = ByteArray(buffer.readableByteCount())
            buffer.read(bytes)
            DataBufferUtils.release(buffer)
            chunks.add(bytes)
        }
        val result = ByteArray(accumulated.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}

/**
 * 파일 업로드가 크기 또는 개수 제한을 위반했을 때 발생하는 예외.
 * [GlobalExceptionHandler]에 의해 HTTP 400 Bad Request로 매핑됩니다.
 */
class FileSizeLimitException(message: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, message)
