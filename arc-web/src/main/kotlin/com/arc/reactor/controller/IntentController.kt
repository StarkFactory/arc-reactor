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
 * Intent Management API Controller
 *
 * Provides REST APIs for managing intent definitions.
 * Only available when `arc.reactor.intent.enabled=true`.
 *
 * ## Endpoints
 * - GET    /api/intents          : List all intents
 * - GET    /api/intents/{name}   : Get an intent by name
 * - POST   /api/intents          : Create a new intent (ADMIN)
 * - PUT    /api/intents/{name}   : Update an existing intent (ADMIN)
 * - DELETE /api/intents/{name}   : Delete an intent (ADMIN)
 */
@Tag(name = "Intents", description = "Intent definition management")
@RestController
@RequestMapping("/api/intents")
@ConditionalOnProperty(prefix = "arc.reactor.intent", name = ["enabled"], havingValue = "true")
class IntentController(
    private val intentRegistry: IntentRegistry
) {

    @Operation(summary = "List all intent definitions")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of intent definitions"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listIntents(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(intentRegistry.list().map { it.toResponse() })
    }

    @Operation(summary = "Get an intent definition by name")
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
        val intent = intentRegistry.get(intentName) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(intent.toResponse())
    }

    @Operation(summary = "Create a new intent definition (ADMIN)")
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
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse(
                    error = "Intent '${request.name}' already exists",
                    timestamp = java.time.Instant.now().toString()
                ))
        }

        val intent = request.toDefinition()
        val saved = intentRegistry.save(intent)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Operation(summary = "Update an existing intent definition (ADMIN)")
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

        val existing = intentRegistry.get(intentName) ?: return ResponseEntity.notFound().build()

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

    @Operation(summary = "Delete an intent definition (ADMIN)")
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

// --- Request DTOs ---

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

// --- Response DTO ---

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

// --- Mapping extensions ---

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
