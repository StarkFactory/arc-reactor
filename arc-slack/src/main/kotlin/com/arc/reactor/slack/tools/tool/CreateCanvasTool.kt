package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.usecase.CreateCanvasUseCase
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class CreateCanvasTool(
    private val createCanvasUseCase: CreateCanvasUseCase,
    private val canvasOwnershipPolicyService: CanvasOwnershipPolicyService =
        AllowAllCanvasOwnershipPolicyService,
    private val idempotencyService: WriteOperationIdempotencyService =
        NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Create a new Slack canvas owned by this app/bot token.")
    fun create_canvas(
        @ToolParam(description = "Canvas title") title: String,
        @ToolParam(description = "Canvas markdown content") markdown: String,
        @ToolParam(
            description = "Optional idempotency key to prevent duplicate writes",
            required = false
        )
        idempotencyKey: String? = null
    ): String {
        val normalizedTitle = ToolInputValidation.normalizeRequired(title)
            ?: return errorJson("title is required")
        val normalizedMarkdown = ToolInputValidation.normalizeRequired(markdown)
            ?: return errorJson("markdown is required")
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        return idempotencyService.execute(
            toolName = "create_canvas",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(
                normalizedTitle,
                ToolInputValidation.sha256Hex(normalizedMarkdown)
            )
        ) {
            val result = createCanvasUseCase.execute(
                title = normalizedTitle,
                markdown = normalizedMarkdown
            )
            if (result.ok && !result.canvasId.isNullOrBlank()) {
                canvasOwnershipPolicyService.registerOwned(result.canvasId)
            }
            toJson(result)
        }
    }
}
