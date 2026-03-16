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
 * 에이전트 테스트를 위한 공유 테스트 픽스처.
 *
 * 11개 이상의 테스트 파일에서 중복되는 mock 설정을 제거합니다.
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
        every { requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
        every { requestSpec.stream() } returns streamResponseSpec
    }

    /** 간단한 성공 호출 응답을 설정합니다. */
    fun mockCallResponse(content: String = "Response") {
        every { callResponseSpec.chatResponse() } returns simpleChatResponse(content)
    }

    /** 도구 호출을 포함하는 CallResponseSpec을 생성합니다 (ReAct 루프를 트리거). */
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

    /** 최종 응답(도구 호출 없음)을 위한 CallResponseSpec을 생성합니다. */
    fun mockFinalResponse(content: String): CallResponseSpec {
        val spec = mockk<CallResponseSpec>()
        every { spec.chatResponse() } returns simpleChatResponse(content)
        return spec
    }

    companion object {

        /** 텍스트 콘텐츠로 ChatResponse를 빌드합니다 (도구 호출 없음). */
        fun simpleChatResponse(content: String): ChatResponse {
            val assistantMsg = AssistantMessage(content)
            return ChatResponse(listOf(Generation(assistantMsg)))
        }

        fun defaultProperties(): AgentProperties = AgentProperties(
            llm = LlmProperties(),
            guard = GuardProperties(),
            rag = RagProperties(),
            // runTest를 사용하는 단위 테스트에서 기본적으로 요청 타임아웃을 비활성화합니다.
            // 가상 시간이 withTimeout(30s)을 빨리 감기하여
            // 거짓 양성 TIMEOUT 실패를 발생시킬 수 있습니다.
            concurrency = ConcurrencyProperties(requestTimeoutMs = 0)
        )

        /** 고정된 결과를 반환하는 간단한 도구 콜백을 생성합니다. */
        fun toolCallback(
            name: String,
            description: String = "Tool $name",
            result: String = "result-$name"
        ): ToolCallback = object : ToolCallback {
            override val name = name
            override val description = description
            override suspend fun call(arguments: Map<String, Any?>) = result
        }

        /** 코루틴 지연이 있는 도구 콜백을 생성합니다 (Thread.sleep이 아님). */
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

        /** 텍스트 콘텐츠로 ChatResponse 청크를 생성합니다 (스트리밍 테스트용). */
        fun textChunk(text: String): ChatResponse {
            return ChatResponse(listOf(Generation(AssistantMessage(text))))
        }

        /** 도구 호출이 포함된 ChatResponse 청크를 생성합니다 (스트리밍 테스트용). */
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
 * 호출 횟수와 캡처된 인수를 기록하는 추적 도구 콜백.
 * 도구 호출 동작을 검증하는 데 유용합니다.
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
