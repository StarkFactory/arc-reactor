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

@Tag(name = "Admin Capabilities", description = "Admin feature capability manifest")
@RestController
@RequestMapping("/api/admin/capabilities")
class AdminCapabilitiesController(
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping
) {

    @Operation(summary = "Get admin capability manifest (admin)")
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
