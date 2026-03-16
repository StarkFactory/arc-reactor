package com.arc.reactor.controller

import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/**
 * 페르소나 관리 API 컨트롤러.
 *
 * 시스템 프롬프트 페르소나를 관리하는 REST API를 제공합니다.
 * 페르소나는 사용자가 선택할 수 있는 이름이 지정된 시스템 프롬프트 템플릿입니다.
 *
 * ## 엔드포인트
 * - GET    /api/personas          : 전체 페르소나 목록 조회
 * - GET    /api/personas/{id}     : ID로 페르소나 조회
 * - POST   /api/personas          : 새 페르소나 생성 (관리자)
 * - PUT    /api/personas/{id}     : 기존 페르소나 수정 (관리자)
 * - DELETE /api/personas/{id}     : 페르소나 삭제 (관리자)
 *
 * @see PersonaStore
 */
@Tag(name = "Personas", description = "System prompt persona management")
@RestController
@RequestMapping("/api/personas")
class PersonaController(
    private val personaStore: PersonaStore
) {

    /** 전체 페르소나 목록을 조회한다. activeOnly=true이면 활성 상태만 반환한다. 관리자 권한 필요. */
    @Operation(summary = "전체 페르소나 목록 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of personas"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listPersonas(
        @RequestParam(required = false, defaultValue = "false") activeOnly: Boolean,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val personas = personaStore.list()
        val filtered = if (activeOnly) personas.filter { it.isActive } else personas
        return ResponseEntity.ok(filtered.map { it.toResponse() })
    }

    /** ID로 페르소나를 조회한다. */
    @Operation(summary = "ID로 페르소나 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Persona details"),
        ApiResponse(responseCode = "404", description = "Persona not found")
    ])
    @GetMapping("/{personaId}")
    fun getPersona(@PathVariable personaId: String): ResponseEntity<Any> {
        val persona = personaStore.get(personaId)
            ?: return notFoundResponse("Persona not found: $personaId")
        return ResponseEntity.ok(persona.toResponse())
    }

    /** 새 페르소나를 생성한다. 관리자 권한 필요. */
    @Operation(summary = "새 페르소나 생성 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Persona created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun createPersona(
        @Valid @RequestBody request: CreatePersonaRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val persona = Persona(
            id = UUID.randomUUID().toString(),
            name = request.name,
            systemPrompt = request.systemPrompt,
            isDefault = request.isDefault,
            description = request.description,
            responseGuideline = request.responseGuideline,
            welcomeMessage = request.welcomeMessage,
            icon = request.icon,
            isActive = request.isActive,
            promptTemplateId = request.promptTemplateId
        )
        val saved = personaStore.save(persona)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 기존 페르소나를 수정한다. 제공된 필드만 변경된다. 관리자 권한 필요. */
    @Operation(summary = "기존 페르소나 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Persona updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Persona not found")
    ])
    @PutMapping("/{personaId}")
    fun updatePersona(
        @PathVariable personaId: String,
        @Valid @RequestBody request: UpdatePersonaRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val updated = personaStore.update(
            personaId = personaId,
            name = request.name,
            systemPrompt = request.systemPrompt,
            isDefault = request.isDefault,
            description = request.description,
            responseGuideline = request.responseGuideline,
            welcomeMessage = request.welcomeMessage,
            icon = request.icon,
            promptTemplateId = request.promptTemplateId,
            isActive = request.isActive
        ) ?: return notFoundResponse("Persona not found: $personaId")
        return ResponseEntity.ok(updated.toResponse())
    }

    /** 페르소나를 삭제한다. 멱등성 보장 -- 미존재 시에도 204를 반환한다. 관리자 권한 필요. */
    @Operation(summary = "페르소나 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Persona deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @DeleteMapping("/{personaId}")
    fun deletePersona(
        @PathVariable personaId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        personaStore.delete(personaId)
        return ResponseEntity.noContent().build()
    }
}

// --- 요청 DTO ---

data class CreatePersonaRequest(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = 200, message = "name must not exceed 200 characters")
    val name: String,
    @field:NotBlank(message = "systemPrompt must not be blank")
    @field:Size(max = 50000, message = "systemPrompt must not exceed 50000 characters")
    val systemPrompt: String,
    val isDefault: Boolean = false,
    @field:Size(max = 2000, message = "description must not exceed 2000 characters")
    val description: String? = null,
    @field:Size(max = 10000, message = "responseGuideline must not exceed 10000 characters")
    val responseGuideline: String? = null,
    @field:Size(max = 2000, message = "welcomeMessage must not exceed 2000 characters")
    val welcomeMessage: String? = null,
    @field:Size(max = 200, message = "promptTemplateId must not exceed 200 characters")
    val promptTemplateId: String? = null,
    @field:Size(max = 20, message = "icon must be 20 characters or fewer")
    val icon: String? = null,
    val isActive: Boolean = true
)

data class UpdatePersonaRequest(
    @field:Size(max = 200, message = "name must not exceed 200 characters")
    val name: String? = null,
    @field:Size(max = 50000, message = "systemPrompt must not exceed 50000 characters")
    val systemPrompt: String? = null,
    val isDefault: Boolean? = null,
    @field:Size(max = 2000, message = "description must not exceed 2000 characters")
    val description: String? = null,
    @field:Size(max = 10000, message = "responseGuideline must not exceed 10000 characters")
    val responseGuideline: String? = null,
    @field:Size(max = 2000, message = "welcomeMessage must not exceed 2000 characters")
    val welcomeMessage: String? = null,
    @field:Size(max = 200, message = "promptTemplateId must not exceed 200 characters")
    val promptTemplateId: String? = null,
    @field:Size(max = 20, message = "icon must be 20 characters or fewer")
    val icon: String? = null,
    val isActive: Boolean? = null
)

// --- 응답 DTO ---

data class PersonaResponse(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean,
    val description: String?,
    val responseGuideline: String?,
    val welcomeMessage: String?,
    val promptTemplateId: String?,
    val icon: String?,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

// --- 매핑 확장 ---

private fun Persona.toResponse() = PersonaResponse(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    isDefault = isDefault,
    description = description,
    responseGuideline = responseGuideline,
    welcomeMessage = welcomeMessage,
    promptTemplateId = promptTemplateId,
    icon = icon,
    isActive = isActive,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
