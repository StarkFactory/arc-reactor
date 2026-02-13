package com.arc.reactor.controller

import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
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

@Tag(name = "Output Guard Rules", description = "Dynamic output guard regex rules (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/output-guard/rules")
class OutputGuardRuleController(
    private val store: OutputGuardRuleStore
) {

    @Operation(summary = "List output guard rules")
    @GetMapping
    fun listRules(): List<OutputGuardRuleResponse> {
        return store.list().map { it.toResponse() }
    }

    @Operation(summary = "Create output guard rule (ADMIN)")
    @PostMapping
    fun createRule(
        @Valid @RequestBody request: CreateOutputGuardRuleRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val action = parseAction(request.action)
            ?: return ResponseEntity.badRequest()
                .body(ErrorResponse(error = "Invalid action: ${request.action}", timestamp = Instant.now().toString()))

        val now = Instant.now()
        val saved = store.save(
            OutputGuardRule(
                id = UUID.randomUUID().toString(),
                name = request.name.trim(),
                pattern = request.pattern.trim(),
                action = action,
                enabled = request.enabled,
                createdAt = now,
                updatedAt = now
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Operation(summary = "Update output guard rule (ADMIN)")
    @PutMapping("/{id}")
    fun updateRule(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateOutputGuardRuleRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val action = request.action?.let { parseAction(it) }
        if (request.action != null && action == null) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse(error = "Invalid action: ${request.action}", timestamp = Instant.now().toString()))
        }

        val existing = store.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(error = "Output guard rule '$id' not found", timestamp = Instant.now().toString()))

        val updated = store.update(
            id = id,
            rule = existing.copy(
                name = request.name?.trim() ?: existing.name,
                pattern = request.pattern?.trim() ?: existing.pattern,
                action = action ?: existing.action,
                enabled = request.enabled ?: existing.enabled
            )
        ) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "Output guard rule '$id' not found", timestamp = Instant.now().toString()))

        return ResponseEntity.ok(updated.toResponse())
    }

    @Operation(summary = "Delete output guard rule (ADMIN)")
    @DeleteMapping("/{id}")
    fun deleteRule(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.delete(id)
        return ResponseEntity.noContent().build()
    }
}

data class CreateOutputGuardRuleRequest(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = 120, message = "name must not exceed 120 characters")
    val name: String,

    @field:NotBlank(message = "pattern must not be blank")
    @field:Size(max = 5000, message = "pattern must not exceed 5000 characters")
    val pattern: String,

    @field:NotBlank(message = "action must not be blank")
    val action: String = "MASK",

    val enabled: Boolean = true
)

data class UpdateOutputGuardRuleRequest(
    val name: String? = null,
    val pattern: String? = null,
    val action: String? = null,
    val enabled: Boolean? = null
)

data class OutputGuardRuleResponse(
    val id: String,
    val name: String,
    val pattern: String,
    val action: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

private fun OutputGuardRule.toResponse() = OutputGuardRuleResponse(
    id = id,
    name = name,
    pattern = pattern,
    action = action.name,
    enabled = enabled,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun parseAction(raw: String): OutputGuardRuleAction? {
    return OutputGuardRuleAction.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
}
