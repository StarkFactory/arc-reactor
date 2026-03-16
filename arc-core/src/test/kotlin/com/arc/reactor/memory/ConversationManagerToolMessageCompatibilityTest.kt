package com.arc.reactor.memory

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.ToolResponseMessage

class ConversationManagerToolMessageCompatibilityTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `toSpringAiMessage은(는) wrap plain tool text as json object해야 한다`() {
        val springMessage = DefaultConversationManager.toSpringAiMessage(
            Message(role = MessageRole.TOOL, content = "Error: backend down")
        ) as ToolResponseMessage

        val payload = objectMapper.readValue(
            springMessage.responses.first().responseData(),
            Map::class.java
        ) as Map<*, *>

        assertEquals("Error: backend down", payload["result"])
    }

    @Test
    fun `toSpringAiMessage은(는) preserve valid json tool payload해야 한다`() {
        val springMessage = DefaultConversationManager.toSpringAiMessage(
            Message(role = MessageRole.TOOL, content = """{"status":"ok"}""")
        ) as ToolResponseMessage

        val payload = objectMapper.readValue(
            springMessage.responses.first().responseData(),
            Map::class.java
        ) as Map<*, *>

        assertEquals("ok", payload["status"])
    }
}
