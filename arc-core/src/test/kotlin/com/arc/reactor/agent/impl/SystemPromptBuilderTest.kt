package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `should add required confluence routing instruction for page summary prompts`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "What does the page titled 개발팀 Home describe in the DEV space?"
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("confluence_answer_question")) {
            "Confluence answer prompts should require the confluence_answer_question tool"
        }
    }

    @Test
    fun `should add required work briefing instruction for briefing prompts`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Give me a morning briefing for Jira project DEV and Bitbucket repo dev."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_morning_briefing")) {
            "Morning briefing prompts should require the work_morning_briefing tool"
        }
    }

    @Test
    fun `should add required work owner instruction for ownership prompts`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "PAY-123 이슈 기준으로 담당 서비스와 owner, 팀을 찾아줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_owner_lookup")) {
            "Ownership prompts should require the work_owner_lookup tool"
        }
    }

    @Test
    fun `should add required work item context instruction for issue context prompts`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "PAY-123 이슈 전체 맥락을 정리해줘. 관련 문서와 다음 액션까지 포함해줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_item_context")) {
            "Issue context prompts should require the work_item_context tool"
        }
    }

    @Test
    fun `should add required work service instruction for service context prompts`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_service_context")) {
            "Service context prompts should require the work_service_context tool"
        }
    }
}
