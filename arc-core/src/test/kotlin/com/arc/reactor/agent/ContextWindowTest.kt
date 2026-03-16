package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.memory.TokenEstimator
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage

class ContextWindowTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        fixture.mockCallResponse()
    }

    @Nested
    inner class BudgetCalculation {

        @Test
        fun `컨텍스트 예산 내에서는 메시지를 제거하지 않아야 한다`() = runBlocking {
            // 예산: 1000 - 시스템토큰 - 100 = 충분한 여유
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 1000,
                    maxOutputTokens = 100
                )
            )

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                tokenEstimator = TokenEstimator { it.length / 4 }  // 간단한 추정기
            )

            executor.execute(
                AgentCommand(
                    systemPrompt = "Short system prompt",
                    userPrompt = "Hello!"
                )
            )

            // 사용자 메시지만 존재해야 합니다 (로드할 기록 없음)
            assertEquals(1, messagesSlot.captured.size)
        }

        @Test
        fun `컨텍스트 예산에서 maxOutputTokens를 예약해야 한다`() = runBlocking {
            // maxContextWindowTokens=100, maxOutputTokens=80
            // 시스템 "Hi" ~= 1 토큰
            // 예산 = 100 - 1 - 80 = 19 토큰
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 100,
                    maxOutputTokens = 80,
                    maxConversationTurns = 100
                )
            )

            val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            memoryStore.addMessage("session-2", "user", "AAAAAAAAAA")  // 10 토큰
            memoryStore.addMessage("session-2", "assistant", "BBBBBBBBBB")  // 10 토큰

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore,
                tokenEstimator = simpleEstimator
            )

            executor.execute(
                AgentCommand(
                    systemPrompt = "Hi",
                    userPrompt = "CCCCC",  // 5 토큰
                    metadata = mapOf("sessionId" to "session-2")
                )
            )

            // 예산은 19입니다. 기록은 10+10=20 토큰 + 사용자 프롬프트 5 = 25, 19를 초과합니다
            // 맞추기 위해 가장 오래된 것을 제거해야 합니다
            val capturedMessages = messagesSlot.captured
            val totalTokens = capturedMessages.sumOf { simpleEstimator.estimate(it.text ?: "") }
            assertTrue(totalTokens <= 19, "Total tokens ($totalTokens) should fit within budget (19)")
            // 현재 사용자 프롬프트는 보존되어야 합니다
            assertTrue(capturedMessages.any { it.text == "CCCCC" }) { "Current user prompt 'CCCCC' should be preserved in: ${capturedMessages.map { it.text }}" }
        }
    }

    @Nested
    inner class MessageTrimming {

        @Test
        fun `컨텍스트 예산을 초과하면 가장 오래된 메시지를 제거해야 한다`() = runBlocking {
            // 매우 타이트한 예산: 총 50 토큰, 출력 예약 10
            // 시스템 프롬프트 "System" = 6/4 = 1 토큰
            // 예산 = 50 - 1 - 10 = 39 토큰
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 50,
                    maxOutputTokens = 10,
                    maxConversationTurns = 100
                )
            )

            // 예측 가능성을 위해 간단한 1글자=1토큰 추정기를 사용합니다
            val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            // 예산을 초과하는 오래된 메시지를 많이 추가합니다
            repeat(10) { i ->
                memoryStore.addMessage("session-1", "user", "Old message number $i which is quite long")
                memoryStore.addMessage("session-1", "assistant", "Old response number $i which is also long")
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore,
                tokenEstimator = simpleEstimator
            )

            executor.execute(
                AgentCommand(
                    systemPrompt = "System",
                    userPrompt = "New question",
                    metadata = mapOf("sessionId" to "session-1")
                )
            )

            // 메시지는 39 토큰 이내로 잘려야 합니다
            val capturedMessages = messagesSlot.captured
            val totalTokens = capturedMessages.sumOf { simpleEstimator.estimate(it.text ?: "") }
            assertTrue(totalTokens <= 39, "Total tokens ($totalTokens) should fit within budget (39)")
            // 마지막 메시지 (현재 사용자 프롬프트)는 항상 존재해야 합니다
            assertEquals("New question", capturedMessages.last().text, "Current user prompt must always be preserved")
        }

        @Test
        fun `예산을 초과하더라도 가장 최근 사용자 메시지를 항상 보존해야 한다`() = runBlocking {
            // 매우 타이트한 예산
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 10,
                    maxOutputTokens = 5
                )
            )

            val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                tokenEstimator = simpleEstimator
            )

            // 사용자 프롬프트가 예산을 초과하더라도 유지되어야 합니다
            executor.execute(
                AgentCommand(
                    systemPrompt = "System prompt that takes tokens",
                    userPrompt = "This is a very long user prompt that definitely exceeds the tiny budget"
                )
            )

            val capturedMessages = messagesSlot.captured
            assertEquals(1, capturedMessages.size, "Should keep at least the current user message")
            assertTrue(capturedMessages[0].text!!.contains("very long user prompt")) { "Current user message should be preserved but got: ${capturedMessages[0].text}" }
        }
    }

    @Nested
    inner class MessageIntegrity {

        @Test
        fun `트리밍 시 도구 호출과 도구 응답 쌍을 보존해야 한다`() = runBlocking {
            // 트리밍을 강제하는 타이트한 예산
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 200,
                    maxOutputTokens = 20,
                    maxConversationTurns = 100
                )
            )

            val simpleEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

            // 도구 호출 쌍이 있는 대화 기록을 수동으로 구성합니다
            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            // 턴 1: 사용자 + 어시스턴트 (단순)
            memoryStore.addMessage("session-pair", "user", "A".repeat(50))
            memoryStore.addMessage("session-pair", "assistant", "B".repeat(50))
            // 턴 2: 사용자 + 어시스턴트 (단순)
            memoryStore.addMessage("session-pair", "user", "C".repeat(50))
            memoryStore.addMessage("session-pair", "assistant", "D".repeat(50))

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore,
                tokenEstimator = simpleEstimator
            )

            // 시스템 프롬프트 "S" = 1 토큰, 예산 = 200 - 1 - 20 = 179
            // 기록: 50 + 50 + 50 + 50 = 200 토큰 + 새 사용자 = 210, 179를 초과합니다
            executor.execute(
                AgentCommand(
                    systemPrompt = "S",
                    userPrompt = "E".repeat(10),
                    metadata = mapOf("sessionId" to "session-pair")
                )
            )

            val captured = messagesSlot.captured
            val totalTokens = captured.sumOf { simpleEstimator.estimate(it.text ?: "") }
            assertTrue(totalTokens <= 179, "Should fit in budget: $totalTokens")

            // 도구 호출이 있는 선행 AssistantMessage 없이 고아 ToolResponseMessage가 존재하지 않는지 확인합니다
            for (i in captured.indices) {
                if (captured[i] is ToolResponseMessage) {
                    assertTrue(i > 0, "ToolResponseMessage should not be first message")
                    val prev = captured[i - 1]
                    assertTrue(
                        prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                        "ToolResponseMessage at index $i must be preceded by AssistantMessage with tool calls"
                    )
                }
            }
        }
    }
}
