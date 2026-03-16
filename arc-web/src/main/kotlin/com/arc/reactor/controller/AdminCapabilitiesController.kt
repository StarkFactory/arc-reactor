package com.arc.reactor.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.result.method.RequestMappingInfo
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * 관리자 기능 매니페스트 컨트롤러.
 *
 * 현재 등록된 모든 API 경로를 조회하여 관리 콘솔이 사용 가능한 기능을
 * 자동으로 파악할 수 있도록 합니다.
 *
 * @see RequestMappingHandlerMapping
 */
@Tag(name = "Admin Capabilities", description = "Admin feature capability manifest")
@RestController
@RequestMapping("/api/admin/capabilities")
class AdminCapabilitiesController(
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping
) {

    /** 관리자 기능 매니페스트를 조회한다. /api/ 접두사를 가진 경로만 반환한다. */
    @Operation(summary = "관리자 기능 매니페스트 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Capability manifest"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun getCapabilities(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()

        val paths = requestMappingHandlerMapping.handlerMethods.keys
            .asSequence()
            .flatMap { info -> info.apiPaths().asSequence() }
            .filter { it.startsWith("/api/") }
            .distinct()
            .sorted()
            .toList()

        return ResponseEntity.ok(
            AdminCapabilitiesResponse(
                generatedAt = Instant.now().toEpochMilli(),
                source = "request-mappings",
                paths = paths
            )
        )
    }

    private fun RequestMappingInfo.apiPaths(): Set<String> {
        val patterns = patternsCondition.patterns
        return patterns.map { it.patternString }.toSet()
    }
}

data class AdminCapabilitiesResponse(
    val generatedAt: Long,
    val source: String,
    val paths: List<String>
)
