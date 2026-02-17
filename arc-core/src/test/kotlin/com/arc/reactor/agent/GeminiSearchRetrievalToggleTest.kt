package com.arc.reactor.agent

import com.arc.reactor.agent.config.LlmProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions

class GeminiSearchRetrievalToggleTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Test
    fun `google search retrieval defaults to false`() = runTest {
        val capturedOptions = mutableListOf<ChatOptions>()
        every { fixture.requestSpec.options(capture(capturedOptions)) } returns fixture.requestSpec
        fixture.mockCallResponse("ok")

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = AgentTestFixture.defaultProperties()
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello", model = "gemini")
        )

        result.assertSuccess("Execution should succeed with default Gemini options")
        val options = capturedOptions.lastOrNull()
        assertNotNull(options) { "ChatOptions should be captured" }
        assertFalse(readGoogleSearchRetrieval(options!!)) {
            "googleSearchRetrieval should be false by default"
        }
    }

    @Test
    fun `google search retrieval can be enabled by config`() = runTest {
        val capturedOptions = mutableListOf<ChatOptions>()
        every { fixture.requestSpec.options(capture(capturedOptions)) } returns fixture.requestSpec
        fixture.mockCallResponse("ok")

        val properties = AgentTestFixture.defaultProperties().copy(
            llm = LlmProperties(
                defaultProvider = "gemini",
                temperature = 0.3,
                maxOutputTokens = 4096,
                googleSearchRetrievalEnabled = true,
                maxConversationTurns = 10,
                maxContextWindowTokens = 128000
            )
        )

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello", model = "gemini")
        )

        result.assertSuccess("Execution should succeed with configured Gemini options")
        val options = capturedOptions.lastOrNull()
        assertNotNull(options) { "ChatOptions should be captured" }
        assertTrue(readGoogleSearchRetrieval(options!!)) {
            "googleSearchRetrieval should be true when enabled by config"
        }
    }

    private fun readGoogleSearchRetrieval(options: ChatOptions): Boolean {
        val getter = options::class.java.methods.firstOrNull {
            it.name == "getGoogleSearchRetrieval" || it.name == "isGoogleSearchRetrieval"
        }
        if (getter != null) {
            return getter.invoke(options) as Boolean
        }

        val field = options::class.java.declaredFields.firstOrNull { it.name == "googleSearchRetrieval" }
        if (field != null) {
            field.isAccessible = true
            return field.get(options) as Boolean
        }

        error("Could not read googleSearchRetrieval from ${options::class.java.name}")
    }
}
