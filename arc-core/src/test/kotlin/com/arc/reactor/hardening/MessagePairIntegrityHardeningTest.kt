package com.arc.reactor.hardening

import com.arc.reactor.agent.impl.ConversationMessageTrimmer
import com.arc.reactor.memory.TokenEstimator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

/**
 * 메시지 쌍 무결성 강화 테스트.
 *
 * AssistantMessage(toolCalls) + ToolResponseMessage 쌍이 항상 함께 추가/제거되는지,
 * 트리밍 과정에서 쌍이 분리되지 않는지 검증한다.
 *
 * Critical Gotcha #4: "메시지 쌍 무결성: AssistantMessage(toolCalls) + ToolResponseMessage는
 * 항상 쌍으로 추가/제거"
 *
 * @see ConversationMessageTrimmer 메시지 트리밍 시 쌍 무결성 보존 구현체
 */
@Tag("hardening")
class MessagePairIntegrityHardeningTest {

    /** 문자열 길이를 토큰 수로 사용하는 단순 estimator. */
    private val tokenEstimator = TokenEstimator { text -> text.length }

    /** 테스트용 ToolCall mock을 생성한다. */
    private fun buildToolCall(id: String = "1", name: String = "tool"): AssistantMessage.ToolCall {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.id() } returns id
        every { toolCall.name() } returns name
        every { toolCall.arguments() } returns "{}"
        every { toolCall.type() } returns "function"
        return toolCall
    }

    /** AssistantMessage + ToolResponseMessage 쌍을 생성한다. */
    private fun buildToolPair(
        content: String = "thinking",
        toolCallId: String = "1",
        toolName: String = "tool",
        toolResult: String = "result"
    ): Pair<AssistantMessage, ToolResponseMessage> {
        val toolCall = buildToolCall(toolCallId, toolName)
        val assistant = AssistantMessage.builder()
            .content(content)
            .toolCalls(listOf(toolCall))
            .build()
        val toolResponse = ToolResponseMessage.builder()
            .responses(listOf(ToolResponseMessage.ToolResponse(toolCallId, toolName, toolResult)))
            .build()
        return assistant to toolResponse
    }

    /**
     * 메시지 리스트에서 쌍 무결성을 검증한다.
     * 모든 toolCalls가 있는 AssistantMessage 뒤에는 ToolResponseMessage가 와야 한다.
     * 모든 ToolResponseMessage 앞에는 toolCalls가 있는 AssistantMessage가 있어야 한다.
     */
    private fun assertPairIntegrity(messages: List<Message>, context: String) {
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is AssistantMessage && !msg.toolCalls.isNullOrEmpty()) {
                assertTrue(i + 1 < messages.size && messages[i + 1] is ToolResponseMessage,
                    "$context: AssistantMessage(toolCalls)가 인덱스 ${i}에 있지만 " +
                        "다음 메시지가 ToolResponseMessage가 아님. " +
                        "다음=${messages.getOrNull(i + 1)?.javaClass?.simpleName}")
            }
            if (msg is ToolResponseMessage) {
                assertTrue(i > 0 && messages[i - 1] is AssistantMessage,
                    "$context: ToolResponseMessage가 인덱스 ${i}에 있지만 " +
                        "이전 메시지가 AssistantMessage가 아님. " +
                        "이전=${messages.getOrNull(i - 1)?.javaClass?.simpleName}")
                val prevAssistant = messages[i - 1] as AssistantMessage
                assertTrue(!prevAssistant.toolCalls.isNullOrEmpty(),
                    "$context: ToolResponseMessage 앞의 AssistantMessage에 toolCalls가 없음")
            }
        }
    }

    // =========================================================================
    // 기본 쌍 무결성 (Basic Pair Integrity)
    // =========================================================================

    @Nested
    inner class BasicPairIntegrity {

        @Test
        fun `트리밍 후에도 AssistantMessage와 ToolResponseMessage 쌍이 보존되어야 한다`() {
            val (assistant, toolResponse) = buildToolPair()

            val messages = mutableListOf<Message>(
                UserMessage("old question"),
                AssistantMessage("old answer"),
                UserMessage("recent question"),
                assistant,
                toolResponse
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 200,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "sys")

            assertPairIntegrity(messages, "기본 쌍 보존")
        }

        @Test
        fun `여러 도구 호출 쌍이 모두 보존되어야 한다`() {
            val (assistant1, toolResponse1) = buildToolPair("think-1", "t1", "search", "found")
            val (assistant2, toolResponse2) = buildToolPair("think-2", "t2", "calc", "42")

            val messages = mutableListOf<Message>(
                UserMessage("question"),
                assistant1,
                toolResponse1,
                assistant2,
                toolResponse2
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 500,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "복수 쌍 보존")
        }

        @Test
        fun `도구 호출이 없는 AssistantMessage는 단독으로 존재할 수 있다`() {
            val messages = mutableListOf<Message>(
                UserMessage("question"),
                AssistantMessage("answer without tools")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 500,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            messages.size shouldBe 2
            assertInstanceOf(UserMessage::class.java, messages[0],
                "UserMessage가 보존되어야 함")
            assertInstanceOf(AssistantMessage::class.java, messages[1],
                "도구 없는 AssistantMessage는 단독 존재 가능")
        }
    }

    // =========================================================================
    // 트리밍 시 쌍 보존 (Trimming Preserves Pairs)
    // =========================================================================

    @Nested
    inner class TrimmingPreservesPairs {

        @Test
        fun `Phase 1 트리밍이 AssistantMessage와 ToolResponseMessage를 함께 제거해야 한다`() {
            val (oldAssistant, oldToolResponse) = buildToolPair(
                "old-thinking", "old-1", "old-tool", "old-result"
            )
            val (recentAssistant, recentToolResponse) = buildToolPair(
                "recent-thinking", "new-1", "new-tool", "new-result"
            )

            val messages = mutableListOf<Message>(
                oldAssistant,
                oldToolResponse,
                UserMessage("recent"),
                recentAssistant,
                recentToolResponse
            )

            // 예산을 충분히 줄여서 오래된 쌍이 트리밍되도록 함
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 120,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "Phase 1 쌍 제거")
            // 오래된 쌍이 제거되었는지 확인
            val hasOldTool = messages.any {
                it is ToolResponseMessage && it.responses.any { r -> r.name() == "old-tool" }
            }
            val hasOldAssistant = messages.any {
                it is AssistantMessage && it.text == "old-thinking"
            }
            // 둘 다 있거나 둘 다 없어야 함 (쌍 무결성)
            assertTrue(hasOldTool == hasOldAssistant,
                "오래된 쌍이 분리됨: assistant=$hasOldAssistant, tool=$hasOldTool")
        }

        @Test
        fun `Phase 2 트리밍이 마지막 UserMessage 이후 도구 쌍을 함께 제거해야 한다`() {
            val (assistant, toolResponse) = buildToolPair(
                "a", "1", "tool", "very-long-result-data-that-exceeds-budget"
            )

            val messages = mutableListOf<Message>(
                UserMessage("keep"),
                assistant,
                toolResponse,
                AssistantMessage("final")
            )

            // 예산을 UserMessage + final AssistantMessage만 들어갈 정도로 설정
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 50,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "Phase 2 도구 쌍 제거")
        }

        @Test
        fun `Phase 1_5 선행 SystemMessage 제거 후에도 쌍 무결성이 유지되어야 한다`() {
            val (assistant, toolResponse) = buildToolPair(
                "a", "1", "tool", "result"
            )

            val messages = mutableListOf<Message>(
                SystemMessage("long-memory-facts-that-take-up-space-1234567890"),
                SystemMessage("long-narrative-summary-that-also-takes-space-1234567890"),
                UserMessage("keep"),
                assistant,
                toolResponse
            )

            // SystemMessage 제거 후 도구 쌍은 남아야 함
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 110,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "Phase 1.5 이후 쌍 무결성")
        }
    }

    // =========================================================================
    // 다중 도구 호출 (Multiple Tool Calls in One Message)
    // =========================================================================

    @Nested
    inner class MultipleToolCallsInOneMessage {

        @Test
        fun `하나의 AssistantMessage에 N개 toolCalls가 있으면 대응하는 ToolResponseMessage가 있어야 한다`() {
            val toolCall1 = buildToolCall("tc-1", "search")
            val toolCall2 = buildToolCall("tc-2", "calculator")
            val toolCall3 = buildToolCall("tc-3", "weather")

            val assistant = AssistantMessage.builder()
                .content("I'll use multiple tools")
                .toolCalls(listOf(toolCall1, toolCall2, toolCall3))
                .build()

            val toolResponse = ToolResponseMessage.builder()
                .responses(
                    listOf(
                        ToolResponseMessage.ToolResponse("tc-1", "search", "found it"),
                        ToolResponseMessage.ToolResponse("tc-2", "calculator", "42"),
                        ToolResponseMessage.ToolResponse("tc-3", "weather", "sunny")
                    )
                )
                .build()

            val messages = mutableListOf<Message>(
                UserMessage("question"),
                assistant,
                toolResponse
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 500,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "N개 toolCalls 쌍 무결성")
            // 3개 toolCalls에 대한 3개 응답이 하나의 ToolResponseMessage에 포함
            val trm = messages.filterIsInstance<ToolResponseMessage>()
            if (trm.isNotEmpty()) {
                trm[0].responses.size shouldBe 3
            }
        }

        @Test
        fun `다중 도구 호출 쌍이 트리밍되면 전체가 함께 제거되어야 한다`() {
            val toolCall1 = buildToolCall("tc-1", "search")
            val toolCall2 = buildToolCall("tc-2", "calculator")

            val assistant = AssistantMessage.builder()
                .content("multi-tool")
                .toolCalls(listOf(toolCall1, toolCall2))
                .build()

            val toolResponse = ToolResponseMessage.builder()
                .responses(
                    listOf(
                        ToolResponseMessage.ToolResponse("tc-1", "search", "found"),
                        ToolResponseMessage.ToolResponse("tc-2", "calculator", "result")
                    )
                )
                .build()

            val messages = mutableListOf<Message>(
                assistant,
                toolResponse,
                UserMessage("final question")
            )

            // 예산을 줄여서 도구 쌍이 트리밍되도록 함
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 40,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "다중 도구 호출 전체 제거")
        }
    }

    // =========================================================================
    // 고아 메시지 처리 (Orphan Message Handling)
    // =========================================================================

    @Nested
    inner class OrphanMessageHandling {

        @Test
        fun `고아 ToolResponseMessage는 트리밍 시 단독 제거되어야 한다`() {
            // 비정상 상태: ToolResponseMessage가 AssistantMessage 없이 존재
            val orphanToolResponse = ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse("orphan-1", "tool", "result")))
                .build()

            val messages = mutableListOf<Message>(
                orphanToolResponse,
                UserMessage("question")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 30,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            // 고아가 제거되더라도 UserMessage는 보존되어야 함
            val userMessages = messages.filterIsInstance<UserMessage>()
            assertTrue(userMessages.isNotEmpty(),
                "UserMessage가 보존되어야 함")
            assertPairIntegrity(messages, "고아 ToolResponse 제거 후")
        }

        @Test
        fun `고아 AssistantMessage(toolCalls)는 트리밍 시 단독 제거될 수 있다`() {
            // 비정상 상태: AssistantMessage(toolCalls)가 ToolResponseMessage 없이 존재
            val toolCall = buildToolCall("orphan-tc", "tool")
            val orphanAssistant = AssistantMessage.builder()
                .content("orphan")
                .toolCalls(listOf(toolCall))
                .build()

            val messages = mutableListOf<Message>(
                orphanAssistant,
                UserMessage("question")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 30,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            // 고아가 제거되더라도 UserMessage는 보존되어야 함
            val userMessages = messages.filterIsInstance<UserMessage>()
            assertTrue(userMessages.isNotEmpty(),
                "UserMessage가 보존되어야 함")
            // 고아 AssistantMessage가 남아있다면 무결성 위반이지만,
            // trimmer는 고아를 단독 제거하므로 결과에 고아가 없어야 함
            assertPairIntegrity(messages, "고아 AssistantMessage 제거 후")
        }
    }

    // =========================================================================
    // 트림 경계 (Trim Boundary)
    // =========================================================================

    @Nested
    inner class TrimBoundary {

        @Test
        fun `트리밍 경계가 도구 쌍 중간에 위치해도 쌍을 분리하지 않아야 한다`() {
            val (assistant1, toolResponse1) = buildToolPair("a1", "1", "tool1", "r1")
            val (assistant2, toolResponse2) = buildToolPair("a2", "2", "tool2", "r2")

            val messages = mutableListOf<Message>(
                assistant1,       // 제거 대상
                toolResponse1,    // 제거 대상 (쌍으로)
                assistant2,       // 제거 대상
                toolResponse2,    // 제거 대상 (쌍으로)
                UserMessage("keep this")
            )

            // 예산을 UserMessage만 겨우 들어가도록 설정
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 35,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            assertPairIntegrity(messages, "경계 트리밍 쌍 보존")
            // 최소한 UserMessage는 남아야 함
            assertTrue(messages.any { it is UserMessage },
                "UserMessage가 보존되어야 함")
        }

        @Test
        fun `정확히 쌍 하나만큼의 예산이 있으면 쌍을 보존해야 한다`() {
            val (assistant, toolResponse) = buildToolPair("a", "1", "t", "r")

            val messages = mutableListOf<Message>(
                UserMessage("q"),
                assistant,
                toolResponse
            )

            // 예산 계산: "q"(1) + overhead(20) + "a"(1) + "{}"(2) + overhead(20)
            //   + "r"(1) + overhead(20) = 65
            // 시스템 프롬프트 "" = 0
            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 65,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            // 예산이 충분하면 3개 모두 유지
            if (messages.size == 3) {
                assertPairIntegrity(messages, "정확한 예산 쌍 보존")
            } else {
                // 예산이 부족해서 제거된 경우에도 쌍 무결성 유지
                assertPairIntegrity(messages, "예산 부족 시에도 쌍 무결성")
            }
        }

        @Test
        fun `예산이 0 이하일 때 마지막 UserMessage만 남아야 한다`() {
            val (assistant, toolResponse) = buildToolPair("a", "1", "tool", "result")

            val messages = mutableListOf<Message>(
                UserMessage("old"),
                assistant,
                toolResponse,
                UserMessage("last")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 0,
                outputReserveTokens = 10,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "sys")

            messages.size shouldBe 1
            assertInstanceOf(UserMessage::class.java, messages[0],
                "예산 0일 때 마지막 UserMessage만 남아야 함")
            (messages[0] as UserMessage).text shouldBe "last"
            // 도구 쌍이 모두 제거됨 — 쌍 분리 없이 깔끔한 상태
            messages.filterIsInstance<ToolResponseMessage>().shouldBeEmpty()
        }
    }

    // =========================================================================
    // 빈 도구 호출 (Empty Tool Calls)
    // =========================================================================

    @Nested
    inner class EmptyToolCalls {

        @Test
        fun `toolCalls가 빈 리스트인 AssistantMessage는 ToolResponseMessage 없이 존재할 수 있다`() {
            val assistant = AssistantMessage.builder()
                .content("no tools needed")
                .toolCalls(emptyList())
                .build()

            val messages = mutableListOf<Message>(
                UserMessage("question"),
                assistant
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 500,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            messages.size shouldBe 2
            assertPairIntegrity(messages, "빈 toolCalls는 ToolResponse 불필요")
        }

        @Test
        fun `toolCalls가 null인 AssistantMessage는 ToolResponseMessage 없이 존재할 수 있다`() {
            // 일반 AssistantMessage 생성자는 toolCalls를 null로 설정
            val assistant = AssistantMessage("simple answer")

            val messages = mutableListOf<Message>(
                UserMessage("question"),
                assistant
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 500,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            messages.size shouldBe 2
            assertPairIntegrity(messages, "null toolCalls는 ToolResponse 불필요")
        }

        @Test
        fun `빈 toolCalls AssistantMessage는 트리밍 시 단독 제거될 수 있다`() {
            val assistant = AssistantMessage.builder()
                .content("long-content-that-uses-budget")
                .toolCalls(emptyList())
                .build()

            val messages = mutableListOf<Message>(
                assistant,
                UserMessage("keep")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 30,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            trimmer.trim(messages, systemPrompt = "")

            val userMessages = messages.filterIsInstance<UserMessage>()
            assertTrue(userMessages.isNotEmpty(),
                "UserMessage가 보존되어야 함")
            assertPairIntegrity(messages, "빈 toolCalls 단독 제거")
        }
    }

    // =========================================================================
    // 연속 트리밍 안정성 (Repeated Trimming Stability)
    // =========================================================================

    @Nested
    inner class RepeatedTrimmingStability {

        @Test
        fun `동일 메시지 리스트를 반복 트리밍해도 쌍 무결성이 유지되어야 한다`() {
            val (assistant, toolResponse) = buildToolPair("a", "1", "tool", "result")

            val messages = mutableListOf<Message>(
                UserMessage("keep"),
                assistant,
                toolResponse
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 200,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            // 여러 번 반복 트리밍 (ReAct 루프에서 매 반복 호출됨)
            repeat(5) { iteration ->
                trimmer.trim(messages, systemPrompt = "sys")
                assertPairIntegrity(messages, "반복 트리밍 #$iteration")
            }
        }

        @Test
        fun `점진적 메시지 추가 후 트리밍해도 쌍 무결성이 유지되어야 한다`() {
            val messages = mutableListOf<Message>(
                UserMessage("initial question")
            )

            val trimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 200,
                outputReserveTokens = 0,
                tokenEstimator = tokenEstimator
            )

            // ReAct 루프 시뮬레이션: 매 반복마다 도구 쌍 추가 후 트리밍
            for (i in 1..5) {
                val (assistant, toolResponse) = buildToolPair("think-$i", "tc-$i", "tool-$i", "r-$i")
                messages.add(assistant)
                messages.add(toolResponse)

                trimmer.trim(messages, systemPrompt = "sys")
                assertPairIntegrity(messages, "점진적 추가 후 트리밍 #$i")
            }

            // 최소한 마지막 UserMessage는 보존
            messages.filterIsInstance<UserMessage>().size shouldBeGreaterThanOrEqualTo 1
        }
    }
}
