package com.arc.reactor.guard.impl

import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec

class LlmClassificationStageTest {

    private fun mockChatClient(responseContent: String): ChatClient {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        val responseSpec = mockk<CallResponseSpec>()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.content() } returns responseContent
        return chatClient
    }

    private fun mockChatClientThrows(exception: Exception): ChatClient {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } throws exception
        return chatClient
    }

    @Nested
    inner class ClassificationResults {

        @Test
        fun `safe classification returns Allowed`() = runBlocking {
            val chatClient = mockChatClient("""{"label":"safe","confidence":0.95}""")
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "Hello, how are you?"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Safe classification should return Allowed")
        }

        @Test
        fun `malicious classification above threshold rejects`() = runBlocking {
            val chatClient = mockChatClient("""{"label":"malicious","confidence":0.95}""")
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "harmful input"))
            val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result,
                "Malicious with high confidence should be rejected")
            assertEquals(RejectionCategory.OFF_TOPIC, rejected.category)
            assertTrue(rejected.reason.contains("malicious"),
                "Reason should contain label, got: ${rejected.reason}")
        }

        @Test
        fun `malicious classification below threshold allows`() = runBlocking {
            val chatClient = mockChatClient("""{"label":"malicious","confidence":0.5}""")
            val stage = LlmClassificationStage(chatClient, confidenceThreshold = 0.7)

            val result = stage.check(GuardCommand(userId = "user-1", text = "ambiguous input"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Below-threshold confidence should return Allowed")
        }

        @Test
        fun `harmful classification above threshold rejects`() = runBlocking {
            val chatClient = mockChatClient("""{"label":"harmful","confidence":0.85}""")
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "violent content"))
            assertInstanceOf(GuardResult.Rejected::class.java, result,
                "Harmful with high confidence should be rejected")
        }

        @Test
        fun `unparseable LLM response defaults to safe`() = runBlocking {
            val chatClient = mockChatClient("I cannot classify this content properly")
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "some text"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Unparseable response should default to safe (label defaults to 'safe')")
        }
    }

    @Nested
    inner class FailOpen {

        @Test
        fun `LLM exception returns Allowed (fail-open)`() = runBlocking {
            val chatClient = mockChatClientThrows(RuntimeException("LLM API down"))
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "any input"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "LLM failure should fail-open and return Allowed")
        }

        @Test
        fun `null content returns Allowed`() = runBlocking {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
            val responseSpec = mockk<CallResponseSpec>()
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } returns responseSpec
            every { responseSpec.content() } returns null
            val stage = LlmClassificationStage(chatClient)

            val result = stage.check(GuardCommand(userId = "user-1", text = "test"))
            assertEquals(GuardResult.Allowed.DEFAULT, result,
                "Null content should be treated as safe")
        }
    }

    @Nested
    inner class InputTruncation {

        @Test
        fun `input is truncated to 500 chars`() = runBlocking {
            val longInput = "a".repeat(1000)
            var capturedUser = ""
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>(relaxed = true)
            val responseSpec = mockk<CallResponseSpec>()
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } answers {
                capturedUser = firstArg()
                requestSpec
            }
            every { requestSpec.call() } returns responseSpec
            every { responseSpec.content() } returns """{"label":"safe","confidence":0.9}"""

            val stage = LlmClassificationStage(chatClient)
            stage.check(GuardCommand(userId = "user-1", text = longInput))

            assertEquals(500, capturedUser.length,
                "Input should be truncated to 500 chars")
        }
    }

    @Nested
    inner class StageProperties {

        @Test
        fun `inherits order 4 from ClassificationStage`() {
            val chatClient = mockChatClient("""{"label":"safe","confidence":1.0}""")
            val stage = LlmClassificationStage(chatClient)
            assertEquals(4, stage.order,
                "LlmClassificationStage should inherit order=4 from ClassificationStage")
        }

        @Test
        fun `stage name is LlmClassification`() {
            val chatClient = mockChatClient("""{"label":"safe","confidence":1.0}""")
            val stage = LlmClassificationStage(chatClient)
            assertEquals("LlmClassification", stage.stageName)
        }
    }
}
