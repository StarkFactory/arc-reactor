package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.CanvasEditResult
import com.arc.reactor.slack.tools.usecase.AppendCanvasUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [AppendCanvasTool]의 단위 테스트.
 *
 * 캔버스 내용 추가 도구의 소유권 정책 검증 및 마크다운 추가 위임을 검증한다.
 */
class AppendCanvasToolTest {

    private val appendCanvasUseCase = mockk<AppendCanvasUseCase>()
    private val policyService = mockk<CanvasOwnershipPolicyService>()
    private val tool = AppendCanvasTool(appendCanvasUseCase, policyService)

    @Test
    fun `blocks edit when canvas은(는) not in allowlist이다`() {
        every { policyService.canEdit("F123") } returns false

        val result = tool.append_canvas("F123", "more details")

        result shouldContain "Access denied: canvasId is not in ownership allowlist"
        verify(exactly = 0) { appendCanvasUseCase.execute(any(), any()) }
    }

    @Test
    fun `appends markdown when canvas은(는) allowed이다`() {
        every { policyService.canEdit("F123") } returns true
        every { appendCanvasUseCase.execute("F123", "more details") } returns
            CanvasEditResult(ok = true, canvasId = "F123")

        val result = tool.append_canvas("F123", "more details")

        result shouldContain "\"ok\":true"
        verify { appendCanvasUseCase.execute("F123", "more details") }
    }
}
