package com.arc.reactor.controller

import com.arc.reactor.agent.multiagent.AgentSpecRecord
import com.arc.reactor.agent.multiagent.AgentSpecStore
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
    private val store: AgentSpecStore
) {

    /** 전체 에이전트 스펙 목록을 조회한다. */
    @Operation(summary = "전체 에이전트 스펙 목록 조회")
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(store.list().map { it.toResponse() })
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
        if (store.list().any { it.name == request.name }) {
            return conflictResponse("이름 '${request.name}'은 이미 사용 중입니다")
        }
        val record = AgentSpecRecord(
            id = UUID.randomUUID().toString(),
            name = request.name,
            description = request.description.orEmpty(),
            toolNames = request.toolNames.orEmpty(),
            keywords = request.keywords.orEmpty(),
            systemPrompt = request.systemPrompt,
            mode = request.mode ?: "REACT",
            enabled = request.enabled ?: true
        )
        val saved = store.save(record)
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
        val existing = store.get(id)
            ?: return notFoundResponse("에이전트 스펙을 찾을 수 없습니다: $id")
        val updated = existing.copy(
            name = request.name ?: existing.name,
            description = request.description ?: existing.description,
            toolNames = request.toolNames ?: existing.toolNames,
            keywords = request.keywords ?: existing.keywords,
            systemPrompt = request.systemPrompt ?: existing.systemPrompt,
            mode = request.mode ?: existing.mode,
            enabled = request.enabled ?: existing.enabled,
            updatedAt = Instant.now()
        )
        val saved = store.save(updated)
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
        return ResponseEntity.noContent().build()
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
