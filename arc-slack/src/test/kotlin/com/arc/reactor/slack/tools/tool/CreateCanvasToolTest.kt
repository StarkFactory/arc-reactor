package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.client.CanvasCreateResult
import com.arc.reactor.slack.tools.usecase.CreateCanvasUseCase
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class CreateCanvasToolTest {

    private val createCanvasUseCase = mockk<CreateCanvasUseCase>()
    private val policyService = mockk<CanvasOwnershipPolicyService>(relaxed = true)
    private val tool = CreateCanvasTool(createCanvasUseCase, policyService)

    @Test
    fun `canvas and registers ownership를 생성한다`() {
        every { createCanvasUseCase.execute("Release Notes", "hello") } returns
            CanvasCreateResult(ok = true, canvasId = "F123")

        val result = tool.create_canvas("Release Notes", "hello")

        result shouldContain "\"ok\":true"
        result shouldContain "F123"
        verify { policyService.registerOwned("F123") }
    }

    @Test
    fun `blank title에 대해 validation error를 반환한다`() {
        val result = tool.create_canvas(" ", "hello")

        result shouldContain "title is required"
        verify(exactly = 0) { createCanvasUseCase.execute(any(), any()) }
    }

    @Test
    fun `blank markdown에 대해 validation error를 반환한다`() {
        val result = tool.create_canvas("Release Notes", " ")

        result shouldContain "markdown is required"
        verify(exactly = 0) { createCanvasUseCase.execute(any(), any()) }
    }
}
