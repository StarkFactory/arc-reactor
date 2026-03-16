package com.arc.reactor.agent.impl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * PromptRequestSpecBuilder에 대한 테스트.
 *
 * 프롬프트 요청 사양 빌드 로직을 검증합니다.
 */
class PromptRequestSpecBuilderTest {

    @Test
    fun `request spec without system prompt and tools를 빌드한다`() {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg()) } returns requestSpec
        every { requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) } returns requestSpec

        val builder = PromptRequestSpecBuilder()
        val messages = emptyList<Message>()
        val options = ChatOptions.builder().temperature(0.2).build()

        val built = builder.create(
            activeChatClient = chatClient,
            systemPrompt = "",
            messages = messages,
            chatOptions = options,
            tools = emptyList()
        )

        assertSame(requestSpec, built)
        verify(exactly = 0) { requestSpec.system(any<String>()) }
        verify(exactly = 1) { requestSpec.messages(messages) }
        verify(exactly = 1) { requestSpec.options(options) }
        verify(exactly = 0) { requestSpec.tools(*anyVararg()) }
        verify(exactly = 0) { requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
    }

    @Test
    fun `annotated tools and callback tools to separate spring ai methods를 라우팅한다`() {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.tools(*anyVararg()) } returns requestSpec
        every { requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) } returns requestSpec

        val callback = mockk<org.springframework.ai.tool.ToolCallback>()
        val annotatedTool = Any()
        val builder = PromptRequestSpecBuilder()

        val built = builder.create(
            activeChatClient = chatClient,
            systemPrompt = "system",
            messages = emptyList(),
            chatOptions = ChatOptions.builder().temperature(0.5).build(),
            tools = listOf(annotatedTool, callback)
        )

        assertSame(requestSpec, built)
        verify(exactly = 1) { requestSpec.system("system") }
        verify(exactly = 1) { requestSpec.tools(*arrayOf(annotatedTool)) }
        verify(exactly = 1) {
            requestSpec.toolCallbacks(match<List<org.springframework.ai.tool.ToolCallback>> {
                it.size == 1 && it.first() === callback
            })
        }
    }
}
