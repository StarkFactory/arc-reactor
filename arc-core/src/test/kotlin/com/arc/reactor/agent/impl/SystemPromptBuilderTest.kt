package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SystemPromptBuilder에 대한 테스트.
 *
 * 시스템 프롬프트 구성 로직을 검증합니다.
 */
class SystemPromptBuilderTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `text format without rag에 대해 include only base prompt해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null
        )

        assertTrue(prompt.startsWith("You are helpful.")) {
            "Prompt should start with the base prompt"
        }
        assertTrue(prompt.contains("[Language Rule]")) {
            "Prompt should contain Language Rule section"
        }
        assertTrue(prompt.contains("[Grounding Rules]")) {
            "Prompt should contain Grounding Rules section"
        }
        assertTrue(prompt.contains("GENERAL questions")) {
            "Prompt should distinguish GENERAL vs WORKSPACE questions"
        }
        assertTrue(prompt.contains("[Few-shot Examples")) {
            "Prompt should contain Few-shot Examples section"
        }
        assertTrue(prompt.contains("confluence_answer_question")) {
            "Prompt should mention Confluence tool preference"
        }
        assertTrue(!prompt.contains("Sources")) {
            "Non-workspace prompts should not include 'Sources' section instruction"
        }
    }

    @Test
    fun `workspace prompts에 대해 include sources instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null,
            userPrompt = "DEV 프로젝트 Jira 이슈를 요약해줘."
        )

        assertTrue(prompt.contains("End the response with a 'Sources' section")) {
            "Workspace prompts should include 'Sources' section instruction"
        }
    }

    @Test
    fun `append rag and json instructions해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = "fact1",
            responseFormat = ResponseFormat.JSON,
            responseSchema = "{\"type\":\"object\"}"
        )

        assertTrue(prompt.startsWith("You are helpful.")) {
            "Prompt should start with the base prompt"
        }
        assertTrue(prompt.contains("[Retrieved Context]")) {
            "Prompt should contain RAG context section"
        }
        assertTrue(prompt.contains("fact1")) {
            "Prompt should include the RAG context content"
        }
        assertTrue(prompt.contains("[Response Format]")) {
            "Prompt should contain Response Format section"
        }
        assertTrue(prompt.contains("valid JSON only")) {
            "Prompt should instruct JSON response format"
        }
        assertTrue(prompt.contains("{\"type\":\"object\"}")) {
            "Prompt should include the JSON schema"
        }
    }

    @Test
    fun `page summary prompts에 대해 add required confluence routing instruction해야 한다`() {
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
    fun `briefing prompts에 대해 add required work briefing instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Give me a morning briefing for Jira project DEV and Bitbucket repo dev."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_morning_briefing")) {
            "Morning briefing prompts should require the work_morning_briefing tool"
        }
        assertTrue(prompt.contains("default profile settings") && prompt.contains("do not ask follow-up questions")) {
            "Morning briefing prompts should instruct the model to call the tool first and use defaults"
        }
    }

    @Test
    fun `release prompts에 대해 add required work release risk instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "DEV 프로젝트와 jarvis-project/dev 기준으로 release risk digest를 정리해줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_release_risk_digest")) {
            "Release risk prompts should require the work_release_risk_digest tool"
        }
        assertTrue(prompt.contains("do not ask follow-up questions")) {
            "Release risk prompts should use defaults before asking follow-up questions"
        }
    }

    @Test
    fun `profile prompts에 대해 add required work profile instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "저장된 briefing profile 목록을 보여줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("work_list_briefing_profiles")) {
            "Briefing profile prompts should require the work_list_briefing_profiles tool"
        }
    }

    @Test
    fun `ownership prompts에 대해 add required work owner instruction해야 한다`() {
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
    fun `add required personal focus instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "오늘 개인 focus plan을 근거 정보와 함께 만들어줘."
        )

        assertTrue(prompt.contains("MUST call `work_personal_focus_plan`")) {
            "Personal focus prompts should require the personal focus tool"
        }
    }

    @Test
    fun `add required personal learning instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "오늘 개인 learning digest를 근거 정보와 함께 만들어줘."
        )

        assertTrue(prompt.contains("MUST call `work_personal_learning_digest`")) {
            "Personal learning prompts should require the learning digest tool"
        }
    }

    @Test
    fun `add required personal interrupt instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "오늘 개인 interrupt guard plan을 근거 정보와 함께 만들어줘."
        )

        assertTrue(prompt.contains("MUST call `work_personal_interrupt_guard`")) {
            "Personal interrupt prompts should require the interrupt guard tool"
        }
    }

    @Test
    fun `add required personal wrapup instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "오늘 개인 end of day wrapup 초안을 근거 정보와 함께 만들어줘."
        )

        assertTrue(prompt.contains("MUST call `work_personal_end_of_day_wrapup`")) {
            "Personal wrapup prompts should require the wrapup tool"
        }
    }

    @Test
    fun `forced workspace tool execution 후 suppress must call instructions해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = "tool output",
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "BACKEND 프로젝트 기준으로 오늘의 Jira 브리핑을 만들어줘. 반드시 출처를 붙여줘.",
            workspaceToolAlreadyCalled = true
        )

        assertTrue(prompt.contains("already been executed")) {
            "Forced-tool flows should explain that a workspace tool has already been executed"
        }
        assertTrue(prompt.contains("Do not emit planning syntax")) {
            "Forced-tool flows should explicitly forbid leaked tool planning syntax"
        }
        assertTrue(!prompt.contains("MUST call `jira_daily_briefing`")) {
            "Forced-tool flows should not instruct the model to call the same tool again"
        }
    }

    @Test
    fun `issue context prompts에 대해 add required work item context instruction해야 한다`() {
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
    fun `service context prompts에 대해 add required work service instruction해야 한다`() {
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

    @Test
    fun `jira prompts에 대해 add jira routing instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "DEV 프로젝트에서 최근 Jira 이슈를 소스와 함께 요약해줘."
        )

        assertTrue(prompt.contains("MUST call `jira_search_issues`")) {
            "Recent Jira summary prompts should require jira_search_issues"
        }
    }

    @Test
    fun `recent summary prompts에 대해 add jira project summary instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "BACKEND 프로젝트에서 최근 Jira 이슈를 소스와 함께 요약해줘."
        )

        assertTrue(prompt.contains("MUST call `jira_search_issues`")) {
            "Recent Jira project summaries should force jira_search_issues"
        }
        assertTrue(prompt.contains("obvious project key")) {
            "Recent Jira project summaries should infer the project key from the prompt"
        }
    }

    @Test
    fun `bitbucket prompts에 대해 add bitbucket routing instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "jarvis-project/dev 저장소의 리뷰 대기열을 출처와 함께 보여줘."
        )

        assertTrue(prompt.contains("MUST call `bitbucket_review_queue`")) {
            "Bitbucket review-queue prompts should require bitbucket_review_queue"
        }
    }

    @Test
    fun `risk prompts에 대해 add bitbucket review risk instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Bitbucket에서 최근 코드 리뷰 리스크를 출처와 함께 요약해줘."
        )

        assertTrue(prompt.contains("MUST call `bitbucket_review_sla_alerts` or `bitbucket_review_queue`")) {
            "Bitbucket review risk prompts should require review-risk tools"
        }
    }

    @Test
    fun `repository prompts에 대해 add bitbucket repository list instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "접근 가능한 Bitbucket 저장소 목록을 출처와 함께 보여줘."
        )

        assertTrue(prompt.contains("MUST call `bitbucket_list_repositories`")) {
            "Bitbucket repository-list prompts should require bitbucket_list_repositories"
        }
        assertTrue(prompt.contains("do not ask follow-up questions")) {
            "Bitbucket repository-list prompts should use defaults before follow-up questions"
        }
    }

    @Test
    fun `swagger prompts에 대해 add swagger routing instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "https://petstore3.swagger.io/api/v3/openapi.json 스펙을 로드하고 endpoint를 요약해줘."
        )

        assertTrue(prompt.contains("MUST call `spec_load` and then `spec_summary`")) {
            "Swagger summary prompts should require spec_load and spec_summary"
        }
    }

    @Test
    fun `loaded spec summaries에 대해 add loaded swagger routing instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "현재 로드된 스펙 중 Petstore 관련 스펙을 요약해줘."
        )

        assertTrue(prompt.contains("MUST call `spec_list` and then `spec_summary`")) {
            "Loaded swagger summary prompts should require spec_list and spec_summary"
        }
    }

    @Test
    fun `route named swagger detail prompts without url through loaded spec tools해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "petstore-public Swagger spec detail for /pet. Tell me the methods and whether security is required."
        )

        assertTrue(prompt.contains("MUST call `spec_list` and then `spec_detail`")) {
            "Named swagger detail prompts without a URL should require spec_list and spec_detail"
        }
        assertTrue(prompt.contains("Only call `spec_load` when the user explicitly provides a spec URL or raw spec content")) {
            "Named swagger detail prompts should forbid spec_load unless a URL or raw content is supplied"
        }
    }

    @Test
    fun `add wrong endpoint swagger instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "로드된 Petstore 스펙에서 잘못된 endpoint를 찾으려 하면 어떻게 보이는지 보여줘."
        )

        assertTrue(prompt.contains("MUST call `spec_search`")) {
            "Wrong-endpoint prompts should require spec_search"
        }
    }

    @Test
    fun `treat confluence page body prompt as answer prompt해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Confluence에서 '개발팀 Home' 페이지 본문을 읽고 핵심만 요약해줘."
        )

        assertTrue(prompt.contains("MUST call") && prompt.contains("confluence_answer_question")) {
            "Confluence page body prompts should require the confluence_answer_question tool"
        }
    }

    @Test
    fun `keyword search prompts에 대해 add confluence discovery instruction해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Confluence에서 'weekly' 키워드로 검색하고 어떤 문서가 있는지 링크와 함께 알려줘."
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "Confluence discovery prompts should force confluence_search_by_text"
        }
    }

    @Test
    fun `explicitly refuse workspace mutation prompts in read only mode해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Jira 이슈 DEV-51를 담당자에게 재할당해줘."
        )

        assertTrue(prompt.contains("MUST refuse the action")) {
            "Workspace mutation prompts should require an explicit refusal"
        }
        assertTrue(prompt.contains("read-only")) {
            "Workspace mutation prompts should mention read-only mode"
        }
        assertTrue(prompt.contains("Do not ask follow-up questions")) {
            "Workspace mutation prompts should not ask follow-up questions"
        }
    }

    @Test
    fun `not treat release readiness pack prompt as mutation refusal해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "DEV 프로젝트와 jarvis-project/dev 기준으로 release readiness pack을 출처와 함께 만들어줘."
        )

        assertTrue(prompt.contains("MUST call `work_release_readiness_pack`")) {
            "Release readiness prompts should still route to the read tool"
        }
        assertTrue(!prompt.contains("MUST refuse the action")) {
            "Release readiness prompts should not be misclassified as write mutations"
        }
    }
}
