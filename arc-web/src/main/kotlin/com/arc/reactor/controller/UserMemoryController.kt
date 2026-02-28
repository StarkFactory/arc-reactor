package com.arc.reactor.controller

import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.model.UserMemory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

/**
 * User Memory API Controller
 *
 * Provides REST APIs for managing per-user long-term memory.
 * Each user can access their own memory. Admin users can access any user's memory.
 *
 * ## Endpoints
 * - GET    /api/user-memory/{userId}              : Retrieve full memory record
 * - PUT    /api/user-memory/{userId}/facts        : Update a single fact entry
 * - PUT    /api/user-memory/{userId}/preferences  : Update a single preference entry
 * - DELETE /api/user-memory/{userId}              : Delete all memory for a user
 */
@Tag(name = "User Memory", description = "Per-user long-term memory management")
@RestController
@RequestMapping("/api/user-memory")
@ConditionalOnBean(UserMemoryManager::class)
class UserMemoryController(
    private val userMemoryManager: UserMemoryManager
) {

    /**
     * Retrieve the full memory record for a user.
     */
    @Operation(summary = "Get user memory by userId")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "User memory retrieved"),
        ApiResponse(responseCode = "404", description = "No memory found for userId"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @GetMapping("/{userId}")
    fun getUserMemory(
        @PathVariable userId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        val memory = runBlocking { userMemoryManager.get(userId) }
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(memory.toResponse())
    }

    /**
     * Update a single fact for the user. Creates the memory record if it does not exist.
     */
    @Operation(summary = "Update a user fact")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Fact updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @PutMapping("/{userId}/facts")
    fun updateFact(
        @PathVariable userId: String,
        @Valid @RequestBody request: KeyValueRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        runBlocking { userMemoryManager.updateFact(userId, request.key, request.value) }
        return ResponseEntity.ok(mapOf("updated" to true))
    }

    /**
     * Update a single preference for the user. Creates the memory record if it does not exist.
     */
    @Operation(summary = "Update a user preference")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Preference updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @PutMapping("/{userId}/preferences")
    fun updatePreference(
        @PathVariable userId: String,
        @Valid @RequestBody request: KeyValueRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        runBlocking { userMemoryManager.updatePreference(userId, request.key, request.value) }
        return ResponseEntity.ok(mapOf("updated" to true))
    }

    /**
     * Delete all stored memory for a user. Idempotent.
     */
    @Operation(summary = "Delete all memory for a user")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Memory deleted"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @DeleteMapping("/{userId}")
    fun deleteUserMemory(
        @PathVariable userId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        runBlocking { userMemoryManager.delete(userId) }
        return ResponseEntity.noContent().build()
    }

    /**
     * Determines whether the caller is authorized to access the given userId's memory.
     *
     * When auth is disabled (role == null), all requests are allowed.
     * When auth is enabled, only the owner or an admin can access.
     */
    private fun canAccess(userId: String, exchange: ServerWebExchange): Boolean {
        if (isAdmin(exchange)) return true
        val callerId = currentActor(exchange)
        return callerId == userId
    }
}

// --- Request / Response DTOs ---

data class KeyValueRequest(
    @field:NotBlank(message = "key must not be blank")
    val key: String,
    @field:NotBlank(message = "value must not be blank")
    val value: String
)

data class UserMemoryResponse(
    val userId: String,
    val facts: Map<String, String>,
    val preferences: Map<String, String>,
    val recentTopics: List<String>,
    val updatedAt: String
)

private fun UserMemory.toResponse() = UserMemoryResponse(
    userId = userId,
    facts = facts,
    preferences = preferences,
    recentTopics = recentTopics,
    updatedAt = updatedAt.toString()
)
