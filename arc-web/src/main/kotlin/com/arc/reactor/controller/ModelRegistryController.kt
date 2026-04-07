package com.arc.reactor.controller

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.config.AgentProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 모델 레지스트리 API.
 *
 * 등록된 LLM 모델 목록, 가격표, 현재 기본 모델을 조회한다.
 */
@Tag(name = "Models", description = "LLM 모델 레지스트리 (ADMIN)")
@RestController
@RequestMapping("/api/admin/models")
class ModelRegistryController(
    private val costCalculatorProvider: ObjectProvider<CostCalculator>,
    private val agentPropertiesProvider: ObjectProvider<AgentProperties>
) {

    /** 등록된 모델 목록 및 가격표를 조회한다. */
    @Operation(summary = "모델 목록 및 가격 조회")
    @GetMapping
    fun list(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val defaultModel = resolveDefaultModel()
        val models = CostCalculator.DEFAULT_PRICING.map { (name, pricing) ->
            ModelResponse(
                name = name,
                inputPricePerMillionTokens = pricing.inputPerMillionTokens,
                outputPricePerMillionTokens = pricing.outputPerMillionTokens,
                isDefault = name == defaultModel
            )
        }
        return ResponseEntity.ok(models)
    }

    private fun resolveDefaultModel(): String {
        return agentPropertiesProvider.ifAvailable?.llm?.defaultModel.orEmpty()
            .ifBlank { System.getenv("SPRING_AI_GOOGLE_GENAI_CHAT_OPTIONS_MODEL").orEmpty() }
    }
}

data class ModelResponse(
    val name: String,
    val inputPricePerMillionTokens: Double,
    val outputPricePerMillionTokens: Double,
    val isDefault: Boolean
)
