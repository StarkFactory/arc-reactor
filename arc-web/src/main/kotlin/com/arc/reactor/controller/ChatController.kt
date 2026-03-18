package com.arc.reactor.controller

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaAttachment
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.memory.MemoryStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.persona.PersonaStore
import mu.KotlinLogging
import com.arc.reactor.prompt.PromptTemplateStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Flux
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

/**
 * 채팅 API 컨트롤러.
 *
 * AI 에이전트와의 대화를 위한 REST API를 제공합니다.
 *
 * ## 엔드포인트
 * - POST /api/chat        : 표준 응답 (전체 응답을 한 번에 반환)
 * - POST /api/chat/stream  : 스트리밍 응답 (SSE, 실시간 토큰 단위 전송)
 *
 * ## SSE 이벤트 타입 (스트리밍)
 * - `message` : LLM 텍스트 토큰 청크
 * - `tool_start` : 도구 실행 시작 (data = 도구 이름)
 * - `tool_end` : 도구 실행 완료 (data = 도구 이름)
 * - `error` : 오류 발생 (data = 오류 메시지)
 * - `done` : 스트림 완료
 *
 * @see AgentExecutor
 * @see IntentResolver
 */
@Tag(name = "Chat", description = "AI agent chat endpoints")
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agentExecutor: AgentExecutor,
    private val personaStore: PersonaStore? = null,
    private val promptTemplateStore: PromptTemplateStore? = null,
    private val properties: AgentProperties = AgentProperties(),
    private val intentResolver: IntentResolver? = null,
    private val memoryStore: MemoryStore? = null
) {
    private val systemPromptResolver = SystemPromptResolver(
        personaStore = personaStore,
        promptTemplateStore = promptTemplateStore
    )

    /**
     * 표준 채팅 -- 전체 응답을 한 번에 반환한다.
     *
     * 요청 흐름: Guard -> Hook(BeforeStart) -> ReAct Loop(LLM <-> Tool) -> Hook(AfterComplete) -> 응답.
     * 인텐트 분류가 활성화되어 있으면 사용자 메시지를 분류한 뒤 프로필을 적용한다.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "What is 3 + 5?"}'
     * ```
     */
    @Operation(summary = "메시지를 전송하고 전체 응답을 수신")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "채팅 응답"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
        ApiResponse(responseCode = "429", description = "요청 빈도 제한 초과"),
        ApiResponse(responseCode = "500", description = "서버 또는 LLM 오류"),
        ApiResponse(responseCode = "503", description = "서비스 이용 불가 (서킷 브레이커 열림)"),
        ApiResponse(responseCode = "422", description = "출력 가드에 의해 거부되었거나 응답이 너무 짧음"),
        ApiResponse(responseCode = "504", description = "요청 시간 초과")
    ])
    @PostMapping
    suspend fun chat(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<ChatResponse> {
        val command = buildCommand(request, exchange)
        val result = agentExecutor.execute(command)
        val response = result.toChatResponse(command.model)
        val status = if (result.success) HttpStatus.OK else mapErrorCodeToStatus(result.errorCode)
        return ResponseEntity.status(status).body(response)
    }

    /**
     * 스트리밍 채팅 -- 타입이 지정된 SSE 이벤트를 통한 실시간 응답.
     *
     * SSE 이벤트 타입:
     * - `event: message` + `data: <텍스트>` - LLM 텍스트 토큰
     * - `event: tool_start` + `data: <도구_이름>` - 도구 실행 시작
     * - `event: tool_end` + `data: <도구_이름>` - 도구 실행 완료
     * - `event: done` + `data:` - 스트림 완료
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/chat/stream \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "What time is it now?"}' \
     *   --no-buffer
     * ```
     */
    @Operation(
        summary = "스트리밍 채팅 (SSE)",
        description = "Server-Sent Events를 통한 실시간 스트리밍 응답.\n\n" +
            "SSE 이벤트 타입:\n" +
            "- `message` : LLM 텍스트 토큰 청크\n" +
            "- `tool_start` : 도구 실행 시작 (data = 도구 이름)\n" +
            "- `tool_end` : 도구 실행 완료 (data = 도구 이름)\n" +
            "- `error` : 오류 발생 (data = 오류 메시지)\n" +
            "- `done` : 스트림 완료",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream",
                content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)]
            )
        ]
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
        ApiResponse(responseCode = "500", description = "서버 또는 LLM 오류")
    ])
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun chatStream(
        @Valid @RequestBody request: ChatRequest,
        exchange: ServerWebExchange
    ): Flux<ServerSentEvent<String>> {
        val command = buildCommand(request, exchange)
        val flow: Flow<String> = agentExecutor.executeStream(command)

        val eventFlow: Flow<ServerSentEvent<String>> = flow
            .map { chunk -> toServerSentEvent(chunk) }
            .onCompletion {
                emit(
                    ServerSentEvent.builder<String>()
                        .event("done")
                        .data("")
                        .build()
                )
            }

        val userId = resolveUserId(exchange, request.userId)
        return eventFlow.asFlux()
            .doOnCancel { logger.debug { "SSE stream cancelled by client (userId=$userId)" } }
    }

    /** 요청과 exchange로부터 AgentCommand를 생성한다. 인텐트 프로필도 적용한다. */
    private suspend fun buildCommand(
        request: ChatRequest,
        exchange: ServerWebExchange
    ): AgentCommand {
        val base = AgentCommand(
            systemPrompt = resolveSystemPrompt(request),
            userPrompt = request.message,
            model = request.model,
            userId = resolveUserId(exchange, request.userId),
            metadata = resolveMetadata(request, exchange),
            responseFormat = request.responseFormat ?: ResponseFormat.TEXT,
            responseSchema = request.responseSchema,
            media = resolveMediaUrls(request.mediaUrls)
        )
        return applyIntentProfile(base, request)
    }

    /** 청크 문자열을 파싱하여 적절한 SSE 이벤트 타입(message, tool_start 등)으로 변환한다. */
    private fun toServerSentEvent(chunk: String): ServerSentEvent<String> {
        val parsed = StreamEventMarker.parse(chunk)
        return if (parsed != null) {
            ServerSentEvent.builder<String>()
                .event(parsed.first)
                .data(parsed.second)
                .build()
        } else {
            ServerSentEvent.builder<String>()
                .event("message")
                .data(chunk)
                .build()
        }
    }

    /**
     * 인텐트 분류가 활성화되어 있으면 커맨드에 인텐트 프로필을 적용한다.
     * 인텐트 기능이 비활성화되었거나 확신 있는 매칭이 없으면 원래 커맨드를 그대로 반환한다.
     */
    private suspend fun applyIntentProfile(command: AgentCommand, request: ChatRequest): AgentCommand {
        if (intentResolver == null) return command

        val startTime = System.currentTimeMillis()
        val context = buildClassificationContext(command, request)
        val resolved = intentResolver.resolve(command.userPrompt, context)
        val durationMs = System.currentTimeMillis() - startTime
        val metadata = mapOf(
            IntentResolver.METADATA_INTENT_RESOLUTION_ATTEMPTED to true,
            IntentResolver.METADATA_INTENT_RESOLUTION_DURATION_MS to durationMs
        )
        if (resolved == null) {
            return command.copy(metadata = command.metadata + metadata)
        }
        val applied = intentResolver.applyProfile(command, resolved)
        return applied.copy(metadata = applied.metadata + metadata)
    }

    /**
     * 요청 정보와 대화 이력으로부터 인텐트 분류 컨텍스트를 생성한다.
     * sessionId가 있고 memoryStore가 존재하면 최근 4건의 대화 이력을 로드한다.
     */
    private fun buildClassificationContext(command: AgentCommand, request: ChatRequest): ClassificationContext {
        val sessionId = request.metadata?.get("sessionId") as? String
        val historyLoader = if (sessionId != null && memoryStore != null) {
            {
                memoryStore.get(sessionId)
                    ?.getHistory()
                    ?.takeLast(4)
                    ?: emptyList()
            }
        } else {
            null
        }

        return ClassificationContext(
            userId = command.userId,
            metadata = request.metadata ?: emptyMap(),
            conversationHistoryLoader = historyLoader
        )
    }

    /**
     * 시스템 프롬프트를 우선순위에 따라 해석한다:
     * 1. personaId (페르소나에 연결된 프롬프트)
     * 2. promptTemplateId (프롬프트 템플릿의 활성 버전)
     * 3. request.systemPrompt (요청에 직접 지정한 프롬프트)
     * 4. 기본 페르소나 (isDefault=true)
     * 5. 하드코딩된 폴백 프롬프트
     */
    private fun resolveSystemPrompt(request: ChatRequest): String {
        return systemPromptResolver.resolve(
            personaId = request.personaId,
            promptTemplateId = request.promptTemplateId,
            systemPrompt = request.systemPrompt
        )
    }

    /**
     * 프롬프트 템플릿 사용 시 버전 정보를 포함하여 메타데이터를 구성한다.
     * 코루틴 안전한 전파를 위해 exchange에서 tenantId를 추출하여 포함한다.
     *
     * 메타데이터 보강 순서: channel -> requesterIdentity -> tenantId -> promptVersion.
     * WHY: Hook이 코루틴 내에서 실행되므로 ThreadLocal 기반 컨텍스트 대신
     * 메타데이터 맵을 통해 tenantId를 전파해야 한다.
     */
    private fun resolveMetadata(request: ChatRequest, exchange: ServerWebExchange): Map<String, Any> {
        val base = request.metadata ?: emptyMap()
        val merged = buildMap {
            putAll(base)
            if (!containsKey("channel")) put("channel", "web")
            putAll(resolveRequesterIdentity(base, exchange))
            put("tenantId", TenantContextResolver.resolveTenantId(exchange))
        }
        return enrichWithPromptVersion(merged, request)
    }

    /** exchange에서 요청자 신원 정보를 추출하여 메타데이터에 추가한다. 이미 존재하면 덮어쓰지 않는다. */
    private fun resolveRequesterIdentity(base: Map<String, Any>, exchange: ServerWebExchange): Map<String, Any> {
        if (containsRequesterIdentity(base)) return base
        val email = resolveRequesterEmailFromExchange(exchange)
        val accountId = resolveRequesterAccountIdFromExchange(exchange)
        val withAccount = if (accountId == null) base else base + mapOf(
            "requesterAccountId" to accountId, "accountId" to accountId
        )
        return email?.let { withAccount + mapOf("requesterEmail" to it, "userEmail" to it) } ?: withAccount
    }

    /** 프롬프트 템플릿 버전 정보로 메타데이터를 보강한다. */
    private fun enrichWithPromptVersion(metadata: Map<String, Any>, request: ChatRequest): Map<String, Any> {
        val templateId = resolveEffectivePromptTemplateId(request) ?: return metadata
        if (promptTemplateStore == null) return metadata
        val activeVersion = try {
            promptTemplateStore.getActiveVersion(templateId)
        } catch (e: Exception) {
            logger.warn(e) { "Prompt template metadata lookup failed for id='$templateId'" }
            null
        } ?: return metadata
        return metadata + mapOf(
            "promptTemplateId" to templateId,
            "promptVersionId" to activeVersion.id,
            "promptVersion" to activeVersion.version
        )
    }

    /** 요청 메타데이터에 요청자 신원 정보가 이미 포함되어 있는지 확인한다. Slack 등 외부 채널에서 이미 설정된 경우 덮어쓰지 않기 위함. */
    private fun containsRequesterIdentity(metadata: Map<String, Any>): Boolean {
        return metadata.containsKey("requesterEmail") ||
            metadata.containsKey("userEmail") ||
            metadata.containsKey("slackUserEmail") ||
            metadata.containsKey("requesterAccountId") ||
            metadata.containsKey("accountId")
    }

    /** JWT 인증 필터가 설정한 사용자 이메일을 exchange 속성에서 추출한다. */
    private fun resolveRequesterEmailFromExchange(exchange: ServerWebExchange): String? {
        return (exchange.attributes[JwtAuthWebFilter.USER_EMAIL_ATTRIBUTE] as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** JWT 인증 필터가 설정한 사용자 계정 ID를 exchange 속성에서 추출한다. */
    private fun resolveRequesterAccountIdFromExchange(exchange: ServerWebExchange): String? {
        return (exchange.attributes[JwtAuthWebFilter.USER_ACCOUNT_ID_ATTRIBUTE] as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** 유효한 프롬프트 템플릿 ID를 결정한다. 페르소나에 연결된 템플릿 > 요청에 직접 지정한 템플릿 순으로 해석한다. */
    private fun resolveEffectivePromptTemplateId(request: ChatRequest): String? {
        val linkedTemplateId = request.personaId
            ?.let { personaId ->
                try {
                    personaStore?.get(personaId)?.promptTemplateId
                } catch (e: Exception) {
                    logger.warn(e) { "Persona prompt template lookup failed for personaId='$personaId'" }
                    null
                }
            }
        return linkedTemplateId ?: request.promptTemplateId
    }

    /**
     * [MediaUrlRequest] 목록을 URI 기반 [MediaAttachment] 목록으로 변환한다.
     * 멀티모달이 비활성화되어 있으면 빈 목록을 반환한다.
     */
    private fun resolveMediaUrls(mediaUrls: List<MediaUrlRequest>?): List<MediaAttachment> {
        if (!properties.multimodal.enabled) return emptyList()
        if (mediaUrls.isNullOrEmpty()) return emptyList()
        return mediaUrls.map { req ->
            MediaAttachment(
                mimeType = parseMimeType(req.mimeType),
                uri = parseMediaUri(req.url)
            )
        }
    }

    /** MIME 타입 문자열을 파싱한다. 유효하지 않으면 400 Bad Request 예외를 발생시킨다. */
    private fun parseMimeType(raw: String): MimeType {
        val normalized = raw.trim()
        return try {
            MimeType.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            throw ServerWebInputException("Invalid media mimeType: $raw")
        }
    }

    /**
     * 미디어 URL 문자열을 URI로 파싱하고 안전성을 검증한다.
     * WHY: SSRF 방지를 위해 절대 URL, http/https 스킴, 유효한 호스트만 허용한다.
     */
    private fun parseMediaUri(raw: String): URI {
        val normalized = raw.trim()
        val uri = try {
            URI(normalized)
        } catch (_: URISyntaxException) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        if (!uri.isAbsolute) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        if (uri.host.isNullOrBlank()) {
            throw ServerWebInputException("Invalid media URL: $raw")
        }
        return uri
    }

}

/**
 * 에이전트 오류 코드를 HTTP 상태 코드로 매핑한다.
 * WHY: 오류 유형에 따라 클라이언트가 적절한 재시도/백오프 전략을 결정할 수 있도록
 * 의미 있는 HTTP 상태를 반환해야 한다.
 */
internal fun mapErrorCodeToStatus(errorCode: AgentErrorCode?): HttpStatus {
    return when (errorCode) {
        AgentErrorCode.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
        AgentErrorCode.GUARD_REJECTED, AgentErrorCode.HOOK_REJECTED -> HttpStatus.FORBIDDEN
        AgentErrorCode.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        AgentErrorCode.CIRCUIT_BREAKER_OPEN -> HttpStatus.SERVICE_UNAVAILABLE
        AgentErrorCode.CONTEXT_TOO_LONG -> HttpStatus.BAD_REQUEST
        AgentErrorCode.OUTPUT_GUARD_REJECTED, AgentErrorCode.OUTPUT_TOO_SHORT -> HttpStatus.UNPROCESSABLE_ENTITY
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }
}

/** 채팅 요청 DTO. */
data class ChatRequest(
    @field:NotBlank(message = "message must not be blank")
    @field:Size(max = 50000, message = "message must not exceed 50000 characters")
    val message: String,
    val model: String? = null,
    @field:Size(max = 10000, message = "systemPrompt must not exceed 10000 characters")
    val systemPrompt: String? = null,
    val personaId: String? = null,
    val promptTemplateId: String? = null,
    val userId: String? = null,
    @field:Size(max = 20, message = "metadata must not exceed 20 entries")
    val metadata: Map<String, Any>? = null,
    val responseFormat: ResponseFormat? = null,
    @field:Size(max = 10000, message = "responseSchema must not exceed 10000 characters")
    val responseSchema: String? = null,
    val mediaUrls: List<MediaUrlRequest>? = null
)

/**
 * JSON 기반 멀티모달 요청을 위한 미디어 URL 참조.
 *
 * ```json
 * {
 *   "message": "Describe this image",
 *   "mediaUrls": [
 *     { "url": "https://example.com/photo.png", "mimeType": "image/png" }
 *   ]
 * }
 * ```
 */
data class MediaUrlRequest(
    val url: String,
    val mimeType: String
)

/** 채팅 응답 DTO. */
data class ChatResponse(
    val content: String?,
    val success: Boolean,
    val model: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val errorMessage: String? = null,
    val grounded: Boolean? = null,
    val verifiedSourceCount: Int? = null,
    val blockReason: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/** [AgentResult]를 [ChatResponse]로 변환한다. grounded/verifiedSourceCount 등 신뢰도 정보를 포함한다. */
internal fun AgentResult.toChatResponse(model: String?): ChatResponse {
    return ChatResponse(
        content = content,
        success = success,
        model = model,
        toolsUsed = toolsUsed,
        errorMessage = errorMessage,
        grounded = metadata.booleanValue("grounded"),
        verifiedSourceCount = metadata.intValue("verifiedSourceCount"),
        blockReason = metadata.stringValue("blockReason"),
        metadata = metadata
    )
}

private fun Map<String, Any>.booleanValue(key: String): Boolean? {
    return when (val value = this[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}

private fun Map<String, Any>.intValue(key: String): Int? {
    return when (val value = this[key]) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun Map<String, Any>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf(String::isNotBlank)
}
