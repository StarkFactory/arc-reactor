package com.arc.reactor.slack.handler

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SlackSystemPromptFactoryTest {

    @Nested
    inner class BasicPrompt {

        @Test
        fun `without tool summary produces base prompt only를 빌드한다`() {
            val prompt = SlackSystemPromptFactory.build("gemini")

            prompt shouldContain "gemini"
            prompt shouldContain "Use only facts that you can verify"
            prompt shouldContain "Sources"
            prompt shouldContain "Prefer `confluence_answer_question`"
            prompt shouldContain "Do not answer Confluence knowledge questions from `confluence_search`"
            prompt shouldNotContain "[Cross-tool Correlation]"
            prompt shouldNotContain "[Connected Workspace Tools]"
        }

        @Test
        fun `with null tool summary omits cross-tool section를 빌드한다`() {
            val prompt = SlackSystemPromptFactory.build("gemini", connectedToolSummary = null)

            prompt shouldNotContain "[Cross-tool Correlation]"
        }

        @Test
        fun `with blank tool summary omits cross-tool section를 빌드한다`() {
            val prompt = SlackSystemPromptFactory.build("gemini", connectedToolSummary = "  ")

            prompt shouldNotContain "[Cross-tool Correlation]"
        }

        @Test
        fun `build uses fallback when provider은(는) blank이다`() {
            val prompt = SlackSystemPromptFactory.build("")

            prompt shouldContain "configured backend model"
            prompt shouldNotContain "best-effort answer with brief assumptions"
        }
    }

    @Nested
    inner class CrossToolCorrelation {

        @Test
        fun `with tool summary includes cross-tool section를 빌드한다`() {
            val summary = "[Connected Workspace Tools]\n- atlassian: jira_search, confluence_search"
            val prompt = SlackSystemPromptFactory.build("gemini", connectedToolSummary = summary)

            prompt shouldContain "[Cross-tool Correlation]"
            prompt shouldContain "atlassian: jira_search, confluence_search"
            prompt shouldContain "actively query ALL relevant tools"
        }
    }

    @Nested
    inner class ProactivePrompt {

        @Test
        fun `buildProactive은(는) includes proactive assistance section`() {
            val prompt = SlackSystemPromptFactory.buildProactive("gemini", null)

            prompt shouldContain "[Proactive Assistance Mode]"
            prompt shouldContain "[NO_RESPONSE]"
        }

        @Test
        fun `summary provided일 때 buildProactive includes both cross-tool and proactive`() {
            val summary = "[Connected Workspace Tools]\n- atlassian: jira_search"
            val prompt = SlackSystemPromptFactory.buildProactive("gemini", summary)

            prompt shouldContain "[Cross-tool Correlation]"
            prompt shouldContain "[Proactive Assistance Mode]"
            prompt shouldContain "atlassian: jira_search"
        }
    }

    @Nested
    inner class ToolSummaryBuilder {

        @Test
        fun `buildToolSummary은(는) returns null for empty map`() {
            SlackSystemPromptFactory.buildToolSummary(emptyMap()) shouldBe null
        }

        @Test
        fun `buildToolSummary은(는) formats server and tool names`() {
            val result = SlackSystemPromptFactory.buildToolSummary(
                mapOf(
                    "atlassian" to listOf("jira_search", "confluence_search"),
                    "github" to listOf("list_prs", "get_issue")
                )
            )

            result shouldContain "[Connected Workspace Tools]"
            result shouldContain "- atlassian: jira_search, confluence_search"
            result shouldContain "- github: list_prs, get_issue"
        }

        @Test
        fun `buildToolSummary은(는) handles single server`() {
            val result = SlackSystemPromptFactory.buildToolSummary(
                mapOf("atlassian" to listOf("jira_search"))
            )

            result shouldContain "- atlassian: jira_search"
        }
    }
}
