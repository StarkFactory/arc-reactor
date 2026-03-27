package com.arc.reactor.controller

import com.arc.reactor.auth.AdminAuthorizationSupport.maskedAdminAccountRef
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
import org.springframework.beans.factory.annotation.Value
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Output Guard Rule 동적 관리 컨트롤러.
 *
 * 런타임에 정규식 기반 출력 가드 규칙을 CRUD하고, 시뮬레이션(dry-run)으로
 * 규칙 적용 결과를 미리 확인할 수 있습니다. 쓰기 작업은 ADMIN 권한이 필요합니다.
 *
 * @see OutputGuardRuleStore
 * @see OutputGuardRuleEvaluator
 * @see OutputGuardRuleInvalidationBus
 */
@Tag(name = "Output Guard Rules", description = "Dynamic output guard regex rules (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/output-guard/rules")
@ConditionalOnProperty(
    prefix = "arc.reactor.output-guard",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class OutputGuardRuleController(
    private val store: OutputGuardRuleStore,
    private val auditStore: OutputGuardRuleAuditStore,
    private val invalidationBus: OutputGuardRuleInvalidationBus,
    private val evaluator: OutputGuardRuleEvaluator,
    @param:Value("\${arc.reactor.output-guard.dynamic-rules-enabled:true}")
    private val dynamicRulesEnabled: Boolean = true
) {

    /**
     * 동적 규칙 기능이 비활성화된 경우 503 응답을 반환한다.
     * `@ConditionalOnProperty` 중복 사용 시 OR 시맨틱으로 동작하므로,
     * `dynamic-rules-enabled`는 런타임에 검증한다.
     */
    private fun dynamicRulesDisabledResponse(): ResponseEntity<Any>? {
        if (!dynamicRulesEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                mapOf("error" to "Dynamic output guard rules are disabled")
            )
        }
        return null
    }

    /** 등록된 출력 가드 규칙 전체 목록을 조회한다. */
    @Operation(summary = "출력 가드 규칙 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of output guard rules"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(store.list().map { it.toResponse() })
    }

    /** 출력 가드 규칙 감사 로그 목록을 조회한다. */
    @Operation(summary = "출력 가드 규칙 감사 로그 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of audit logs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping("/audits")
    fun listAudits(
        @RequestParam(required = false) @Min(1) @Max(1000) limit: Int?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        val size = (limit ?: 100).coerceIn(1, 1000)
        val logs = auditStore.list(size).map { it.toResponse() }
        return ResponseEntity.ok(logs)
    }

    /** 새 출력 가드 규칙을 생성한다. 정규식 패턴과 액션을 검증한 뒤 저장한다. */
    @Operation(summary = "출력 가드 규칙 생성 (관리자)")
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
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        val action = parseAction(request.action) ?: return badRequestResponse("Invalid action: ${request.action}")
        val patternError = validatePattern(request.pattern)
        if (patternError != null) return badRequestResponse(patternError)

        val now = Instant.now()
        val saved = store.save(
            OutputGuardRule(
                id = UUID.randomUUID().toString(),
                name = request.name.trim(),
                pattern = request.pattern.trim(),
                action = action,
                replacement = request.replacement,
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
            detail = ruleDetail(saved)
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 기존 출력 가드 규칙을 수정한다. 제공된 필드만 변경된다. */
    @Operation(summary = "출력 가드 규칙 수정 (관리자)")
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
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        val action = request.action?.let { parseAction(it) }
        if (request.action != null && action == null) return badRequestResponse("Invalid action: ${request.action}")
        if (request.pattern != null) {
            val patternError = validatePattern(request.pattern)
            if (patternError != null) return badRequestResponse(patternError)
        }

        val existing = store.findById(id) ?: return ruleNotFound(id)
        val updated = store.update(
            id = id,
            rule = existing.copy(
                name = request.name?.trim() ?: existing.name,
                pattern = request.pattern?.trim() ?: existing.pattern,
                action = action ?: existing.action,
                replacement = request.replacement ?: existing.replacement,
                priority = request.priority ?: existing.priority,
                enabled = request.enabled ?: existing.enabled
            )
        ) ?: return ruleNotFound(id)

        invalidationBus.touch()
        recordAudit(
            action = OutputGuardRuleAuditAction.UPDATE,
            actor = actor(exchange),
            ruleId = updated.id,
            detail = ruleDetail(updated)
        )
        return ResponseEntity.ok(updated.toResponse())
    }

    /** 출력 가드 규칙을 삭제한다. */
    @Operation(summary = "출력 가드 규칙 삭제 (관리자)")
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
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        val existing = store.findById(id) ?: return ruleNotFound(id)
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

    /** 출력 가드 정책 시뮬레이션(dry-run)을 수행한다. 실제 차단 없이 결과를 미리 확인할 수 있다. */
    @Operation(summary = "출력 가드 정책 시뮬레이션 (관리자)")
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
        dynamicRulesDisabledResponse()?.let { return it }
        if (!isAdmin(exchange)) return forbiddenResponse()
        val rules = store.list().filter { request.includeDisabled || it.enabled }
        val evaluation = evaluator.evaluate(content = request.content, rules = rules)
        recordAudit(
            action = OutputGuardRuleAuditAction.SIMULATE,
            actor = actor(exchange),
            detail = "blocked=${evaluation.blocked}, matched=${evaluation.matchedRules.size}, " +
                "includeDisabled=${request.includeDisabled}"
        )
        return ResponseEntity.ok(evaluation.toSimulationResponse(request.content))
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

    /** 패턴 문자열을 검증하고, 문제가 있으면 오류 메시지를 반환한다. */
    private fun validatePattern(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return "Invalid pattern: pattern must not be blank after trimming"
        val regexError = validateRegex(trimmed)
        if (regexError != null) return "Invalid pattern: $regexError"
        return null
    }

    /** 규칙 변경 감사 로그 상세 문자열을 생성한다. */
    private fun ruleDetail(rule: OutputGuardRule): String {
        return "name=${rule.name}, action=${rule.action}, priority=${rule.priority}, enabled=${rule.enabled}"
    }

    /** 규칙 미발견 404 응답. */
    private fun ruleNotFound(id: String): ResponseEntity<Any> = notFoundResponse("Output guard rule '$id' not found")
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

    @field:Size(max = 256, message = "replacement must not exceed 256 characters")
    val replacement: String = OutputGuardRule.DEFAULT_REPLACEMENT,

    @field:Min(value = 1, message = "priority must be >= 1")
    @field:Max(value = 10000, message = "priority must be <= 10000")
    val priority: Int = 100,

    val enabled: Boolean = true
)

data class UpdateOutputGuardRuleRequest(
    val name: String? = null,
    val pattern: String? = null,
    val action: String? = null,
    @field:Size(max = 256, message = "replacement must not exceed 256 characters")
    val replacement: String? = null,
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
    val replacement: String,
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
    replacement = replacement,
    priority = priority,
    enabled = enabled,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun OutputGuardRuleAuditLog.toResponse() = OutputGuardRuleAuditResponse(
    id = id,
    ruleId = ruleId,
    action = action.name,
    actor = maskedAdminAccountRef(actor),
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
    }.getOrElse { "Invalid regex pattern" }
}

/** 평가 결과를 시뮬레이션 응답 DTO로 변환한다. */
private fun com.arc.reactor.guard.output.policy.OutputGuardEvaluation.toSimulationResponse(
    originalContent: String
) = OutputGuardSimulationResponse(
    originalContent = originalContent,
    resultContent = content,
    blocked = blocked,
    modified = modified,
    blockedByRuleId = blockedBy?.ruleId,
    blockedByRuleName = blockedBy?.ruleName,
    matchedRules = matchedRules.map {
        OutputGuardSimulationMatchResponse(it.ruleId, it.ruleName, it.action.name, it.priority)
    },
    invalidRules = invalidRules.map {
        OutputGuardSimulationInvalidRuleResponse(it.ruleId, it.ruleName, it.reason)
    }
)
