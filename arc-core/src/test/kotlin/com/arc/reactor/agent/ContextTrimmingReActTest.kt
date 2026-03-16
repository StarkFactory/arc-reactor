package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.LlmProperties
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
import org.springframework.ai.chat.messages.UserMessage

/**
 * 다중 라운드 ReAct 루프 중 컨텍스트 트리밍에 대한 P0 테스트.
 *
 * 도구 호출 반복 중 컨텍스트 윈도우가 가득 찰 때,
 * 트리밍 로직이:
 * - ToolResponseMessage를 고아로 만들지 않고 (항상 선행 AssistantMessage와 쌍으로 제거)
 * - 가장 최근 UserMessage (현재 프롬프트)를 항상 보존하고
 * - 도구 결과가 누적됨에 따라 증가하는 메시지 목록을 올바르게 처리하는지 검증합니다
 */
class ContextTrimmingReActTest {

    private lateinit var fixture: AgentTestFixture
    private val charEstimator = TokenEstimator { text -> text.length.coerceAtLeast(1) }

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class TrimmingDuringReActLoop {

        @Test
        fun `다중 라운드 ReAct 중 도구 호출 쌍을 보존하면서 오래된 기록을 제거해야 한다`() = runBlocking {
            // 타이트한 컨텍스트 예산이 ReAct 반복 중 트리밍을 강제합니다
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 300,
                    maxOutputTokens = 50,
                    maxConversationTurns = 100
                )
            )

            // 시스템 프롬프트 "S" = 1 글자/토큰 → 예산 = 300 - 1 - 50 = 249
            val toolCall1 = AssistantMessage.ToolCall("call-1", "function", "search", """{"q":"round 1"}""")
            val toolCall2 = AssistantMessage.ToolCall("call-2", "function", "search", """{"q":"round 2"}""")
            val toolCall3 = AssistantMessage.ToolCall("call-3", "function", "search", """{"q":"round 3"}""")

            // LLM에 전송된 모든 메시지 목록을 추적합니다
            val allMessageCaptures = mutableListOf<List<Message>>()
            every { fixture.requestSpec.messages(any<List<Message>>()) } answers {
                val msgs = firstArg<List<Message>>()
                allMessageCaptures.add(msgs.toList())  // 스냅샷 복사
                fixture.requestSpec
            }

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall1)),
                fixture.mockToolCallResponse(listOf(toolCall2)),
                fixture.mockToolCallResponse(listOf(toolCall3)),
                fixture.mockFinalResponse("Final answer after 3 rounds")
            )

            // 큰 출력을 반환하는 도구 (컨텍스트 예산을 소모)
            val tool = AgentTestFixture.toolCallback("search", result = "X".repeat(80))

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            // 대화 기록을 미리 채웁니다
            memoryStore.addMessage("session-trim", "user", "A".repeat(60))
            memoryStore.addMessage("session-trim", "assistant", "B".repeat(60))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "S",
                    userPrompt = "Find information",
                    metadata = mapOf("sessionId" to "session-trim"),
                    maxToolCalls = 3
                )
            )

            result.assertSuccess()

            // 모든 반복에서 메시지 무결성을 확인합니다
            for ((iteration, messages) in allMessageCaptures.withIndex()) {
                for (i in messages.indices) {
                    if (messages[i] is ToolResponseMessage) {
                        assertTrue(i > 0,
                            "Iteration $iteration: ToolResponseMessage at index $i should not be first")
                        val prev = messages[i - 1]
                        assertTrue(
                            prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                            "Iteration $iteration: ToolResponseMessage at index $i must follow " +
                                "AssistantMessage with tool calls, but found: ${prev::class.simpleName}"
                        )
                    }
                }

                // 마지막 메시지는 항상 UserMessage (현재 프롬프트)여야 합니다
                // 또는 도구 호출 루프에 의해 추가된 메시지 (AssistantMessage/ToolResponseMessage)
                // 하지만 원본 UserMessage는 어딘가에 여전히 존재해야 합니다
                val hasUserMessage = messages.any { it is UserMessage }
                assertTrue(hasUserMessage,
                    "Iteration $iteration: UserMessage should always be present in messages")
            }
        }
    }

    @Nested
    inner class ExtremeBudgetPressure {

        @Test
        fun `예산이 매우 타이트할 때 현재 사용자 메시지만 유지해야 한다`() = runBlocking {
            // 사용자 메시지만 들어갈 정도로 타이트한 예산
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 50,
                    maxOutputTokens = 20,
                    maxConversationTurns = 100
                )
            )

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Brief response")

            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            memoryStore.addMessage("session-tight", "user", "A".repeat(100))
            memoryStore.addMessage("session-tight", "assistant", "B".repeat(100))
            memoryStore.addMessage("session-tight", "user", "C".repeat(100))
            memoryStore.addMessage("session-tight", "assistant", "D".repeat(100))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "System prompt that uses tokens",
                    userPrompt = "Current question",
                    metadata = mapOf("sessionId" to "session-tight")
                )
            )

            result.assertSuccess()

            val captured = messagesSlot.captured
            // 현재 사용자 프롬프트는 항상 유지되어야 합니다
            assertTrue(captured.any { it is UserMessage && it.text == "Current question" },
                "Current user prompt must always be preserved, got: ${captured.map { "${it::class.simpleName}(${it.text?.take(20)})" }}")
        }

        @Test
        fun `예산이 0일 때 크래시 없이 우아하게 처리해야 한다`() = runBlocking {
            // maxOutputTokens >= maxContextWindowTokens → 예산이 음수가 됩니다
            // (init 블록이 maxContextWindowTokens > maxOutputTokens를 요구하므로, 통과하는 값을 사용합니다)
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 100,
                    maxOutputTokens = 99
                )
            )

            val messagesSlot = slot<List<Message>>()
            every { fixture.requestSpec.messages(capture(messagesSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("OK")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                tokenEstimator = TokenEstimator { text -> text.length * 10 }  // 부풀린 추정기
            )

            // 시스템 프롬프트 후 예산이 사실상 0이더라도 예외를 던지지 않아야 합니다
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "A".repeat(50),  // 부풀린 추정기로 500 "토큰"
                    userPrompt = "Hello"
                )
            )

            result.assertSuccess()
            assertTrue(messagesSlot.captured.isNotEmpty(),
                "Should still have at least the user message")
        }
    }

    @Nested
    inner class MessagePairValidation {

        @Test
        fun `기록 트리밍 후 고아 ToolResponseMessage를 남기지 않아야 한다`() = runBlocking {
            // 도구 호출 기록이 미리 로드된 적절한 예산
            val properties = AgentProperties(
                llm = LlmProperties(
                    maxContextWindowTokens = 200,
                    maxOutputTokens = 30,
                    maxConversationTurns = 100
                )
            )

            val allMessageCaptures = mutableListOf<List<Message>>()
            every { fixture.requestSpec.messages(any<List<Message>>()) } answers {
                allMessageCaptures.add(firstArg<List<Message>>().toList())
                fixture.requestSpec
            }

            // 중간 크기 출력을 생성하는 도구 호출
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "lookup", """{"id":"123"}""")

            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockToolCallResponse(listOf(toolCall)),
                fixture.mockFinalResponse("Result after two lookups")
            )

            val tool = AgentTestFixture.toolCallback("lookup", result = "Y".repeat(60))

            // 혼합된 기록으로 미리 채웁니다
            val memoryStore = com.arc.reactor.memory.InMemoryMemoryStore()
            memoryStore.addMessage("session-pairs", "user", "Old question 1".repeat(5))
            memoryStore.addMessage("session-pairs", "assistant", "Old answer 1".repeat(5))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(tool),
                memoryStore = memoryStore,
                tokenEstimator = charEstimator
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "S",
                    userPrompt = "Look up item 123 twice",
                    metadata = mapOf("sessionId" to "session-pairs"),
                    maxToolCalls = 5
                )
            )

            result.assertSuccess()

            // 모든 메시지 스냅샷에 대해 쌍 무결성을 검증합니다
            for ((iteration, messages) in allMessageCaptures.withIndex()) {
                var i = 0
                while (i < messages.size) {
                    val msg = messages[i]
                    if (msg is ToolResponseMessage && i == 0) {
                        fail<Unit>("Iteration $iteration: ToolResponseMessage should not be first message")
                    }
                    if (msg is ToolResponseMessage) {
                        val prev = messages[i - 1]
                        assertTrue(
                            prev is AssistantMessage && !prev.toolCalls.isNullOrEmpty(),
                            "Iteration $iteration index $i: Orphaned ToolResponseMessage found. " +
                                "Preceding message is ${prev::class.simpleName}"
                        )
                    }
                    i++
                }
            }
        }
    }
}
