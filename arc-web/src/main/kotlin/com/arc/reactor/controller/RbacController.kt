package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.auth.AdminScope
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 역할 기반 접근 제어(RBAC) API.
 *
 * 역할 목록, 권한 매트릭스 조회, 사용자 역할 변경을 지원한다.
 */
@Tag(name = "RBAC", description = "역할 기반 접근 제어 (ADMIN)")
@RestController
@RequestMapping("/api/admin/rbac")
class RbacController(
    private val userStore: UserStore,
    private val adminAuditStore: AdminAuditStore
) {

    /** 전체 역할 목록 및 권한 매트릭스를 조회한다. */
    @Operation(summary = "역할 및 권한 목록 조회")
    @GetMapping("/roles")
    fun listRoles(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(ROLE_DEFINITIONS)
    }

    /** 사용자의 역할을 변경한다. */
    @Operation(summary = "사용자 역할 변경")
    @PutMapping("/users/{userId}/role")
    fun updateUserRole(
        @PathVariable userId: String,
        @Valid @RequestBody request: UpdateRoleRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val role = try { UserRole.valueOf(request.role) } catch (_: Exception) {
            return badRequestResponse("유효하지 않은 역할: ${request.role}")
        }
        val user = userStore.findById(userId)
            ?: return notFoundResponse("사용자를 찾을 수 없습니다: $userId")
        userStore.update(user.copy(role = role))
        recordAdminAudit(
            store = adminAuditStore, category = "rbac", action = "UPDATE_ROLE",
            actor = currentActor(exchange), resourceType = "user",
            resourceId = userId, detail = "role=${role.name}"
        )
        return ResponseEntity.ok(mapOf("userId" to userId, "role" to role.name))
    }

    companion object {
        /** 역할별 권한 정의 (정적). */
        private val ROLE_DEFINITIONS = UserRole.entries.map { role ->
            RoleDefinition(
                role = role.name,
                scope = role.adminScope()?.name,
                permissions = permissionsFor(role)
            )
        }

        private fun permissionsFor(role: UserRole): List<String> = when (role) {
            UserRole.ADMIN -> listOf(
                "persona:read", "persona:write",
                "prompt:read", "prompt:write",
                "session:read", "session:export",
                "feedback:read",
                "guard:read", "guard:write",
                "mcp:read", "mcp:write",
                "scheduler:read", "scheduler:write",
                "audit:read", "audit:export",
                "user:read", "user:write",
                "settings:read", "settings:write",
                "agent-spec:read", "agent-spec:write"
            )
            UserRole.ADMIN_DEVELOPER -> listOf(
                "persona:read", "persona:write",
                "prompt:read", "prompt:write",
                "session:read",
                "feedback:read",
                "guard:read", "guard:write",
                "mcp:read", "mcp:write",
                "scheduler:read", "scheduler:write",
                "audit:read",
                "agent-spec:read", "agent-spec:write"
            )
            UserRole.ADMIN_MANAGER -> listOf(
                "session:read", "session:export",
                "feedback:read",
                "audit:read",
                "persona:read"
            )
            UserRole.USER -> listOf("chat:use", "persona:select")
        }
    }
}

data class RoleDefinition(
    val role: String,
    val scope: String?,
    val permissions: List<String>
)

data class UpdateRoleRequest(
    @field:NotBlank val role: String
)
