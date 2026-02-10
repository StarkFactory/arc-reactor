package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/**
 * Persona Management API Controller
 *
 * Provides REST APIs for managing system prompt personas.
 * Personas are named system prompt templates that can be selected by users.
 *
 * ## Endpoints
 * - GET    /api/personas          : List all personas
 * - GET    /api/personas/{id}     : Get a persona by ID
 * - POST   /api/personas          : Create a new persona
 * - PUT    /api/personas/{id}     : Update an existing persona
 * - DELETE /api/personas/{id}     : Delete a persona
 */
@Tag(name = "Personas", description = "System prompt persona management")
@RestController
@RequestMapping("/api/personas")
class PersonaController(
    private val personaStore: PersonaStore
) {

    private fun isAdmin(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        // When auth is disabled, JwtAuthWebFilter is not registered so role is null.
        // Allow all operations (consistent with frontend: !isAuthRequired || isAdmin).
        return role == null || role == UserRole.ADMIN
    }

    private fun forbidden(): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to "Admin access required"))
    }

    /**
     * List all personas.
     */
    @GetMapping
    fun listPersonas(): List<PersonaResponse> {
        return personaStore.list().map { it.toResponse() }
    }

    /**
     * Get a persona by ID.
     */
    @GetMapping("/{personaId}")
    fun getPersona(@PathVariable personaId: String): ResponseEntity<PersonaResponse> {
        val persona = personaStore.get(personaId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(persona.toResponse())
    }

    /**
     * Create a new persona. Requires ADMIN role when auth is enabled.
     */
    @PostMapping
    fun createPersona(
        @Valid @RequestBody request: CreatePersonaRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbidden()
        val persona = Persona(
            id = UUID.randomUUID().toString(),
            name = request.name,
            systemPrompt = request.systemPrompt,
            isDefault = request.isDefault
        )
        val saved = personaStore.save(persona)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /**
     * Update an existing persona. Only provided fields are changed.
     * Requires ADMIN role when auth is enabled.
     */
    @PutMapping("/{personaId}")
    fun updatePersona(
        @PathVariable personaId: String,
        @Valid @RequestBody request: UpdatePersonaRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbidden()
        val updated = personaStore.update(
            personaId = personaId,
            name = request.name,
            systemPrompt = request.systemPrompt,
            isDefault = request.isDefault
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Delete a persona. Idempotent — returns 204 even if not found.
     * Requires ADMIN role when auth is enabled.
     */
    @DeleteMapping("/{personaId}")
    fun deletePersona(
        @PathVariable personaId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbidden()
        personaStore.delete(personaId)
        return ResponseEntity.noContent().build()
    }
}

// --- Request DTOs ---

data class CreatePersonaRequest(
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    @field:NotBlank(message = "systemPrompt must not be blank")
    val systemPrompt: String,
    val isDefault: Boolean = false
)

data class UpdatePersonaRequest(
    val name: String? = null,
    val systemPrompt: String? = null,
    val isDefault: Boolean? = null
)

// --- Response DTO ---

data class PersonaResponse(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

// --- Mapping extensions ---

private fun Persona.toResponse() = PersonaResponse(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    isDefault = isDefault,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
