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
 * 멀티 에이전트 컨트롤러 예제.
 *
 * REST 엔드포인트를 통해 멀티 에이전트 오케스트레이션을 노출하는 방법을 보여줍니다.
 * 컨트롤러는 HTTP 관심사만 처리하고, 에이전트 오케스트레이션 로직은 서비스 클래스에 있습니다.
 *
 * [@RestController] 주석을 해제하면 활성화됩니다.
 *
 * @see CustomerServiceExample Supervisor 패턴 (서비스 계층)
 * @see ReportPipelineExample Sequential 패턴 (서비스 계층)
 * @see CodeReviewExample Parallel 패턴 (서비스 계층)
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
     * Supervisor: 적절한 전문가 에이전트에게 자동으로 위임한다.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/supervisor \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "I want to cancel my order and get a refund"}'
     * ```
     */
    @Operation(summary = "Supervisor: 적절한 전문가 에이전트에게 위임")
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
     * Sequential: 조사 -> 작성 -> 검토 파이프라인.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/sequential \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "Write a report about AI trends in 2025"}'
     * ```
     */
    @Operation(summary = "Sequential: 조사 -> 작성 -> 검토 파이프라인")
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
     * Parallel: 보안 + 스타일 + 로직 검토를 동시에 수행한다.
     *
     * ```bash
     * curl -X POST http://localhost:8080/api/multi/parallel \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "Review this code: fun add(a: Int, b: Int) = a + b"}'
     * ```
     */
    @Operation(summary = "Parallel: 보안 + 스타일 + 로직 동시 검토")
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
