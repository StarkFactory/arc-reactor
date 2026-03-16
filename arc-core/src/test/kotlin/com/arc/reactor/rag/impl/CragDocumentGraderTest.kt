package com.arc.reactor.rag.impl

import com.arc.reactor.rag.GradingAction
import com.arc.reactor.rag.model.RetrievedDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage

class CragDocumentGraderTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClientRequestSpec>()
    private val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
    private lateinit var grader: CragDocumentGrader

    private val doc1 = RetrievedDocument(
        id = "doc-1",
        content = "Kotlin is a modern programming language for the JVM",
        score = 0.9
    )
    private val doc2 = RetrievedDocument(
        id = "doc-2",
        content = "Python is a popular scripting language",
        score = 0.8
    )
    private val doc3 = RetrievedDocument(
        id = "doc-3",
        content = "How to bake chocolate chip cookies",
        score = 0.7
    )

    @BeforeEach
    fun setUp() {
        grader = CragDocumentGrader(
            chatClient = chatClient,
            relevanceThreshold = 0.5,
            maxContentChars = 500,
            timeoutMs = 10_000
        )
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
    }

    private fun mockLlmResponse(text: String) {
        val assistantMessage = AssistantMessage(text)
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        every { callResponseSpec.chatResponse() } returns chatResponse
    }

    @Nested
    inner class GradingWithLlm {

        @Test
        fun `should return USE_AS_IS when all documents are relevant`() = runBlocking {
            mockLlmResponse("RELEVANT\nRELEVANT\nRELEVANT")

            val result = grader.grade("kotlin programming", listOf(doc1, doc2, doc3))

            result.action shouldBe GradingAction.USE_AS_IS
            result.relevantDocuments shouldHaveSize 3
        }

        @Test
        fun `should return FILTERED when some documents are irrelevant`() = runBlocking {
            mockLlmResponse("RELEVANT\nRELEVANT\nIRRELEVANT")

            val result = grader.grade("programming languages", listOf(doc1, doc2, doc3))

            result.action shouldBe GradingAction.FILTERED
            result.relevantDocuments shouldHaveSize 2
            result.relevantDocuments[0].id shouldBe "doc-1"
            result.relevantDocuments[1].id shouldBe "doc-2"
        }

        @Test
        fun `should return NEEDS_REWRITE when relevance ratio below threshold`() = runBlocking {
            mockLlmResponse("IRRELEVANT\nIRRELEVANT\nRELEVANT")

            val result = grader.grade("baking recipes", listOf(doc1, doc2, doc3))

            result.action shouldBe GradingAction.NEEDS_REWRITE
            result.relevantDocuments shouldHaveSize 1
            result.relevantDocuments[0].id shouldBe "doc-3"
        }

        @Test
        fun `should return NEEDS_REWRITE when all documents are irrelevant`() = runBlocking {
            mockLlmResponse("IRRELEVANT\nIRRELEVANT")

            val result = grader.grade("quantum physics", listOf(doc1, doc2))

            result.action shouldBe GradingAction.NEEDS_REWRITE
            result.relevantDocuments.shouldBeEmpty()
        }
    }

    @Nested
    inner class GracefulDegradation {

        @Test
        fun `should return all documents on LLM failure`() = runBlocking {
            every { callResponseSpec.chatResponse() } throws RuntimeException("LLM unavailable")

            val result = grader.grade("kotlin", listOf(doc1, doc2))

            result.action shouldBe GradingAction.USE_AS_IS
            result.relevantDocuments shouldHaveSize 2
        }

        @Test
        fun `should return USE_AS_IS for empty document list`() = runBlocking {
            val result = grader.grade("kotlin", emptyList())

            result.action shouldBe GradingAction.USE_AS_IS
            result.relevantDocuments.shouldBeEmpty()
        }

        @Test
        fun `should return all documents when LLM returns null response`() = runBlocking {
            every { callResponseSpec.chatResponse() } returns null

            val result = grader.grade("kotlin", listOf(doc1))

            result.action shouldBe GradingAction.USE_AS_IS
            result.relevantDocuments shouldHaveSize 1
        }
    }

    @Nested
    inner class VerdictParsing {

        @Test
        fun `should parse case-insensitive verdicts`() {
            val verdicts = CragDocumentGrader.parseVerdicts("relevant\nIrrelevant\nRELEVANT", 3)

            verdicts shouldBe listOf(true, false, true)
        }

        @Test
        fun `should default unparseable lines to relevant`() {
            val verdicts = CragDocumentGrader.parseVerdicts("RELEVANT\ngarbage\nIRRELEVANT", 3)

            verdicts shouldBe listOf(true, true, false)
        }

        @Test
        fun `should pad missing verdicts with relevant`() {
            val verdicts = CragDocumentGrader.parseVerdicts("RELEVANT", 3)

            verdicts shouldHaveSize 3
            verdicts shouldBe listOf(true, true, true)
        }

        @Test
        fun `should truncate extra verdicts`() {
            val verdicts = CragDocumentGrader.parseVerdicts(
                "RELEVANT\nIRRELEVANT\nRELEVANT\nIRRELEVANT",
                2
            )

            verdicts shouldHaveSize 2
            verdicts shouldBe listOf(true, false)
        }

        @Test
        fun `should skip blank lines`() {
            val verdicts = CragDocumentGrader.parseVerdicts(
                "RELEVANT\n\n\nIRRELEVANT",
                2
            )

            verdicts shouldBe listOf(true, false)
        }

        @Test
        fun `should handle empty response`() {
            val verdicts = CragDocumentGrader.parseVerdicts("", 2)

            verdicts shouldHaveSize 2
            verdicts shouldBe listOf(true, true)
        }
    }

    @Nested
    inner class ContentTruncation {

        @Test
        fun `should truncate long document content in prompt`() = runBlocking {
            val longDoc = RetrievedDocument(
                id = "long",
                content = "A".repeat(1000),
                score = 0.9
            )
            mockLlmResponse("RELEVANT")

            val shortGrader = CragDocumentGrader(
                chatClient = chatClient,
                maxContentChars = 100
            )
            val result = shortGrader.grade("test", listOf(longDoc))

            result.action shouldBe GradingAction.USE_AS_IS
            result.relevantDocuments shouldHaveSize 1
        }
    }

    @Nested
    inner class PipelineIntegration {

        @Test
        fun `should integrate with DefaultRagPipeline`() = runBlocking {
            mockLlmResponse("RELEVANT\nIRRELEVANT")

            val retriever = InMemoryDocumentRetriever()
            retriever.addDocuments(listOf(doc1, doc2))

            val pipeline = DefaultRagPipeline(
                retriever = retriever,
                grader = grader
            )

            val result = pipeline.retrieve(
                com.arc.reactor.rag.model.RagQuery(
                    query = "kotlin",
                    topK = 10,
                    rerank = false
                )
            )

            result.documents shouldHaveSize 1
            result.documents[0].id shouldBe "doc-1"
        }

        @Test
        fun `pipeline should work without grader`() = runBlocking {
            val retriever = InMemoryDocumentRetriever()
            retriever.addDocuments(listOf(doc1))

            val pipeline = DefaultRagPipeline(
                retriever = retriever,
                grader = null
            )

            val result = pipeline.retrieve(
                com.arc.reactor.rag.model.RagQuery(
                    query = "kotlin",
                    topK = 10,
                    rerank = false
                )
            )

            result.documents shouldHaveSize 1
        }
    }
}
