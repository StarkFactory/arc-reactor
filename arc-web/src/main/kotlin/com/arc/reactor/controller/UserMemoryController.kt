package com.arc.reactor.controller

import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.memory.model.UserMemory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

/**
 * 사용자 메모리 컨트롤러.
 *
 * 사용자별 장기 메모리(facts, preferences, recentTopics)를 관리하는 REST API를 제공합니다.
 * 각 사용자는 자신의 메모리에만 접근할 수 있습니다.
 *
 * ## 엔드포인트
 * - GET    /api/user-memory/{userId}              : 전체 메모리 레코드 조회
 * - PUT    /api/user-memory/{userId}/facts        : 단일 fact 항목 수정
 * - PUT    /api/user-memory/{userId}/preferences  : 단일 preference 항목 수정
 * - DELETE /api/user-memory/{userId}              : 사용자의 모든 메모리 삭제
 *
 * @see UserMemoryManager
 */
@Tag(name = "User Memory", description = "Per-user long-term memory management")
@RestController
@RequestMapping("/api/user-memory")
@ConditionalOnBean(UserMemoryManager::class)
class UserMemoryController(
    private val userMemoryManager: UserMemoryManager
) {

    /** 사용자의 전체 메모리 레코드를 조회한다. */
    @Operation(summary = "사용자 메모리 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "User memory retrieved"),
        ApiResponse(responseCode = "404", description = "No memory found for userId"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @GetMapping("/{userId}")
    suspend fun getUserMemory(
        @PathVariable userId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        val memory = userMemoryManager.get(userId)
            ?: return notFoundResponse("User memory not found: $userId")
        return ResponseEntity.ok(memory.toResponse())
    }

    /** 사용자의 단일 fact를 수정한다. 메모리 레코드가 없으면 새로 생성한다. */
    @Operation(summary = "사용자 fact 수정")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Fact updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @PutMapping("/{userId}/facts")
    suspend fun updateFact(
        @PathVariable userId: String,
        @Valid @RequestBody request: KeyValueRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        userMemoryManager.updateFact(userId, request.key, request.value)
        return ResponseEntity.ok(mapOf("updated" to true))
    }

    /** 사용자의 단일 preference를 수정한다. 메모리 레코드가 없으면 새로 생성한다. */
    @Operation(summary = "사용자 preference 수정")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Preference updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @PutMapping("/{userId}/preferences")
    suspend fun updatePreference(
        @PathVariable userId: String,
        @Valid @RequestBody request: KeyValueRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        userMemoryManager.updatePreference(userId, request.key, request.value)
        return ResponseEntity.ok(mapOf("updated" to true))
    }

    /** 사용자의 모든 메모리를 삭제한다. 멱등성을 보장한다. */
    @Operation(summary = "사용자 전체 메모리 삭제")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Memory deleted"),
        ApiResponse(responseCode = "403", description = "Access denied")
    ])
    @DeleteMapping("/{userId}")
    suspend fun deleteUserMemory(
        @PathVariable userId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!canAccess(userId, exchange)) return forbiddenResponse()
        userMemoryManager.delete(userId)
        return ResponseEntity.noContent().build()
    }

    /** 호출자가 해당 userId의 메모리에 접근할 수 있는지 판단한다. 본인만 접근 가능하다. */
    private fun canAccess(userId: String, exchange: ServerWebExchange): Boolean {
        val callerId = currentActor(exchange)
        return callerId.isNotBlank() && callerId == userId
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
    val facts: Map<String, String>,
    val preferences: Map<String, String>,
    val recentTopics: List<String>,
    val updatedAt: String
)

private fun UserMemory.toResponse() = UserMemoryResponse(
    facts = facts,
    preferences = preferences,
    recentTopics = recentTopics,
    updatedAt = updatedAt.toString()
)
