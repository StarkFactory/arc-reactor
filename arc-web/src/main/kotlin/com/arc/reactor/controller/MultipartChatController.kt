package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.persona.PersonaStore
import mu.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

/**
 * Multipart Chat Controller — file upload endpoint for multimodal LLMs.
 *
 * Only registered when `arc.reactor.multimodal.enabled=true` (default).
 * Set `arc.reactor.multimodal.enabled=false` to disable file uploads entirely.
 */
@Tag(name = "Chat", description = "AI agent chat endpoints")
@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(
    prefix = "arc.reactor.multimodal",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class MultipartChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null,
    private val authProperties: AuthProperties = AuthProperties()
) {

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
        val mediaAttachments = files.map { file -> filePartToMediaAttachment(file) }

        val resolvedSystemPrompt = resolveSystemPrompt(systemPrompt, personaId)
        val resolvedUserId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: userId ?: "anonymous"
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

    private fun resolveSystemPrompt(systemPrompt: String?, personaId: String?): String {
        personaId?.let { resolvePersonaPromptSafely(it)?.let { prompt -> return prompt } }
        if (!systemPrompt.isNullOrBlank()) return systemPrompt
        resolveDefaultPersonaPromptSafely()?.let { return it }
        return ChatController.DEFAULT_SYSTEM_PROMPT
    }

    private fun resolvePersonaPromptSafely(personaId: String): String? {
        return try {
            personaStore?.get(personaId)?.systemPrompt
        } catch (e: Exception) {
            logger.warn(e) { "Persona lookup failed for personaId='$personaId'; using fallback prompt" }
            null
        }
    }

    private fun resolveDefaultPersonaPromptSafely(): String? {
        return try {
            personaStore?.getDefault()?.systemPrompt
        } catch (e: Exception) {
            logger.warn(e) { "Default persona lookup failed in multipart chat; using fallback prompt" }
            null
        }
    }

    private suspend fun filePartToMediaAttachment(file: FilePart): MediaAttachment {
        val bytes = DataBufferUtils.join(file.content())
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .awaitSingle()

        val mimeType = file.headers().contentType?.let { MimeType(it.type, it.subtype) }
            ?: MimeType("application", "octet-stream")

        return MediaAttachment(
            mimeType = mimeType,
            data = bytes,
            name = file.filename()
        )
    }
}
