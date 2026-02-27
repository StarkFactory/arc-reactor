package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.guard.output.policy.OutputGuardRule
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditLog
import com.arc.reactor.guard.output.policy.OutputGuardRuleAuditStore
import com.arc.reactor.guard.output.policy.OutputGuardRuleAction
import com.arc.reactor.guard.output.policy.OutputGuardRuleEvaluator
import com.arc.reactor.guard.output.policy.OutputGuardRuleInvalidationBus
import com.arc.reactor.guard.output.policy.OutputGuardRuleStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Tag(name = "Output Guard Rules", description = "Dynamic output guard regex rules (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/output-guard/rules")
class OutputGuardRuleController(
    private val store: OutputGuardRuleStore,
    private val auditStore: OutputGuardRuleAuditStore,
    private val invalidationBus: OutputGuardRuleInvalidationBus,
    private val evaluator: OutputGuardRuleEvaluator
) {

    @Operation(summary = "List output guard rules")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of output guard rules"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(store.list().map { it.toResponse() })
    }

    @Operation(summary = "List output guard rule audit logs (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of audit logs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/audits")
    fun listAudits(
        @RequestParam(required = false) @Min(1) @Max(1000) limit: Int?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val size = (limit ?: 100).coerceIn(1, 1000)
        val logs = auditStore.list(size).map { it.toResponse() }
        return ResponseEntity.ok(logs)
    }

    @Operation(summary = "Create output guard rule (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Output guard rule created"),
        ApiResponse(responseCode = "400", description = "Invalid action or regex pattern"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun createRule(
        @Valid @RequestBody request: CreateOutputGuardRuleRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val action = parseAction(request.action)
            ?: return ResponseEntity.badRequest()
                .body(ErrorResponse(error = "Invalid action: ${request.action}", timestamp = Instant.now().toString()))
        val regexError = validateRegex(request.pattern.trim())
        if (regexError != null) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse(error = "Invalid pattern: $regexError", timestamp = Instant.now().toString()))
        }

        val now = Instant.now()
        val saved = store.save(
            OutputGuardRule(
                id = UUID.randomUUID().toString(),
                name = request.name.trim(),
                pattern = request.pattern.trim(),
                action = action,
                priority = request.priority,
                enabled = request.enabled,
                createdAt = now,
                updatedAt = now
            )
        )
        invalidationBus.touch()
        recordAudit(
            action = OutputGuardRuleAuditAction.CREATE,
            actor = actor(exchange),
            ruleId = saved.id,
            detail = "name=${saved.name}, action=${saved.action}, priority=${saved.priority}, enabled=${saved.enabled}"
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @Operation(summary = "Update output guard rule (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Output guard rule updated"),
        ApiResponse(responseCode = "400", description = "Invalid action or regex pattern"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Output guard rule not found")
    ])
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
        if (request.pattern != null) {
            val regexError = validateRegex(request.pattern.trim())
            if (regexError != null) {
                return ResponseEntity.badRequest()
                    .body(ErrorResponse(error = "Invalid pattern: $regexError", timestamp = Instant.now().toString()))
            }
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
                priority = request.priority ?: existing.priority,
                enabled = request.enabled ?: existing.enabled
            )
        ) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "Output guard rule '$id' not found", timestamp = Instant.now().toString()))

        invalidationBus.touch()
        recordAudit(
            action = OutputGuardRuleAuditAction.UPDATE,
            actor = actor(exchange),
            ruleId = updated.id,
            detail = "name=${updated.name}, action=${updated.action}, " +
                "priority=${updated.priority}, enabled=${updated.enabled}"
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    @Operation(summary = "Delete output guard rule (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Output guard rule deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Output guard rule not found")
    ])
    @DeleteMapping("/{id}")
    fun deleteRule(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val existing = store.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse(error = "Output guard rule '$id' not found", timestamp = Instant.now().toString()))
        store.delete(id)
        invalidationBus.touch()
        recordAudit(
            action = OutputGuardRuleAuditAction.DELETE,
            actor = actor(exchange),
            ruleId = id,
            detail = "name=${existing.name}"
        )
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "Dry-run output guard policy simulation (ADMIN)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Simulation result"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping("/simulate")
    fun simulate(
        @Valid @RequestBody request: OutputGuardSimulationRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val rules = store.list().filter { request.includeDisabled || it.enabled }
        val evaluation = evaluator.evaluate(content = request.content, rules = rules)

        recordAudit(
            action = OutputGuardRuleAuditAction.SIMULATE,
            actor = actor(exchange),
            detail = "blocked=${evaluation.blocked}, matched=${evaluation.matchedRules.size}, " +
                "includeDisabled=${request.includeDisabled}"
        )

        return ResponseEntity.ok(
            OutputGuardSimulationResponse(
                originalContent = request.content,
                resultContent = evaluation.content,
                blocked = evaluation.blocked,
                modified = evaluation.modified,
                blockedByRuleId = evaluation.blockedBy?.ruleId,
                blockedByRuleName = evaluation.blockedBy?.ruleName,
                matchedRules = evaluation.matchedRules.map {
                    OutputGuardSimulationMatchResponse(
                        ruleId = it.ruleId,
                        ruleName = it.ruleName,
                        action = it.action.name,
                        priority = it.priority
                    )
                },
                invalidRules = evaluation.invalidRules.map {
                    OutputGuardSimulationInvalidRuleResponse(
                        ruleId = it.ruleId,
                        ruleName = it.ruleName,
                        reason = it.reason
                    )
                }
            )
        )
    }

    private fun actor(exchange: ServerWebExchange): String {
        return (exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
    }

    private fun recordAudit(
        action: OutputGuardRuleAuditAction,
        actor: String,
        ruleId: String? = null,
        detail: String? = null
    ) {
        runCatching {
            auditStore.save(
                OutputGuardRuleAuditLog(
                    action = action,
                    actor = actor,
                    ruleId = ruleId,
                    detail = detail
                )
            )
        }.onFailure { e ->
            logger.warn(e) { "Failed to persist output guard audit log: action=$action, ruleId=$ruleId" }
        }
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

    @field:Min(value = 1, message = "priority must be >= 1")
    @field:Max(value = 10000, message = "priority must be <= 10000")
    val priority: Int = 100,

    val enabled: Boolean = true
)

data class UpdateOutputGuardRuleRequest(
    val name: String? = null,
    val pattern: String? = null,
    val action: String? = null,
    @field:Min(value = 1, message = "priority must be >= 1")
    @field:Max(value = 10000, message = "priority must be <= 10000")
    val priority: Int? = null,
    val enabled: Boolean? = null
)

data class OutputGuardSimulationRequest(
    @field:NotBlank(message = "content must not be blank")
    @field:Size(max = 50000, message = "content must not exceed 50000 characters")
    val content: String,
    val includeDisabled: Boolean = false
)

data class OutputGuardRuleResponse(
    val id: String,
    val name: String,
    val pattern: String,
    val action: String,
    val priority: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class OutputGuardSimulationResponse(
    val originalContent: String,
    val resultContent: String,
    val blocked: Boolean,
    val modified: Boolean,
    val blockedByRuleId: String?,
    val blockedByRuleName: String?,
    val matchedRules: List<OutputGuardSimulationMatchResponse>,
    val invalidRules: List<OutputGuardSimulationInvalidRuleResponse>
)

data class OutputGuardSimulationMatchResponse(
    val ruleId: String,
    val ruleName: String,
    val action: String,
    val priority: Int
)

data class OutputGuardSimulationInvalidRuleResponse(
    val ruleId: String,
    val ruleName: String,
    val reason: String
)

data class OutputGuardRuleAuditResponse(
    val id: String,
    val ruleId: String?,
    val action: String,
    val actor: String,
    val detail: String?,
    val createdAt: Long
)

private fun OutputGuardRule.toResponse() = OutputGuardRuleResponse(
    id = id,
    name = name,
    pattern = pattern,
    action = action.name,
    priority = priority,
    enabled = enabled,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun OutputGuardRuleAuditLog.toResponse() = OutputGuardRuleAuditResponse(
    id = id,
    ruleId = ruleId,
    action = action.name,
    actor = actor,
    detail = detail,
    createdAt = createdAt.toEpochMilli()
)

private fun parseAction(raw: String): OutputGuardRuleAction? {
    return OutputGuardRuleAction.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
}

private fun validateRegex(pattern: String): String? {
    return runCatching {
        Regex(pattern)
        null
    }.getOrElse { it.message ?: "invalid regex" }
}
