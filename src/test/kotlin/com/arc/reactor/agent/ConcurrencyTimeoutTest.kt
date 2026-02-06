package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for concurrency limiting (P1-1) and request timeout (P1-2) features.
 */
class ConcurrencyTimeoutTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<org.springframework.ai.chat.messages.Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.content() } returns "Response"
        every { responseSpec.chatResponse() } returns null
    }

    @Test
    fun `should enforce maxConcurrentRequests limit`() = runBlocking {
        val currentConcurrent = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)

        val properties = AgentProperties(
            concurrency = ConcurrencyProperties(
                maxConcurrentRequests = 2,
                requestTimeoutMs = 5000
            )
        )
        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        every { requestSpec.call() } answers {
            val concurrent = currentConcurrent.incrementAndGet()
            maxConcurrentObserved.updateAndGet { max -> maxOf(max, concurrent) }
            Thread.sleep(200)
            currentConcurrent.decrementAndGet()
            responseSpec
        }

        val jobs = List(5) { index ->
            async {
                executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Request $index"))
            }
        }

        jobs.awaitAll()

        assertTrue(maxConcurrentObserved.get() <= 2,
            "Max concurrent (${maxConcurrentObserved.get()}) should not exceed 2")
    }

    @Test
    fun `should timeout when request exceeds requestTimeoutMs`() = runBlocking {
        val properties = AgentProperties(
            concurrency = ConcurrencyProperties(
                maxConcurrentRequests = 20,
                requestTimeoutMs = 50
            )
        )
        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        every { requestSpec.call() } answers {
            Thread.sleep(500)
            responseSpec
        }

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertFalse(result.success, "Request should fail due to timeout")
        val errorMessage = requireNotNull(result.errorMessage)
        assertTrue(
            errorMessage.contains("timed out", ignoreCase = true) ||
            errorMessage.contains("timeout", ignoreCase = true),
            "Error message should mention timeout, got: $errorMessage"
        )
    }

    @Test
    fun `should allow requests within timeout`() = runBlocking {
        val properties = AgentProperties(
            concurrency = ConcurrencyProperties(
                maxConcurrentRequests = 20,
                requestTimeoutMs = 2000
            )
        )
        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        every { requestSpec.call() } answers {
            Thread.sleep(50)
            responseSpec
        }

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
        )

        assertTrue(result.success, "Request should succeed within timeout")
        assertNull(result.errorMessage)
    }
}
