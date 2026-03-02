package com.arc.reactor.slack.handler

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SlackResponseTextFormatterTest {

    @Test
    fun `successful result returns content as-is`() {
        val result = AgentResult(success = true, content = "Actionable answer")

        SlackResponseTextFormatter.fromResult(result, "anything") shouldBe "Actionable answer"
    }

    @Test
    fun `blank successful content returns fallback placeholder`() {
        val result = AgentResult(success = true, content = "  ")

        SlackResponseTextFormatter.fromResult(result, "anything")
            .shouldBe("I processed your request but have no response.")
    }

    @Test
    fun `failure result returns warning with reason`() {
        val result = AgentResult(
            success = false,
            content = null,
            errorCode = AgentErrorCode.GUARD_REJECTED,
            errorMessage = "unsafe input"
        )

        SlackResponseTextFormatter.fromResult(result, "anything")
            .shouldBe(":warning: unsafe input")
    }

    @Test
    fun `generic refusal for task planning prompt is rewritten to best effort answer`() {
        val result = AgentResult(success = true, content = "요청하신 작업을 수행할 수 없습니다")

        val text = SlackResponseTextFormatter.fromResult(result, "오늘 할 일 우선순위 정리해줘")

        text shouldContain "바로 실행 가능한 초안으로 정리합니다."
        text shouldContain "오늘 반드시 끝낼 핵심 작업"
        text shouldNotContain "요청하신 작업을 수행할 수 없습니다"
    }

    @Test
    fun `generic refusal for non planning prompt is rewritten to generic action checklist`() {
        val result = AgentResult(success = true, content = "I cannot fulfill this request.")

        val text = SlackResponseTextFormatter.fromResult(result, "brainstorm facilitation tips")

        text shouldContain "요청을 처리하기에 충분한 실시간 맥락이 없어도"
        text shouldContain "- 목표를 한 줄로 확정"
        text shouldNotContain "cannot fulfill this request"
    }
}
