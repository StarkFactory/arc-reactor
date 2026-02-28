package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.UploadFileResult
import com.arc.reactor.slack.tools.usecase.UploadFileUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class UploadFileToolTest {

    private val uploadFileUseCase = mockk<UploadFileUseCase>()
    private val tool = UploadFileTool(uploadFileUseCase)

    @Test
    fun `uploads file successfully`() {
        every {
            uploadFileUseCase.execute(
                channelId = "C123",
                filename = "report.txt",
                content = "hello",
                title = null,
                initialComment = null,
                threadTs = null
            )
        } returns UploadFileResult(ok = true, fileId = "F123", fileName = "report.txt")

        val result = tool.upload_file("C123", "report.txt", "hello", null, null, null)
        result shouldContain "\"ok\":true"
        result shouldContain "F123"
    }

    @Test
    fun `returns error for invalid channel id`() {
        val result = tool.upload_file("123", "report.txt", "hello", null, null, null)
        result shouldContain "channelId must be a valid Slack channel ID"
        verify(exactly = 0) { uploadFileUseCase.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid filename`() {
        val result = tool.upload_file("C123", "dir/report.txt", "hello", null, null, null)
        result shouldContain "filename must be 1-255 chars and must not include path separators"
        verify(exactly = 0) { uploadFileUseCase.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns error for blank content`() {
        val result = tool.upload_file("C123", "report.txt", " ", null, null, null)
        result shouldContain "content is required"
        verify(exactly = 0) { uploadFileUseCase.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns error for invalid thread timestamp`() {
        val result = tool.upload_file("C123", "report.txt", "hello", null, null, "abc")
        result shouldContain "threadTs must be a valid Slack timestamp"
        verify(exactly = 0) { uploadFileUseCase.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `normalizes optional text fields`() {
        every {
            uploadFileUseCase.execute(
                channelId = "C123",
                filename = "report.txt",
                content = "hello",
                title = "Title",
                initialComment = "comment",
                threadTs = "1234.5678"
            )
        } returns UploadFileResult(ok = true, fileId = "F123")

        val result = tool.upload_file(" C123 ", " report.txt ", " hello ", " Title ", " comment ", " 1234.5678 ")
        result shouldContain "\"ok\":true"
        verify {
            uploadFileUseCase.execute(
                channelId = "C123",
                filename = "report.txt",
                content = "hello",
                title = "Title",
                initialComment = "comment",
                threadTs = "1234.5678"
            )
        }
    }
}
