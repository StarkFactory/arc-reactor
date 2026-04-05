package com.arc.reactor.controller

import com.arc.reactor.multibot.SlackBotInstance
import com.arc.reactor.multibot.SlackBotInstanceStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

/**
 * Slack 봇 인스턴스 관리 API.
 *
 * 멀티 봇 페르소나 구조에서 개별 Slack 봇을 관리한다.
 * 각 봇은 고유한 Bot Token, App Token, 페르소나를 가진다.
 *
 * 모든 엔드포인트는 관리자 권한이 필요하다.
 */
@Tag(name = "Slack Bots", description = "멀티 Slack 봇 인스턴스 관리")
@RestController
@RequestMapping("/api/admin/slack-bots")
class SlackBotController(
    private val store: SlackBotInstanceStore
) {

    @Operation(summary = "전체 봇 인스턴스 목록 조회")
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val bots = store.list().map { it.toResponse() }
        return ResponseEntity.ok(bots)
    }

    @Operation(summary = "봇 인스턴스 상세 조회")
    @GetMapping("/{id}")
    fun get(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val bot = store.get(id) ?: return notFoundResponse("봇 인스턴스를 찾을 수 없습니다: $id")
        return ResponseEntity.ok(bot.toResponse())
    }

    @Operation(summary = "봇 인스턴스 등록")
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateSlackBotRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val instance = SlackBotInstance(
            id = UUID.randomUUID().toString(),
            name = request.name,
            botToken = request.botToken,
            appToken = request.appToken,
            personaId = request.personaId,
            defaultChannel = request.defaultChannel,
            enabled = request.enabled ?: true
        )
        val saved = store.save(instance)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Operation(summary = "봇 인스턴스 수정")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateSlackBotRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val existing = store.get(id) ?: return notFoundResponse("봇 인스턴스를 찾을 수 없습니다: $id")
        val updated = existing.copy(
            name = request.name ?: existing.name,
            botToken = request.botToken ?: existing.botToken,
            appToken = request.appToken ?: existing.appToken,
            personaId = request.personaId ?: existing.personaId,
            defaultChannel = request.defaultChannel ?: existing.defaultChannel,
            enabled = request.enabled ?: existing.enabled,
            updatedAt = Instant.now()
        )
        val saved = store.save(updated)
        return ResponseEntity.ok(saved.toResponse())
    }

    @Operation(summary = "봇 인스턴스 삭제")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.get(id) ?: return notFoundResponse("봇 인스턴스를 찾을 수 없습니다: $id")
        store.delete(id)
        return ResponseEntity.noContent().build()
    }

    /** 토큰 마스킹하여 응답 (보안) */
    private fun SlackBotInstance.toResponse() = SlackBotResponse(
        id = id,
        name = name,
        botTokenMasked = botToken.take(15) + "...",
        appTokenMasked = appToken.take(15) + "...",
        personaId = personaId,
        defaultChannel = defaultChannel,
        enabled = enabled,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )
}

data class CreateSlackBotRequest(
    @field:NotBlank(message = "name은 필수입니다")
    @field:Size(max = 100)
    val name: String,
    @field:NotBlank(message = "botToken은 필수입니다")
    val botToken: String,
    @field:NotBlank(message = "appToken은 필수입니다")
    val appToken: String,
    @field:NotBlank(message = "personaId는 필수입니다")
    val personaId: String,
    val defaultChannel: String? = null,
    val enabled: Boolean? = true
)

data class UpdateSlackBotRequest(
    val name: String? = null,
    val botToken: String? = null,
    val appToken: String? = null,
    val personaId: String? = null,
    val defaultChannel: String? = null,
    val enabled: Boolean? = null
)

data class SlackBotResponse(
    val id: String,
    val name: String,
    val botTokenMasked: String,
    val appTokenMasked: String,
    val personaId: String,
    val defaultChannel: String?,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String
)
