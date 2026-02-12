package com.arc.reactor.controller.example

import com.arc.reactor.agent.multi.example.CodeReviewExample
import com.arc.reactor.agent.multi.example.CustomerServiceExample
import com.arc.reactor.agent.multi.example.ReportPipelineExample
import com.arc.reactor.controller.ChatRequest
import com.arc.reactor.controller.ChatResponse
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
