package com.arc.reactor.rag.impl

import com.arc.reactor.agent.impl.RagContextRetriever
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.QueryComplexity
import com.arc.reactor.rag.QueryRouter
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage

class AdaptiveQueryRouterTest {

    @Nested
    inner class ParseComplexityTest {

        @Test
        fun `parses NO_RETRIEVAL correctly`() {
            assertEquals(
                QueryComplexity.NO_RETRIEVAL,
                AdaptiveQueryRouter.parseComplexity("NO_RETRIEVAL"),
                "Should parse NO_RETRIEVAL"
            )
        }

        @Test
        fun `parses SIMPLE correctly`() {
            assertEquals(
                QueryComplexity.SIMPLE,
                AdaptiveQueryRouter.parseComplexity("SIMPLE"),
                "Should parse SIMPLE"
            )
        }

        @Test
        fun `parses COMPLEX correctly`() {
            assertEquals(
                QueryComplexity.COMPLEX,
                AdaptiveQueryRouter.parseComplexity("COMPLEX"),
                "Should parse COMPLEX"
            )
        }

        @Test
        fun `parses lowercase response`() {
            assertEquals(
                QueryComplexity.COMPLEX,
                AdaptiveQueryRouter.parseComplexity("complex"),
                "Should parse lowercase 'complex'"
            )
        }

        @Test
        fun `parses response with extra text`() {
            assertEquals(
                QueryComplexity.NO_RETRIEVAL,
                AdaptiveQueryRouter.parseComplexity("Classification: NO_RETRIEVAL because it's a greeting"),
                "Should extract NO_RETRIEVAL from verbose response"
            )
        }

        @Test
        fun `defaults to SIMPLE for unrecognized response`() {
            assertEquals(
                QueryComplexity.SIMPLE,
                AdaptiveQueryRouter.parseComplexity("something unexpected"),
                "Should default to SIMPLE for unrecognized response"
            )
        }

        @Test
        fun `defaults to SIMPLE for empty response`() {
            assertEquals(
                QueryComplexity.SIMPLE,
                AdaptiveQueryRouter.parseComplexity(""),
                "Should default to SIMPLE for empty response"
            )
        }
    }

    @Nested
    inner class RouteTest {

        private fun mockChatClient(response: String): ChatClient {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>()
            val callResponseSpec = mockk<CallResponseSpec>()
            val chatResponse = mockk<ChatResponse>()
            val generation = mockk<Generation>()
            val assistantMessage = AssistantMessage(response)

            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } returns callResponseSpec
            every { callResponseSpec.chatResponse() } returns chatResponse
            every { chatResponse.result } returns generation
            every { generation.output } returns assistantMessage

            return chatClient
        }

        @Test
        fun `routes greeting as NO_RETRIEVAL`() = runBlocking {
            val chatClient = mockChatClient("NO_RETRIEVAL")
            val router = AdaptiveQueryRouter(chatClient)

            val result = router.route("Hello!")

            assertEquals(
                QueryComplexity.NO_RETRIEVAL, result,
                "Greeting should be classified as NO_RETRIEVAL"
            )
        }

        @Test
        fun `routes simple question as SIMPLE`() = runBlocking {
            val chatClient = mockChatClient("SIMPLE")
            val router = AdaptiveQueryRouter(chatClient)

            val result = router.route("What is our return policy?")

            assertEquals(
                QueryComplexity.SIMPLE, result,
                "Simple question should be classified as SIMPLE"
            )
        }

        @Test
        fun `routes multi-hop question as COMPLEX`() = runBlocking {
            val chatClient = mockChatClient("COMPLEX")
            val router = AdaptiveQueryRouter(chatClient)

            val result = router.route(
                "Compare the performance of product A vs B across all regions"
            )

            assertEquals(
                QueryComplexity.COMPLEX, result,
                "Multi-hop question should be classified as COMPLEX"
            )
        }

        @Test
        fun `defaults to SIMPLE on LLM failure`() = runBlocking {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>()
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } throws RuntimeException("LLM unavailable")

            val router = AdaptiveQueryRouter(chatClient)
            val result = router.route("some query")

            assertEquals(
                QueryComplexity.SIMPLE, result,
                "Should default to SIMPLE when LLM fails"
            )
        }

        @Test
        fun `rethrows CancellationException`() {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>()
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } throws CancellationException("cancelled")

            val router = AdaptiveQueryRouter(chatClient)

            assertThrows(CancellationException::class.java) {
                runBlocking { router.route("test") }
            }
        }

        @Test
        fun `defaults to SIMPLE on timeout`() = runTest {
            val chatClient = mockk<ChatClient>()
            val requestSpec = mockk<ChatClientRequestSpec>()
            every { chatClient.prompt() } returns requestSpec
            every { requestSpec.system(any<String>()) } returns requestSpec
            every { requestSpec.user(any<String>()) } returns requestSpec
            every { requestSpec.call() } answers {
                Thread.sleep(5000)
                throw RuntimeException("should not reach")
            }

            val router = AdaptiveQueryRouter(chatClient, timeoutMs = 100)
            val result = router.route("test query")

            assertEquals(
                QueryComplexity.SIMPLE, result,
                "Should default to SIMPLE on timeout"
            )
        }
    }

    @Nested
    inner class RagContextRetrieverIntegrationTest {

        @Test
        fun `skips retrieval when router returns NO_RETRIEVAL`() = runBlocking {
            val pipeline = mockk<RagPipeline>()
            val router = mockk<QueryRouter>()
            coEvery { router.route(any()) } returns QueryComplexity.NO_RETRIEVAL

            val retriever = RagContextRetriever(
                enabled = true,
                topK = 5,
                rerankEnabled = true,
                ragPipeline = pipeline,
                retrievalTimeoutMs = 5000,
                queryRouter = router,
                complexTopK = 15
            )

            val result = retriever.retrieve(
                AgentCommand(systemPrompt = "sys", userPrompt = "Hello!")
            )

            assertNull(result, "Should return null when router says NO_RETRIEVAL")
            coVerify(exactly = 0) { pipeline.retrieve(any()) }
        }

        @Test
        fun `uses default topK when router returns SIMPLE`() = runBlocking {
            val pipeline = mockk<RagPipeline>()
            val router = mockk<QueryRouter>()
            val querySlot = slot<RagQuery>()
            coEvery { router.route(any()) } returns QueryComplexity.SIMPLE
            coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
                context = "simple context",
                documents = listOf(
                    RetrievedDocument(id = "doc-1", content = "content")
                )
            )

            val retriever = RagContextRetriever(
                enabled = true,
                topK = 5,
                rerankEnabled = true,
                ragPipeline = pipeline,
                retrievalTimeoutMs = 5000,
                queryRouter = router,
                complexTopK = 15
            )

            val result = retriever.retrieve(
                AgentCommand(systemPrompt = "sys", userPrompt = "What is X?")
            )

            assertEquals("simple context", result?.context, "Should return context for SIMPLE query")
            assertEquals(5, querySlot.captured.topK, "Should use default topK=5 for SIMPLE")
        }

        @Test
        fun `uses complexTopK when router returns COMPLEX`() = runBlocking {
            val pipeline = mockk<RagPipeline>()
            val router = mockk<QueryRouter>()
            val querySlot = slot<RagQuery>()
            coEvery { router.route(any()) } returns QueryComplexity.COMPLEX
            coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
                context = "complex context",
                documents = listOf(
                    RetrievedDocument(id = "doc-1", content = "content")
                )
            )

            val retriever = RagContextRetriever(
                enabled = true,
                topK = 5,
                rerankEnabled = true,
                ragPipeline = pipeline,
                retrievalTimeoutMs = 5000,
                queryRouter = router,
                complexTopK = 15
            )

            val result = retriever.retrieve(
                AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "Compare A vs B across all regions"
                )
            )

            assertEquals("complex context", result?.context, "Should return context for COMPLEX query")
            assertEquals(15, querySlot.captured.topK, "Should use complexTopK=15 for COMPLEX")
        }

        @Test
        fun `uses default topK when no router is configured`() = runBlocking {
            val pipeline = mockk<RagPipeline>()
            val querySlot = slot<RagQuery>()
            coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
                context = "context without router",
                documents = listOf(
                    RetrievedDocument(id = "doc-1", content = "content")
                )
            )

            val retriever = RagContextRetriever(
                enabled = true,
                topK = 10,
                rerankEnabled = true,
                ragPipeline = pipeline,
                retrievalTimeoutMs = 5000,
                queryRouter = null,
                complexTopK = 15
            )

            val result = retriever.retrieve(
                AgentCommand(systemPrompt = "sys", userPrompt = "some query")
            )

            assertEquals(
                "context without router", result?.context,
                "Should return context when no router configured"
            )
            assertEquals(
                10, querySlot.captured.topK,
                "Should use default topK when no router configured"
            )
        }
    }

    @Nested
    inner class QueryComplexityTest {

        @Test
        fun `NO_RETRIEVAL has zero multiplier`() {
            assertEquals(
                0.0, QueryComplexity.NO_RETRIEVAL.topKMultiplier,
                "NO_RETRIEVAL should have 0.0 multiplier"
            )
        }

        @Test
        fun `SIMPLE has 1x multiplier`() {
            assertEquals(
                1.0, QueryComplexity.SIMPLE.topKMultiplier,
                "SIMPLE should have 1.0 multiplier"
            )
        }

        @Test
        fun `COMPLEX has 3x multiplier`() {
            assertEquals(
                3.0, QueryComplexity.COMPLEX.topKMultiplier,
                "COMPLEX should have 3.0 multiplier"
            )
        }
    }
}
