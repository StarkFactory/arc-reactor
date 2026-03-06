package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SystemPromptBuilderTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `should include only base prompt for text format without rag`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null
        )

        val expected = """
            You are helpful.

            [Grounding Rules]
            Use only facts supported by the retrieved context or tool results.
            If you cannot verify a fact, say you cannot verify it instead of guessing.
            For Jira, Confluence, Bitbucket, policy, documentation, or internal knowledge requests, call the relevant workspace tool before answering.
            Prefer `confluence_answer_question` for Confluence policy, wiki, service, or page-summary questions.
            Do not answer Confluence knowledge questions from `confluence_search` or `confluence_search_by_text` alone; use them only for discovery, then verify with `confluence_answer_question` or `confluence_get_page_content`.
            End the response with a 'Sources' section that lists the supporting links.
        """.trimIndent()
        assertEquals(expected, prompt)
    }

    @Test
    fun `should append rag and json instructions`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = "fact1",
            responseFormat = ResponseFormat.JSON,
            responseSchema = "{\"type\":\"object\"}"
        )

        val expected = """
            You are helpful.

            [Grounding Rules]
            Use only facts supported by the retrieved context or tool results.
            If you cannot verify a fact, say you cannot verify it instead of guessing.
            For Jira, Confluence, Bitbucket, policy, documentation, or internal knowledge requests, call the relevant workspace tool before answering.
            Prefer `confluence_answer_question` for Confluence policy, wiki, service, or page-summary questions.
            Do not answer Confluence knowledge questions from `confluence_search` or `confluence_search_by_text` alone; use them only for discovery, then verify with `confluence_answer_question` or `confluence_get_page_content`.

            [Retrieved Context]
            The following information was retrieved from the knowledge base and may be relevant.
            Use this context to inform your answer when relevant. If the context does not contain the answer, say so rather than guessing.
            Do not mention the retrieval process to the user.

            fact1

            [Response Format]
            You MUST respond with valid JSON only.
            - Do NOT wrap the response in markdown code blocks (no ```json or ```).
            - Do NOT include any text before or after the JSON.
            - The response MUST start with '{' or '[' and end with '}' or ']'.

            Expected JSON schema:
            {"type":"object"}
        """.trimIndent()
        assertEquals(expected, prompt)
    }
}
