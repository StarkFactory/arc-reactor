package com.arc.reactor.controller

import com.arc.reactor.agent.multiagent.AgentSpecRecord
import com.arc.reactor.agent.multiagent.AgentSpecStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
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
 * 멀티에이전트 스펙 관리 Admin API.
 *
 * 서브에이전트를 등록/수정/삭제하고, 오케스트레이션에 사용할 에이전트 목록을 관리한다.
 *
 * @see AgentSpecStore 에이전트 스펙 저장소
 */
@Tag(name = "Agent Specs", description = "멀티에이전트 스펙 관리")
@RestController
@RequestMapping("/api/admin/agent-specs")
class AgentSpecController(
    private val store: AgentSpecStore,
    private val adminAuditStore: AdminAuditStore
) {

    /** 전체 에이전트 스펙 목록을 조회한다. */
    @Operation(summary = "전체 에이전트 스펙 목록 조회")
    @GetMapping
    fun list(
        @org.springframework.web.bind.annotation.RequestParam(required = false) enabled: Boolean?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val records = if (enabled == true) store.listEnabled() else store.list()
        return ResponseEntity.ok(records.map { it.toResponse() })
    }

    /** ID로 에이전트 스펙을 조회한다. */
    @Operation(summary = "에이전트 스펙 상세 조회")
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val record = store.get(id)
            ?: return notFoundResponse("에이전트 스펙을 찾을 수 없습니다: $id")
        return ResponseEntity.ok(record.toResponse())
    }

    /** 새 에이전트 스펙을 등록한다. */
    @Operation(summary = "에이전트 스펙 등록")
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAgentSpecRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val mode = request.mode ?: "REACT"
        if (mode !in VALID_MODES) return badRequestResponse("유효하지 않은 모드: $mode")
        if (store.list().any { it.name == request.name }) {
            return conflictResponse("이름 '${request.name}'은 이미 사용 중입니다")
        }
        val saved = store.save(buildRecordFromRequest(request, mode))
        auditAgentSpec("CREATE", saved, exchange)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 에이전트 스펙을 수정한다. */
    @Operation(summary = "에이전트 스펙 수정")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateAgentSpecRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        if (request.mode != null && request.mode !in VALID_MODES) {
            return badRequestResponse("유효하지 않은 모드: ${request.mode}")
        }
        val existing = store.get(id)
            ?: return notFoundResponse("에이전트 스펙을 찾을 수 없습니다: $id")
        val saved = store.save(applyUpdate(existing, request))
        auditAgentSpec("UPDATE", saved, exchange)
        return ResponseEntity.ok(saved.toResponse())
    }

    /** 에이전트 스펙을 삭제한다. */
    @Operation(summary = "에이전트 스펙 삭제")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        store.get(id)
            ?: return notFoundResponse("에이전트 스펙을 찾을 수 없습니다: $id")
        store.delete(id)
        recordAdminAudit(
            store = adminAuditStore, category = "agent_spec", action = "DELETE",
            actor = currentActor(exchange), resourceType = "agent_spec", resourceId = id
        )
        return ResponseEntity.noContent().build()
    }

    private fun buildRecordFromRequest(request: CreateAgentSpecRequest, mode: String) = AgentSpecRecord(
        id = UUID.randomUUID().toString(),
        name = request.name,
        description = request.description.orEmpty(),
        toolNames = request.toolNames.orEmpty(),
        keywords = request.keywords.orEmpty(),
        systemPrompt = request.systemPrompt,
        mode = mode,
        enabled = request.enabled ?: true
    )

    private fun applyUpdate(existing: AgentSpecRecord, request: UpdateAgentSpecRequest) = existing.copy(
        name = request.name ?: existing.name,
        description = request.description ?: existing.description,
        toolNames = request.toolNames ?: existing.toolNames,
        keywords = request.keywords ?: existing.keywords,
        systemPrompt = request.systemPrompt ?: existing.systemPrompt,
        mode = request.mode ?: existing.mode,
        enabled = request.enabled ?: existing.enabled,
        updatedAt = Instant.now()
    )

    private fun auditAgentSpec(action: String, record: AgentSpecRecord, exchange: ServerWebExchange) {
        recordAdminAudit(
            store = adminAuditStore, category = "agent_spec", action = action,
            actor = currentActor(exchange), resourceType = "agent_spec",
            resourceId = record.id, detail = "name=${record.name}"
        )
    }

    companion object {
        private val VALID_MODES = setOf("REACT", "STANDARD", "PLAN_EXECUTE")
    }

    private fun AgentSpecRecord.toResponse() = AgentSpecResponse(
        id = id,
        name = name,
        description = description,
        toolNames = toolNames,
        keywords = keywords,
        systemPrompt = systemPrompt,
        mode = mode,
        enabled = enabled,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )
}

// --- 요청/응답 DTO ---

data class CreateAgentSpecRequest(
    @field:NotBlank(message = "name은 필수입니다")
    @field:Size(max = 255)
    val name: String,
    val description: String? = null,
    val toolNames: List<String>? = null,
    val keywords: List<String>? = null,
    val systemPrompt: String? = null,
    val mode: String? = null,
    val enabled: Boolean? = true
)

data class UpdateAgentSpecRequest(
    @field:Size(max = 255)
    val name: String? = null,
    val description: String? = null,
    val toolNames: List<String>? = null,
    val keywords: List<String>? = null,
    val systemPrompt: String? = null,
    val mode: String? = null,
    val enabled: Boolean? = null
)

data class AgentSpecResponse(
    val id: String,
    val name: String,
    val description: String,
    val toolNames: List<String>,
    val keywords: List<String>,
    val systemPrompt: String?,
    val mode: String,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String
)
