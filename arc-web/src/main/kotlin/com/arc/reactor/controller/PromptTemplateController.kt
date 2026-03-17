package com.arc.reactor.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import com.arc.reactor.prompt.PromptTemplate
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
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
import java.util.UUID

/**
 * 프롬프트 템플릿 관리 컨트롤러.
 *
 * 버전 관리되는 시스템 프롬프트 템플릿의 CRUD와 버전 생명주기
 * (DRAFT -> ACTIVE -> ARCHIVED)를 제공합니다.
 *
 * ## 템플릿 엔드포인트
 * - GET    /api/prompt-templates          : 전체 템플릿 목록 조회
 * - POST   /api/prompt-templates          : 새 템플릿 생성
 * - GET    /api/prompt-templates/{id}     : 템플릿 + 전체 버전 조회
 * - PUT    /api/prompt-templates/{id}     : 템플릿 메타데이터 수정
 * - DELETE /api/prompt-templates/{id}     : 템플릿 및 모든 버전 삭제
 *
 * ## 버전 엔드포인트
 * - POST   /api/prompt-templates/{id}/versions                  : 새 버전 생성
 * - PUT    /api/prompt-templates/{id}/versions/{vid}/activate   : 버전 활성화
 * - PUT    /api/prompt-templates/{id}/versions/{vid}/archive    : 버전 아카이브
 *
 * @see PromptTemplateStore
 */
@Tag(name = "Prompt Templates", description = "Versioned system prompt management (ADMIN only for write operations)")
@RestController
@RequestMapping("/api/prompt-templates")
class PromptTemplateController(
    private val promptTemplateStore: PromptTemplateStore
) {

    // ---- 템플릿 엔드포인트 ----

    /** 전체 프롬프트 템플릿 목록을 조회한다. */
    @Operation(summary = "전체 프롬프트 템플릿 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "List of prompt templates")
    ])
    @GetMapping
    fun listTemplates(): List<TemplateResponse> {
        return promptTemplateStore.listTemplates().map { it.toResponse() }
    }

    /** ID로 템플릿을 조회한다. 모든 버전을 함께 반환한다. */
    @Operation(summary = "템플릿 + 전체 버전 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Template details with versions"),
        ApiResponse(responseCode = "404", description = "Prompt template not found")
    ])
    @GetMapping("/{templateId}")
    fun getTemplate(@PathVariable templateId: String): ResponseEntity<Any> {
        val template = promptTemplateStore.getTemplate(templateId)
            ?: return notFoundResponse("Prompt template not found: $templateId")
        val versions = promptTemplateStore.listVersions(templateId)
        val activeVersion = versions.firstOrNull { it.status == VersionStatus.ACTIVE }
        return ResponseEntity.ok(template.toDetailResponse(versions, activeVersion))
    }

    /** 새 프롬프트 템플릿을 생성한다. ADMIN 권한이 필요하다. */
    @Operation(summary = "새 프롬프트 템플릿 생성 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Prompt template created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @PostMapping
    fun createTemplate(
        @Valid @RequestBody request: CreateTemplateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val template = PromptTemplate(
            id = UUID.randomUUID().toString(),
            name = request.name,
            description = request.description
        )
        val saved = promptTemplateStore.saveTemplate(template)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /** 템플릿 메타데이터를 수정한다. 제공된 필드만 변경된다. ADMIN 권한이 필요하다. */
    @Operation(summary = "템플릿 메타데이터 수정 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Template updated"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Prompt template not found")
    ])
    @PutMapping("/{templateId}")
    fun updateTemplate(
        @PathVariable templateId: String,
        @Valid @RequestBody request: UpdateTemplateRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val updated = promptTemplateStore.updateTemplate(
            id = templateId,
            name = request.name,
            description = request.description
        ) ?: return notFoundResponse("Prompt template not found: $templateId")
        return ResponseEntity.ok(updated.toResponse())
    }

    /** 템플릿과 모든 버전을 삭제한다. 멱등성을 보장한다. ADMIN 권한이 필요하다. */
    @Operation(summary = "템플릿 및 모든 버전 삭제 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "Template deleted"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @DeleteMapping("/{templateId}")
    fun deleteTemplate(
        @PathVariable templateId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        promptTemplateStore.deleteTemplate(templateId)
        return ResponseEntity.noContent().build()
    }

    // ---- 버전 엔드포인트 ----

    /** 템플릿에 새 버전을 생성한다. DRAFT 상태로 시작된다. ADMIN 권한이 필요하다. */
    @Operation(summary = "템플릿 새 버전 생성 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Version created"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Prompt template not found")
    ])
    @PostMapping("/{templateId}/versions")
    fun createVersion(
        @PathVariable templateId: String,
        @Valid @RequestBody request: CreateVersionRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val version = promptTemplateStore.createVersion(
            templateId = templateId,
            content = request.content,
            changeLog = request.changeLog
        ) ?: return notFoundResponse("Prompt template not found: $templateId")
        return ResponseEntity.status(HttpStatus.CREATED).body(version.toResponse())
    }

    /** 버전을 활성화한다. 기존 활성 버전이 있으면 자동으로 아카이브된다. ADMIN 권한이 필요하다. */
    @Operation(summary = "템플릿 버전 활성화 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Version activated"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Template or version not found")
    ])
    @PutMapping("/{templateId}/versions/{versionId}/activate")
    fun activateVersion(
        @PathVariable templateId: String,
        @PathVariable versionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val activated = promptTemplateStore.activateVersion(templateId, versionId)
            ?: return notFoundResponse("Template or version not found: $templateId/$versionId")
        return ResponseEntity.ok(activated.toResponse())
    }

    /** 버전을 아카이브한다. ADMIN 권한이 필요하다. */
    @Operation(summary = "템플릿 버전 아카이브 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Version archived"),
        ApiResponse(responseCode = "403", description = "Admin access required"),
        ApiResponse(responseCode = "404", description = "Template or version not found")
    ])
    @PutMapping("/{templateId}/versions/{versionId}/archive")
    fun archiveVersion(
        @PathVariable templateId: String,
        @PathVariable versionId: String,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val archived = promptTemplateStore.archiveVersion(versionId)
            ?: return notFoundResponse("Template or version not found: $templateId/$versionId")
        return ResponseEntity.ok(archived.toResponse())
    }
}

// --- 요청 DTO ---

data class CreateTemplateRequest(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = 200, message = "name must not exceed 200 characters")
    val name: String,
    @field:Size(max = 2000, message = "description must not exceed 2000 characters")
    val description: String = ""
)

data class UpdateTemplateRequest(
    @field:Size(max = 200, message = "name must not exceed 200 characters")
    val name: String? = null,
    @field:Size(max = 2000, message = "description must not exceed 2000 characters")
    val description: String? = null
)

data class CreateVersionRequest(
    @field:NotBlank(message = "content must not be blank")
    @field:Size(max = 100000, message = "content must not exceed 100000 characters")
    val content: String,
    @field:Size(max = 2000, message = "changeLog must not exceed 2000 characters")
    val changeLog: String = ""
)

// --- 응답 DTO ---

data class TemplateResponse(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class TemplateDetailResponse(
    val id: String,
    val name: String,
    val description: String,
    val activeVersion: VersionResponse?,
    val versions: List<VersionResponse>,
    val createdAt: Long,
    val updatedAt: Long
)

data class VersionResponse(
    val id: String,
    val templateId: String,
    val version: Int,
    val content: String,
    val status: String,
    val changeLog: String,
    val createdAt: Long
)

// --- 매핑 확장 ---

private fun PromptTemplate.toResponse() = TemplateResponse(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun PromptTemplate.toDetailResponse(
    versions: List<PromptVersion>,
    activeVersion: PromptVersion?
) = TemplateDetailResponse(
    id = id,
    name = name,
    description = description,
    activeVersion = activeVersion?.toResponse(),
    versions = versions.map { it.toResponse() },
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun PromptVersion.toResponse() = VersionResponse(
    id = id,
    templateId = templateId,
    version = version,
    content = content,
    status = status.name,
    changeLog = changeLog,
    createdAt = createdAt.toEpochMilli()
)
