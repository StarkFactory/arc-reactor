package com.arc.reactor.hook.impl

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant

class WebhookNotificationHookTest {

    private lateinit var webClient: WebClient
    private lateinit var requestBodyUriSpec: WebClient.RequestBodyUriSpec
    private lateinit var requestBodySpec: WebClient.RequestBodySpec
    private lateinit var requestHeadersSpec: WebClient.RequestHeadersSpec<*>
    private lateinit var responseSpec: WebClient.ResponseSpec

    @BeforeEach
    fun setup() {
        webClient = mockk()
        requestBodyUriSpec = mockk()
        requestBodySpec = mockk()
        requestHeadersSpec = mockk()
        responseSpec = mockk()

        every { webClient.post() } returns requestBodyUriSpec
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec
        every { responseSpec.toBodilessEntity() } returns Mono.empty()
    }

    private fun createHook(
        url: String = "https://example.com/webhook",
        timeoutMs: Long = 5000,
        includeConversation: Boolean = false
    ): WebhookNotificationHook {
        val properties = WebhookProperties(
            enabled = true,
            url = url,
            timeoutMs = timeoutMs,
            includeConversation = includeConversation
        )
        return WebhookNotificationHook(
            webhookProperties = properties,
            webClient = webClient
        )
    }

    private fun createContext(
        runId: String = "run-1",
        userId: String = "user-1",
        userPrompt: String = "Hello"
    ): HookContext = HookContext(
        runId = runId,
        userId = userId,
        userPrompt = userPrompt,
        startedAt = Instant.now()
    )

    private fun createSuccessResponse(
        content: String = "Agent response content",
        toolsUsed: List<String> = listOf("calculator"),
        durationMs: Long = 500
    ): AgentResponse = AgentResponse(
        success = true,
        response = content,
        toolsUsed = toolsUsed,
        totalDurationMs = durationMs
    )

    private fun createFailureResponse(
        errorMessage: String = "Something went wrong",
        durationMs: Long = 100
    ): AgentResponse = AgentResponse(
        success = false,
        errorMessage = errorMessage,
        totalDurationMs = durationMs
    )

    @Nested
    inner class HookProperties {

        @Test
        fun `should have order 200 for late hook execution`() {
            val hook = createHook()
            assertEquals(200, hook.order) { "Webhook hook should be in late-hooks range (200+)" }
        }

        @Test
        fun `should be fail-open by default`() {
            val hook = createHook()
            assertFalse(hook.failOnError) { "Webhook should never block the agent on failure" }
        }
    }

    @Nested
    inner class SuccessNotification {

        @Test
        fun `should POST to webhook URL on agent success`() = runTest {
            val hook = createHook(url = "https://hooks.example.com/notify")

            hook.afterAgentComplete(createContext(), createSuccessResponse())

            verify(exactly = 1) { webClient.post() }
            verify(exactly = 1) { requestBodyUriSpec.uri("https://hooks.example.com/notify") }
        }

        @Test
        fun `should include required fields in payload`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook()

            hook.afterAgentComplete(
                createContext(runId = "run-42", userId = "user-7"),
                createSuccessResponse(toolsUsed = listOf("search", "calculator"), durationMs = 1234)
            )

            val payload = payloadSlot.captured
            assertEquals("AGENT_COMPLETE", payload["event"]) { "Event type should be AGENT_COMPLETE" }
            assertEquals("run-42", payload["runId"]) { "runId should match" }
            assertEquals("user-7", payload["userId"]) { "userId should match" }
            assertEquals(true, payload["success"]) { "success should be true" }
            assertEquals(listOf("search", "calculator"), payload["toolsUsed"]) { "toolsUsed should match" }
            assertEquals(1234L, payload["durationMs"]) { "durationMs should match" }
            assertNotNull(payload["timestamp"]) { "timestamp should be present" }
        }

        @Test
        fun `should include content preview on success`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook()
            hook.afterAgentComplete(createContext(), createSuccessResponse(content = "Short answer"))

            val payload = payloadSlot.captured
            assertEquals("Short answer", payload["contentPreview"]) { "Should include content preview" }
            assertNull(payload["errorMessage"]) { "errorMessage should not be present on success" }
        }

        @Test
        fun `should truncate content preview to 200 chars`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook()
            val longContent = "A".repeat(500)
            hook.afterAgentComplete(createContext(), createSuccessResponse(content = longContent))

            val preview = payloadSlot.captured["contentPreview"] as String
            assertEquals(200, preview.length) { "Content preview should be truncated to 200 chars" }
        }
    }

    @Nested
    inner class FailureNotification {

        @Test
        fun `should include error message on failure`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook()
            hook.afterAgentComplete(createContext(), createFailureResponse(errorMessage = "Rate limited"))

            val payload = payloadSlot.captured
            assertEquals(false, payload["success"]) { "success should be false" }
            assertEquals("Rate limited", payload["errorMessage"]) { "errorMessage should match" }
            assertNull(payload["contentPreview"]) { "contentPreview should not be present on failure" }
        }
    }

    @Nested
    inner class ConversationInclusion {

        @Test
        fun `should not include conversation by default`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook(includeConversation = false)

            hook.afterAgentComplete(
                createContext(userPrompt = "secret question"),
                createSuccessResponse(content = "full response text")
            )

            val payload = payloadSlot.captured
            assertNull(payload["userPrompt"]) { "userPrompt should not be included by default" }
            assertNull(payload["fullResponse"]) { "fullResponse should not be included by default" }
        }

        @Test
        fun `should include conversation when enabled`() = runTest {
            val payloadSlot = slot<Map<String, Any?>>()
            every { requestBodySpec.bodyValue(capture(payloadSlot)) } returns requestHeadersSpec

            val hook = createHook(includeConversation = true)

            hook.afterAgentComplete(
                createContext(userPrompt = "What is 2+2?"),
                createSuccessResponse(content = "The answer is 4")
            )

            val payload = payloadSlot.captured
            assertEquals("What is 2+2?", payload["userPrompt"]) { "userPrompt should be included" }
            assertEquals("The answer is 4", payload["fullResponse"]) { "fullResponse should be included" }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should not throw when webhook POST fails`() = runTest {
            every { responseSpec.toBodilessEntity() } returns Mono.error(RuntimeException("Connection refused"))

            val hook = createHook()

            // Should not throw — fail-open
            hook.afterAgentComplete(createContext(), createSuccessResponse())
        }

        @Test
        fun `should skip when webhook URL is blank`() = runTest {
            val hook = createHook(url = "")

            // Should not throw, just skip
            hook.afterAgentComplete(createContext(), createSuccessResponse())

            verify(exactly = 0) { webClient.post() }
        }

        @Test
        fun `should rethrow cancellation exception`() {
            every { responseSpec.toBodilessEntity() } returns Mono.error(CancellationException("cancelled"))
            val hook = createHook()

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    hook.afterAgentComplete(createContext(), createSuccessResponse())
                }
            }
        }

        @Test
        fun `should not throw when uri call throws`() = runTest {
            every { requestBodyUriSpec.uri(any<String>()) } throws RuntimeException("Invalid URI")

            val hook = createHook(url = "not-a-valid-url")

            // Should not throw — caught by outer try-catch
            hook.afterAgentComplete(createContext(), createSuccessResponse())
        }
    }
}
