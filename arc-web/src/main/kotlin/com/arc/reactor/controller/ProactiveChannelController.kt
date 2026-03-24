package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.context.ApplicationContext
import org.springframework.util.ClassUtils
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 프로액티브 채널 관리 컨트롤러.
 *
 * Slack 프로액티브 모니터링 대상 채널을 추가/제거합니다.
 * arc-web가 arc-slack 없이도 기동 가능하도록 Slack 타입 직접 참조는 피합니다.
 */
@Tag(name = "Proactive Channels", description = "Manage proactive monitoring channels (ADMIN)")
@RestController
@RequestMapping("/api/proactive-channels")
@ConditionalOnClass(name = [PROACTIVE_CHANNEL_STORE_CLASS_NAME])
@ConditionalOnProperty(
    prefix = "arc.reactor.slack", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@ConditionalOnBean(type = [PROACTIVE_CHANNEL_STORE_CLASS_NAME])
class ProactiveChannelController(
    private val applicationContext: ApplicationContext,
    private val adminAuditStore: AdminAuditStore
) {

    private val store: Any by lazy {
        val storeClass = ClassUtils.resolveClassName(PROACTIVE_CHANNEL_STORE_CLASS_NAME, javaClass.classLoader)
        applicationContext.getBean(storeClass)
    }

    /** 등록된 프로액티브 채널 전체 목록을 조회한다. */
    @Operation(summary = "프로액티브 채널 목록 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of proactive channels"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(ProactiveChannelStoreBridge.list(store).map { it.toResponse() })
    }

    /** 프로액티브 모니터링 대상에 채널을 추가한다. */
    @Operation(summary = "프로액티브 모니터링 채널 추가 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Channel added"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Channel already exists")
    ])
    @PostMapping
    fun add(
        @Valid @RequestBody request: AddProactiveChannelRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (ProactiveChannelStoreBridge.isEnabled(store, request.channelId)) {
            return conflictResponse("Channel already in proactive list")
        }

        val channel = ProactiveChannelStoreBridge.add(store, request.channelId, request.channelName)
        recordAdminAudit(
            store = adminAuditStore,
            category = "proactive_channel",
            action = "ADD",
            actor = currentActor(exchange),
            resourceType = "proactive_channel",
            resourceId = request.channelId,
            detail = "channelName=${request.channelName.orEmpty()}"
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(channel.toResponse())
    }

    /** 프로액티브 모니터링 대상에서 채널을 제거한다. */
    @Operation(summary = "프로액티브 모니터링 채널 제거 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Channel removed"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Channel not found")
    ])
    @DeleteMapping("/{channelId}")
    fun remove(
        @PathVariable channelId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (!ProactiveChannelStoreBridge.remove(store, channelId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(
                    error = "Channel not found in proactive list",
                    timestamp = java.time.Instant.now().toString()
                ))
        }

        recordAdminAudit(
            store = adminAuditStore,
            category = "proactive_channel",
            action = "REMOVE",
            actor = currentActor(exchange),
            resourceType = "proactive_channel",
            resourceId = channelId,
            detail = "removed"
        )
        return ResponseEntity.noContent().build()
    }
}

data class AddProactiveChannelRequest(
    @field:NotBlank(message = "channelId must not be blank")
    @field:Size(max = 50, message = "channelId must not exceed 50 characters")
    val channelId: String,

    @field:Size(max = 200, message = "channelName must not exceed 200 characters")
    val channelName: String? = null
)

data class ProactiveChannelResponse(
    val channelId: String,
    val channelName: String?,
    val addedAt: Long
)

private data class ProactiveChannelView(
    val channelId: String,
    val channelName: String?,
    val addedAt: Long
)

private fun ProactiveChannelView.toResponse() = ProactiveChannelResponse(
    channelId = channelId,
    channelName = channelName,
    addedAt = addedAt
)

private object ProactiveChannelStoreBridge {

    fun list(store: Any): List<ProactiveChannelView> {
        val result = invoke(store, "list") as? Iterable<*>
            ?: error("Unexpected proactive channel list result")
        return result.map { channel ->
            requireNotNull(channel) { "Proactive channel entry must not be null" }
            toView(channel)
        }
    }

    fun isEnabled(store: Any, channelId: String): Boolean =
        invoke(store, "isEnabled", channelId) as? Boolean
            ?: error("Unexpected proactive channel enabled result")

    fun add(store: Any, channelId: String, channelName: String?): ProactiveChannelView =
        toView(invoke(store, "add", channelId, channelName))

    fun remove(store: Any, channelId: String): Boolean =
        invoke(store, "remove", channelId) as? Boolean
            ?: error("Unexpected proactive channel remove result")

    private fun invoke(store: Any, methodName: String, vararg args: Any?): Any {
        val parameterTypes = args.map { arg ->
            when (arg) {
                null -> String::class.java
                else -> arg.javaClass
            }
        }.toTypedArray()
        val method = try {
            store.javaClass.getMethod(methodName, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException(
                "ProactiveChannelStore does not support method: $methodName(${parameterTypes.joinToString { it.simpleName }})", e
            )
        }
        return requireNotNull(method.invoke(store, *args)) {
            "Proactive channel store method returned null: $methodName"
        }
    }

    private fun toView(channel: Any): ProactiveChannelView {
        val type = channel.javaClass
        val channelId = type.getMethod("getChannelId").invoke(channel) as String
        val channelName = type.getMethod("getChannelName").invoke(channel) as String?
        val addedAt = type.getMethod("getAddedAt").invoke(channel) as Long
        return ProactiveChannelView(channelId = channelId, channelName = channelName, addedAt = addedAt)
    }
}

private const val PROACTIVE_CHANNEL_STORE_CLASS_NAME = "com.arc.reactor.slack.proactive.ProactiveChannelStore"
