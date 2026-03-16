package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.usecase.AppendCanvasUseCase
import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/** 기존 Slack Canvas에 마크다운 내용을 추가하는 도구. 소유권 허용 목록 확인 후 실행된다. */
open class AppendCanvasTool(
    private val appendCanvasUseCase: AppendCanvasUseCase,
    private val canvasOwnershipPolicyService: CanvasOwnershipPolicyService =
        AllowAllCanvasOwnershipPolicyService,
    private val idempotencyService: WriteOperationIdempotencyService =
        NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Append markdown content to an existing Slack canvas.")
    fun append_canvas(
        @ToolParam(description = "Slack canvas ID") canvasId: String,
        @ToolParam(description = "Markdown to append") markdown: String,
        @ToolParam(
            description = "Optional idempotency key to prevent duplicate writes",
            required = false
        )
        idempotencyKey: String? = null
    ): String {
        val normalizedCanvasId = ToolInputValidation.normalizeRequired(canvasId)
            ?: return errorJson("canvasId is required")
        val normalizedMarkdown = ToolInputValidation.normalizeRequired(markdown)
            ?: return errorJson("markdown is required")
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        if (!canvasOwnershipPolicyService.canEdit(normalizedCanvasId)) {
            return errorJson(
                "Access denied: canvasId is not in ownership allowlist. " +
                    "Create it first with create_canvas."
            )
        }

        return idempotencyService.execute(
            toolName = "append_canvas",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(
                normalizedCanvasId,
                ToolInputValidation.sha256Hex(normalizedMarkdown)
            )
        ) {
            toJson(
                appendCanvasUseCase.execute(
                    canvasId = normalizedCanvasId,
                    markdown = normalizedMarkdown
                )
            )
        }
    }
}
