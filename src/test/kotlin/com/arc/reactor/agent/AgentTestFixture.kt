package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.ConcurrencyProperties
import com.arc.reactor.agent.config.GuardProperties
import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Shared test fixture for agent tests.
 *
 * Eliminates duplicated mock setup across 11+ test files.
 */
class AgentTestFixture {

    val chatClient: ChatClient = mockk()
    val requestSpec: ChatClientRequestSpec = mockk(relaxed = true)
    val callResponseSpec: CallResponseSpec = mockk()
    val streamResponseSpec: StreamResponseSpec = mockk()

    init {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
        every { requestSpec.stream() } returns streamResponseSpec
    }

    /** Set up a simple successful call response. */
    fun mockCallResponse(content: String = "Response") {
        every { callResponseSpec.chatResponse() } returns simpleChatResponse(content)
    }

    /** Create a CallResponseSpec containing tool calls (triggers ReAct loop). */
    fun mockToolCallResponse(toolCalls: List<AssistantMessage.ToolCall>): CallResponseSpec {
        val assistantMsg = AssistantMessage.builder().content("").toolCalls(toolCalls).build()
        val generation = mockk<Generation>()
        every { generation.output } returns assistantMsg
        val chatResponse = mockk<ChatResponse>()
        every { chatResponse.results } returns listOf(generation)
        every { chatResponse.metadata } returns mockk(relaxed = true)

        val spec = mockk<CallResponseSpec>()
        every { spec.chatResponse() } returns chatResponse
        return spec
    }

    /** Create a CallResponseSpec for a final (no tool call) response. */
    fun mockFinalResponse(content: String): CallResponseSpec {
        val spec = mockk<CallResponseSpec>()
        every { spec.chatResponse() } returns simpleChatResponse(content)
        return spec
    }

    companion object {

        /** Build a ChatResponse with text content (no tool calls). */
        fun simpleChatResponse(content: String): ChatResponse {
            val assistantMsg = AssistantMessage(content)
            return ChatResponse(listOf(Generation(assistantMsg)))
        }

        fun defaultProperties(): AgentProperties = AgentProperties(
            llm = LlmProperties(),
            guard = GuardProperties(),
            rag = RagProperties(),
            concurrency = ConcurrencyProperties()
        )

        /** Create a simple tool callback that returns a fixed result. */
        fun toolCallback(
            name: String,
            description: String = "Tool $name",
            result: String = "result-$name"
        ): ToolCallback = object : ToolCallback {
            override val name = name
            override val description = description
            override suspend fun call(arguments: Map<String, Any?>) = result
        }

        /** Create a tool callback with a coroutine delay (NOT Thread.sleep). */
        fun delayingToolCallback(
            name: String,
            delayMs: Long,
            result: String = "result-$name"
        ): ToolCallback = object : ToolCallback {
            override val name = name
            override val description = "Tool $name"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                if (delayMs > 0) delay(delayMs)
                return result
            }
        }

        /** Create a ChatResponse chunk with text content (for streaming tests). */
        fun textChunk(text: String): ChatResponse {
            return ChatResponse(listOf(Generation(AssistantMessage(text))))
        }

        /** Create a ChatResponse chunk with tool calls (for streaming tests). */
        fun toolCallChunk(
            toolCalls: List<AssistantMessage.ToolCall>,
            text: String = ""
        ): ChatResponse {
            val msg = AssistantMessage.builder()
                .content(text)
                .toolCalls(toolCalls)
                .build()
            return ChatResponse(listOf(Generation(msg)))
        }
    }
}

/**
 * Tracking tool callback that records call count and captured arguments.
 * Useful for verifying tool invocation behavior.
 */
class TrackingTool(
    override val name: String,
    private val result: String = "tool result",
    override val description: String = "Test tool: $name"
) : ToolCallback {
    var callCount = 0
        private set
    val capturedArgs = mutableListOf<Map<String, Any?>>()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        callCount++
        capturedArgs.add(arguments)
        return result
    }
}
