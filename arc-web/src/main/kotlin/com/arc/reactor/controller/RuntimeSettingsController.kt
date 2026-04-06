package com.arc.reactor.controller

import com.arc.reactor.settings.RuntimeSetting
import com.arc.reactor.settings.RuntimeSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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

/**
 * 런타임 설정 관리 API.
 *
 * Admin이 재배포 없이 기능 토글/파라미터를 변경할 수 있다.
 */
@Tag(name = "Runtime Settings", description = "런타임 설정 관리 (재배포 불필요)")
@RestController
@RequestMapping("/api/admin/settings")
class RuntimeSettingsController(
    private val settingsService: RuntimeSettingsService
) {

    @Operation(summary = "전체 설정 목록 조회")
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        return ResponseEntity.ok(settingsService.list())
    }

    @Operation(summary = "개별 설정 조회")
    @GetMapping("/{key}")
    fun get(@PathVariable key: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val value = settingsService.getString(key, "")
        if (value.isBlank()) return notFoundResponse("설정을 찾을 수 없습니다: $key")
        return ResponseEntity.ok(mapOf("key" to key, "value" to value))
    }

    @Operation(summary = "설정 변경")
    @PutMapping("/{key}")
    fun set(
        @PathVariable key: String,
        @Valid @RequestBody request: UpdateSettingRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val userId = exchange.attributes["arc.user.id"]?.toString()
        settingsService.set(
            key = key,
            value = request.value,
            type = request.type ?: "STRING",
            category = request.category ?: "general",
            description = request.description,
            updatedBy = userId
        )
        return ResponseEntity.ok(mapOf("key" to key, "value" to request.value, "status" to "updated"))
    }

    @Operation(summary = "설정 삭제 (기본값 리셋)")
    @DeleteMapping("/{key}")
    fun delete(@PathVariable key: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        settingsService.delete(key)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "캐시 무효화")
    @PostMapping("/refresh")
    fun refresh(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        settingsService.refreshCache()
        return ResponseEntity.ok(mapOf("status" to "cache_refreshed"))
    }
}

data class UpdateSettingRequest(
    @field:NotBlank(message = "value는 필수입니다")
    val value: String,
    val type: String? = null,
    val category: String? = null,
    val description: String? = null
)
