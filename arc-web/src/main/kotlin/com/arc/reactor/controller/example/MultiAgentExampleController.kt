package com.arc.reactor.controller.example

import com.arc.reactor.agent.multi.example.CodeReviewExample
import com.arc.reactor.agent.multi.example.CustomerServiceExample
import com.arc.reactor.agent.multi.example.ReportPipelineExample
import com.arc.reactor.controller.ChatRequest
import com.arc.reactor.controller.ChatResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Multi-agent controller examples.
 *
 * Demonstrates how to expose multi-agent orchestration via REST endpoints.
 * The controller only handles HTTP concerns -- all agent orchestration
 * logic lives in service classes.
 *
 * Uncomment [@RestController] to activate.
 *
 * @see CustomerServiceExample Supervisor pattern (service layer)
 * @see ReportPipelineExample Sequential pattern (service layer)
 * @see CodeReviewExample Parallel pattern (service layer)
 */
// @RestController  <- Uncomment to activate
// @RequestMapping("/api/multi")
// @Tag(name = "Multi-Agent Examples", description = "Multi-agent orchestration example endpoints")
class MultiAgentExampleController(
    private val customerService: CustomerServiceExample,
    private val reportPipeline: ReportPipelineExample,
    private val codeReview: CodeReviewExample
) {

    /**
     * Supervisor: delegates to the right specialist automatically.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/supervisor \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "I want to cancel my order and get a refund"}'
     * ```
     */
    @Operation(summary = "Supervisor: delegate to the right specialist agent")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Agent response"),
        ApiResponse(responseCode = "400", description = "Invalid request")
    ])
    @PostMapping("/supervisor")
    suspend fun supervisor(
        @Valid @RequestBody request: ChatRequest
    ): ChatResponse {
        val result = customerService.handle(
            request.message, request.userId
        )
        return ChatResponse(
            content = result.finalResult.content,
            success = result.success,
            toolsUsed = result.finalResult.toolsUsed,
            errorMessage = result.finalResult.errorMessage
        )
    }

    /**
     * Sequential: research -> write -> review pipeline.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/sequential \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "Write a report about AI trends in 2025"}'
     * ```
     */
    @Operation(summary = "Sequential: research → write → review pipeline")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Agent response"),
        ApiResponse(responseCode = "400", description = "Invalid request")
    ])
    @PostMapping("/sequential")
    suspend fun sequential(
        @Valid @RequestBody request: ChatRequest
    ): ChatResponse {
        val result = reportPipeline.handle(
            request.message, request.userId
        )
        return ChatResponse(
            content = result.finalResult.content,
            success = result.success,
            toolsUsed = result.finalResult.toolsUsed,
            errorMessage = result.finalResult.errorMessage
        )
    }

    /**
     * Parallel: security + style + logic review concurrently.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/parallel \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "Review this code: fun add(a: Int, b: Int) = a + b"}'
     * ```
     */
    @Operation(summary = "Parallel: security + style + logic review concurrently")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Agent response"),
        ApiResponse(responseCode = "400", description = "Invalid request")
    ])
    @PostMapping("/parallel")
    suspend fun parallel(
        @Valid @RequestBody request: ChatRequest
    ): ChatResponse {
        val result = codeReview.handle(
            request.message, request.userId
        )
        return ChatResponse(
            content = result.finalResult.content,
            success = result.success,
            toolsUsed = result.finalResult.toolsUsed,
            errorMessage = result.finalResult.errorMessage
        )
    }
}
