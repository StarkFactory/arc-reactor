package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.settings.RuntimeSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Input Guard 파이프라인 조회 API.
 *
 * 등록된 Guard 단계 목록, 순서, 활성화 상태를 조회한다.
 * 프론트엔드 `/input-guard` 페이지의 백엔드.
 */
@Tag(name = "Input Guard", description = "Input Guard 파이프라인 조회 (ADMIN)")
@RestController
@RequestMapping("/api/input-guard")
class InputGuardController(
    private val stagesProvider: ObjectProvider<List<GuardStage>>,
    private val settingsProvider: ObjectProvider<RuntimeSettingsService>,
    private val adminAuditStore: AdminAuditStore
) {

    /** 등록된 Input Guard 단계 목록을 조회한다. */
    @Operation(summary = "Input Guard 파이프라인 단계 목록")
    @GetMapping("/pipeline")
    fun pipeline(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val settings = settingsProvider.ifAvailable
        val stages = stagesProvider.ifAvailable.orEmpty()
            .sortedBy { it.order }
            .map { it.toResponse(settings) }
        return ResponseEntity.ok(stages)
    }

    /** Guard 단계 설정을 변경한다 (RuntimeSettings 기반, 재시작 필요). */
    @Operation(summary = "Guard 단계 설정 변경")
    @PutMapping("/settings")
    fun updateSettings(
        @Valid @RequestBody request: GuardSettingsRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val settings = settingsProvider.ifAvailable
            ?: return badRequestResponse("RuntimeSettingsService가 활성화되지 않았습니다")

        for ((key, value) in request.settings) {
            if (!key.startsWith("guard.")) continue
            settings.set(key, value)
        }
        recordAdminAudit(
            store = adminAuditStore, category = "input_guard", action = "UPDATE_SETTINGS",
            actor = currentActor(exchange), detail = "keys=${request.settings.keys}"
        )
        return ResponseEntity.ok(mapOf("updated" to request.settings.size, "note" to "일부 변경은 서버 재시작 후 적용됩니다"))
    }

    private fun GuardStage.toResponse(settings: RuntimeSettingsService?) = GuardStageResponse(
        name = stageName,
        order = order,
        enabled = enabled,
        className = this::class.simpleName.orEmpty(),
        runtimeOverride = settings?.getString("guard.stage.$stageName.enabled", "")?.takeIf { it.isNotBlank() }
    )
}

data class GuardStageResponse(
    val name: String,
    val order: Int,
    val enabled: Boolean,
    val className: String,
    val runtimeOverride: String? = null
)

data class GuardSettingsRequest(
    val settings: Map<String, String>
)
