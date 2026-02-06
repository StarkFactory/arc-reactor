package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

class RetryTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()
        properties = AgentProperties(
            retry = RetryProperties(
                maxAttempts = 3,
                initialDelayMs = 10,  // short delays for test speed
                multiplier = 2.0,
                maxDelayMs = 100
            )
        )

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
    }

    @Test
    fun `should succeed after transient failure with retry`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            val count = callCount.incrementAndGet()
            if (count < 3) {
                throw RuntimeException("Rate limit exceeded: too many requests")
            }
            responseSpec
        }
        every { responseSpec.content() } returns "Success after retry"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertTrue(result.success, "Should succeed after retries")
        assertEquals("Success after retry", result.content)
        assertEquals(3, callCount.get(), "Should have called LLM 3 times (2 failures + 1 success)")
    }

    @Test
    fun `should fail when all retry attempts exhausted`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            callCount.incrementAndGet()
            throw RuntimeException("Service unavailable")
        }

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertFalse(result.success, "Should fail after exhausting retries")
        assertNotNull(result.errorMessage)
        assertEquals(3, callCount.get(), "Should have tried 3 times")
    }

    @Test
    fun `should not retry non-transient errors`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            callCount.incrementAndGet()
            throw RuntimeException("Invalid API key")
        }

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertFalse(result.success)
        assertEquals(1, callCount.get(), "Should NOT retry non-transient errors")
    }

    @Test
    fun `should propagate CancellationException without retry`() {
        every { requestSpec.call() } answers {
            throw java.util.concurrent.CancellationException("Cancelled")
        }

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties.copy(
                concurrency = ConcurrencyProperties(requestTimeoutMs = 30000)
            )
        )

        // CancellationException should propagate through execute() (structured concurrency)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking {
                executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
            }
        }
    }

    @Test
    fun `should apply exponential backoff with jitter`() = runBlocking {
        val timestamps = mutableListOf<Long>()

        every { requestSpec.call() } answers {
            timestamps.add(System.currentTimeMillis())
            if (timestamps.size < 3) {
                throw RuntimeException("Rate limit exceeded")
            }
            responseSpec
        }
        every { responseSpec.content() } returns "OK"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties.copy(
                retry = RetryProperties(
                    maxAttempts = 3,
                    initialDelayMs = 100,
                    multiplier = 2.0,
                    maxDelayMs = 500
                )
            )
        )

        executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

        assertEquals(3, timestamps.size)
        // First delay: base 100ms ±25% jitter = 75..125ms
        val firstDelay = timestamps[1] - timestamps[0]
        assertTrue(firstDelay >= 60, "First delay ($firstDelay) should be ~100ms with jitter")

        // Second delay: base 200ms ±25% jitter = 150..250ms
        val secondDelay = timestamps[2] - timestamps[1]
        assertTrue(secondDelay >= 120, "Second delay ($secondDelay) should be ~200ms with jitter")
    }

    @Test
    fun `should not false-positive on error messages containing numbers`() = runBlocking {
        val callCount = AtomicInteger(0)

        // "Processed 500 records" contains "500" but is NOT a server error
        every { requestSpec.call() } answers {
            callCount.incrementAndGet()
            throw RuntimeException("Processed 500 records successfully")
        }

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

        // Should NOT retry because "500" in this context is not a transient error
        // The regex uses word boundaries so "500" in "Processed 500 records" won't match
        assertEquals(1, callCount.get(), "Should not retry false-positive 500")
    }

    @Test
    fun `should retry on connection error`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            val count = callCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("Connection refused")
            }
            responseSpec
        }
        every { responseSpec.content() } returns "Recovered"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertTrue(result.success)
        assertEquals(2, callCount.get())
    }

    @Test
    fun `should make at least one attempt when maxAttempts is zero`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            callCount.incrementAndGet()
            responseSpec
        }
        every { responseSpec.content() } returns "OK"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties.copy(
                retry = RetryProperties(maxAttempts = 0, initialDelayMs = 10, multiplier = 2.0, maxDelayMs = 100)
            )
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertTrue(result.success, "Should succeed with at least 1 attempt even when maxAttempts=0")
        assertEquals(1, callCount.get(), "Should have made exactly 1 attempt")
    }

    @Test
    fun `should retry on timeout error`() = runBlocking {
        val callCount = AtomicInteger(0)

        every { requestSpec.call() } answers {
            val count = callCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("Request timed out")
            }
            responseSpec
        }
        every { responseSpec.content() } returns "Recovered"
        every { responseSpec.chatResponse() } returns null

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertTrue(result.success)
        assertEquals(2, callCount.get())
    }
}
