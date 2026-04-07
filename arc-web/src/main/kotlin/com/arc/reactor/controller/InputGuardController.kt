package com.arc.reactor.controller

import com.arc.reactor.guard.GuardStage
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
    private val stagesProvider: ObjectProvider<List<GuardStage>>
) {

    /** 등록된 Input Guard 단계 목록을 조회한다. */
    @Operation(summary = "Input Guard 파이프라인 단계 목록")
    @GetMapping("/pipeline")
    fun pipeline(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val stages = stagesProvider.ifAvailable.orEmpty()
            .sortedBy { it.order }
            .map { it.toResponse() }
        return ResponseEntity.ok(stages)
    }

    private fun GuardStage.toResponse() = GuardStageResponse(
        name = stageName,
        order = order,
        enabled = enabled,
        className = this::class.simpleName.orEmpty()
    )
}

data class GuardStageResponse(
    val name: String,
    val order: Int,
    val enabled: Boolean,
    val className: String
)
