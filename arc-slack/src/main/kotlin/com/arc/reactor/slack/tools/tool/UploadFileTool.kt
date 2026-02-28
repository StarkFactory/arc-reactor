package com.arc.reactor.slack.tools.tool
import com.arc.reactor.tool.LocalTool

import com.arc.reactor.slack.tools.usecase.UploadFileUseCase
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

open class UploadFileTool(
    private val uploadFileUseCase: UploadFileUseCase,
    private val idempotencyService: WriteOperationIdempotencyService = NoopWriteOperationIdempotencyService
) : LocalTool {

    @Tool(description = "Upload a text file to a Slack channel with optional initial comment and thread reply.")
    fun upload_file(
        @ToolParam(description = "Slack channel ID to upload the file to") channelId: String,
        @ToolParam(description = "File name (e.g., report.txt)") filename: String,
        @ToolParam(description = "File content as text") content: String,
        @ToolParam(description = "File title (optional)", required = false) title: String?,
        @ToolParam(description = "Initial comment to post with the file (optional)", required = false) initialComment: String?,
        @ToolParam(description = "Thread timestamp to upload as a reply (optional)", required = false) threadTs: String?,
        @ToolParam(description = "Optional idempotency key to prevent duplicate writes", required = false) idempotencyKey: String? = null
    ): String {
        val normalizedChannelId = ToolInputValidation.normalizeChannelId(channelId)
            ?: return errorJson("channelId must be a valid Slack channel ID")
        val normalizedFilename = ToolInputValidation.normalizeFilename(filename)
            ?: return errorJson("filename must be 1-255 chars and must not include path separators")
        val normalizedContent = ToolInputValidation.normalizeRequired(content)
            ?: return errorJson("content is required")
        val normalizedThreadTs = threadTs?.let {
            ToolInputValidation.normalizeThreadTs(it)
                ?: return errorJson("threadTs must be a valid Slack timestamp")
        }
        val normalizedTitle = ToolInputValidation.normalizeOptional(title)
        val normalizedInitialComment = ToolInputValidation.normalizeOptional(initialComment)
        val dedupeKey = ToolInputValidation.normalizeOptional(idempotencyKey)

        return idempotencyService.execute(
            toolName = "upload_file",
            explicitIdempotencyKey = dedupeKey,
            keyParts = listOf(
                normalizedChannelId,
                normalizedFilename,
                ToolInputValidation.sha256Hex(normalizedContent),
                normalizedTitle.orEmpty(),
                normalizedInitialComment.orEmpty(),
                normalizedThreadTs.orEmpty()
            )
        ) {
            toJson(
                uploadFileUseCase.execute(
                    channelId = normalizedChannelId,
                    filename = normalizedFilename,
                    content = normalizedContent,
                    title = normalizedTitle,
                    initialComment = normalizedInitialComment,
                    threadTs = normalizedThreadTs
                )
            )
        }
    }
}
