package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions

/**
 * ReActLoopUtils에 대한 테스트.
 *
 * ReAct 루프 공용 유틸리티(도구 에러 감지, 힌트 주입, 정규화 판단 등)를 검증한다.
 */
class ReActLoopUtilsTest {

    @Nested
    inner class ShouldNormalizeToolResponses {

        @Test
        fun `GoogleGenAiChatOptions이면 true를 반환해야 한다`() {
            val options = GoogleGenAiChatOptions.builder().build()

            ReActLoopUtils.shouldNormalizeToolResponses(options)
                .shouldBe(true)
        }

        @Test
        fun `Google GenAI가 아닌 ChatOptions이면 false를 반환해야 한다`() {
            val options = mockk<ChatOptions>()

            ReActLoopUtils.shouldNormalizeToolResponses(options)
                .shouldBe(false)
        }
    }

    @Nested
    inner class BuildMaxToolCallsMessage {

        @Test
        fun `도구 호출 횟수와 최대값을 포함한 메시지를 생성해야 한다`() {
            val message = ReActLoopUtils.buildMaxToolCallsMessage(10, 10)

            message.text shouldContain "10/10"
            message.text shouldContain "Tool call limit reached"
        }

        @Test
        fun `최종 답변 요청 안내를 포함해야 한다`() {
            val message = ReActLoopUtils.buildMaxToolCallsMessage(5, 5)

            message.text shouldContain "Summarize"
            message.text shouldContain "Do not request additional tool calls"
        }

        @Test
        fun `다양한 횟수에서 올바른 형식을 생성해야 한다`() {
            val message = ReActLoopUtils.buildMaxToolCallsMessage(3, 15)

            message.text shouldContain "3/15"
        }
    }

    @Nested
    inner class HasToolError {

        @Test
        fun `에러 응답이 있으면 true를 반환해야 한다`() {
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "Error: timeout")
            )

            ReActLoopUtils.hasToolError(responses)
                .shouldBe(true)
        }

        @Test
        fun `에러 응답이 없으면 false를 반환해야 한다`() {
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "success result")
            )

            ReActLoopUtils.hasToolError(responses)
                .shouldBe(false)
        }

        @Test
        fun `빈 리스트에서 false를 반환해야 한다`() {
            ReActLoopUtils.hasToolError(emptyList())
                .shouldBe(false)
        }

        @Test
        fun `여러 응답 중 하나라도 에러면 true를 반환해야 한다`() {
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "ok"),
                ToolResponseMessage.ToolResponse("id2", "tool2", "Error: not found")
            )

            ReActLoopUtils.hasToolError(responses)
                .shouldBe(true)
        }
    }

    @Nested
    inner class InjectToolErrorRetryHint {

        @Test
        fun `에러 응답이 있으면 힌트를 주입해야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("hello"))
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "Error: failed")
            )

            ReActLoopUtils.injectToolErrorRetryHint(responses, messages)

            messages.size shouldBe 2
            (messages.last() as UserMessage).text shouldBe ReActLoopUtils.TOOL_ERROR_RETRY_HINT
        }

        @Test
        fun `에러 응답이 없으면 힌트를 주입하지 않아야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("hello"))
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "success")
            )

            ReActLoopUtils.injectToolErrorRetryHint(responses, messages)

            messages.size shouldBe 1
        }

        @Test
        fun `이전 iteration의 힌트를 제거해야 한다`() {
            val messages = mutableListOf<Message>(
                UserMessage("hello"),
                UserMessage(ReActLoopUtils.TOOL_ERROR_RETRY_HINT)
            )
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "success")
            )

            ReActLoopUtils.injectToolErrorRetryHint(responses, messages)

            messages.size shouldBe 1
            (messages[0] as UserMessage).text shouldBe "hello"
        }

        @Test
        fun `이전 힌트 제거 후 새 힌트를 주입해야 한다`() {
            val messages = mutableListOf<Message>(
                UserMessage("hello"),
                UserMessage(ReActLoopUtils.TOOL_ERROR_RETRY_HINT)
            )
            val responses = listOf(
                ToolResponseMessage.ToolResponse("id1", "tool1", "Error: retry")
            )

            ReActLoopUtils.injectToolErrorRetryHint(responses, messages)

            // 이전 힌트 제거 + 새 힌트 추가 = hello + 새 힌트
            messages.size shouldBe 2
            (messages[0] as UserMessage).text shouldBe "hello"
            (messages[1] as UserMessage).text shouldBe ReActLoopUtils.TOOL_ERROR_RETRY_HINT
        }
    }

    @Nested
    inner class InjectForceRetryHintIfNeeded {

        @Test
        fun `재시도 한도 미만이면 힌트를 주입하고 true를 반환해야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("hello"))

            val result = ReActLoopUtils.injectForceRetryHintIfNeeded(messages, 0)

            result shouldBe true
            messages.size shouldBe 2
            messages.last() shouldBe SystemMessage(
                "IMPORTANT: You just responded with text instead of a tool call. " +
                    "You MUST make a tool call to retry. Do NOT explain or apologize — " +
                    "directly call the tool with corrected parameters.\n" +
                    "중요: 방금 텍스트로 응답했습니다. 반드시 도구 호출로 재시도하세요. " +
                    "설명이나 사과 없이 수정된 파라미터로 도구를 직접 호출하세요."
            )
        }

        @Test
        fun `재시도 한도에 도달하면 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("hello"))

            val result = ReActLoopUtils.injectForceRetryHintIfNeeded(
                messages,
                ReActLoopUtils.MAX_TEXT_RETRIES_AFTER_TOOL_ERROR
            )

            result shouldBe false
            messages.size shouldBe 1
        }

        @Test
        fun `한도 초과 시에도 false를 반환해야 한다`() {
            val messages = mutableListOf<Message>(UserMessage("hello"))

            val result = ReActLoopUtils.injectForceRetryHintIfNeeded(messages, 10)

            result shouldBe false
        }

        @Test
        fun `이전 힌트를 정리한 후 새 힌트를 주입해야 한다`() {
            val messages = mutableListOf<Message>(
                UserMessage("hello"),
                UserMessage(ReActLoopUtils.TOOL_ERROR_RETRY_HINT)
            )

            val result = ReActLoopUtils.injectForceRetryHintIfNeeded(messages, 1)

            result shouldBe true
            // 이전 UserMessage 힌트 제거됨, 새 SystemMessage 힌트 추가
            messages.size shouldBe 2
            (messages[0] as UserMessage).text shouldBe "hello"
            (messages[1] is SystemMessage) shouldBe true
        }
    }

    @Nested
    inner class Constants {

        @Test
        fun `TOOL_ERROR_PREFIX는 Error 콜론으로 시작해야 한다`() {
            ReActLoopUtils.TOOL_ERROR_PREFIX shouldBe "Error:"
        }

        @Test
        fun `TOKENS_PER_TOOL_DEFINITION은 양수여야 한다`() {
            (ReActLoopUtils.TOKENS_PER_TOOL_DEFINITION > 0) shouldBe true
        }

        @Test
        fun `MAX_TEXT_RETRIES_AFTER_TOOL_ERROR는 양수여야 한다`() {
            (ReActLoopUtils.MAX_TEXT_RETRIES_AFTER_TOOL_ERROR > 0) shouldBe true
        }
    }
}
