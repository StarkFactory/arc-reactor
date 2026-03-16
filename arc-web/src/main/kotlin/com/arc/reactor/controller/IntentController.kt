package com.arc.reactor.controller

import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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

/**
 * 인텐트 관리 API 컨트롤러.
 *
 * 인텐트 정의를 관리하는 REST API를 제공합니다.
 * `arc.reactor.intent.enabled=true`일 때만 사용 가능합니다.
 *
 * ## 엔드포인트
 * - GET    /api/intents          : 전체 인텐트 목록 조회
 * - GET    /api/intents/{name}   : 이름으로 인텐트 조회
 * - POST   /api/intents          : 새 인텐트 생성 (관리자)
 * - PUT    /api/intents/{name}   : 기존 인텐트 수정 (관리자)
 * - DELETE /api/intents/{name}   : 인텐트 삭제 (관리자)
 *
 * @see IntentRegistry
 */
@Tag(name = "Intents", description = "Intent definition management")
@RestController
@RequestMapping("/api/intents")
@ConditionalOnProperty(prefix = "arc.reactor.intent", name = ["enabled"], havingValue = "true")
class IntentController(
    private val intentRegistry: IntentRegistry
) {

    /** 전체 인텐트 정의 목록을 조회한다. */
    @Operation(summary = "전체 인텐트 정의 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of intent definitions"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listIntents(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(intentRegistry.list().map { it.toResponse() })
    }

    /** 이름으로 인텐트 정의를 조회한다. */
    @Operation(summary = "이름으로 인텐트 정의 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Intent definition"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Intent not found")
    ])
    @GetMapping("/{intentName}")
    fun getIntent(
        @PathVariable intentName: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val intent = intentRegistry.get(intentName) ?: return notFoundResponse("Intent not found: $intentName")
        return ResponseEntity.ok(intent.toResponse())
    }

    /** 새 인텐트 정의를 생성한다. 동일 이름이 이미 존재하면 409를 반환한다. */
    @Operation(summary = "새 인텐트 정의 생성 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Intent created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "409", description = "Intent already exists")
    ])
    @PostMapping
    fun createIntent(
        @Valid @RequestBody request: CreateIntentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        if (intentRegistry.get(request.name) != null) {
            return conflictResponse("Intent '${request.name}' already exists")
        }

        val intent = request.toDefinition()
        val saved = intentRegistry.save(intent)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 기존 인텐트 정의를 수정한다. 제공된 필드만 변경된다. */
    @Operation(summary = "기존 인텐트 정의 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Intent updated"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Intent not found")
    ])
    @PutMapping("/{intentName}")
    fun updateIntent(
        @PathVariable intentName: String,
        @Valid @RequestBody request: UpdateIntentRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val existing = intentRegistry.get(intentName) ?: return notFoundResponse("Intent not found: $intentName")

        val updated = existing.copy(
            description = request.description ?: existing.description,
            examples = request.examples ?: existing.examples,
            keywords = request.keywords ?: existing.keywords,
            profile = request.profile ?: existing.profile,
            enabled = request.enabled ?: existing.enabled
        )
        val saved = intentRegistry.save(updated)
        return ResponseEntity.ok(saved.toResponse())
    }

    /** 인텐트 정의를 삭제한다. */
    @Operation(summary = "인텐트 정의 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Intent deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @DeleteMapping("/{intentName}")
    fun deleteIntent(
        @PathVariable intentName: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        intentRegistry.delete(intentName)
        return ResponseEntity.noContent().build()
    }
}

// --- 요청 DTO ---

data class CreateIntentRequest(
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    @field:NotBlank(message = "description must not be blank")
    val description: String,
    val examples: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val profile: IntentProfile = IntentProfile(),
    val enabled: Boolean = true
) {
    fun toDefinition() = IntentDefinition(
        name = name,
        description = description,
        examples = examples,
        keywords = keywords,
        profile = profile,
        enabled = enabled
    )
}

data class UpdateIntentRequest(
    val description: String? = null,
    val examples: List<String>? = null,
    val keywords: List<String>? = null,
    val profile: IntentProfile? = null,
    val enabled: Boolean? = null
)

// --- 응답 DTO ---

data class IntentResponse(
    val name: String,
    val description: String,
    val examples: List<String>,
    val keywords: List<String>,
    val profile: IntentProfile,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

// --- 매핑 확장 ---

private fun IntentDefinition.toResponse() = IntentResponse(
    name = name,
    description = description,
    examples = examples,
    keywords = keywords,
    profile = profile,
    enabled = enabled,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
