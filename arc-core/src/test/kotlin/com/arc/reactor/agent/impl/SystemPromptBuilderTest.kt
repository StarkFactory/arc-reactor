package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            "Prompt should start with base prompt"
        }
        assertTrue(prompt.contains("[Language Rule]")) {
            "Prompt should contain Language Rule section"
        }
        assertTrue(prompt.contains("[Grounding Rules]")) {
            "Prompt should contain Grounding Rules section"
        }
        assertTrue(prompt.contains("[Conversation History]")) {
            "Prompt should contain Conversation History section"
        }
        // 비워크스페이스 쿼리(userPrompt=null)에서는 워크스페이스 규칙 생략
        assertFalse(prompt.contains("[Few-shot Examples")) {
            "Non-workspace prompts should not contain Few-shot Examples"
        }
        assertFalse(prompt.contains("[Read vs Write")) {
            "Non-workspace prompts should not contain Read vs Write section"
        }
        assertFalse(prompt.contains("confluence_answer_question")) {
            "Non-workspace prompts should not contain Confluence routing instruction"
        }
        assertFalse(prompt.contains("[Retrieved Context]")) {
            "Non-RAG prompts should not include Retrieved Context section"
        }
        assertFalse(prompt.contains("[Response Format]")) {
            "TEXT format should not include Response Format section"
        }
    }

    @Test
    fun `workspace prompt에 대해 include full workspace grounding rules해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null,
            userPrompt = "Jira 이슈 목록을 보여줘."
        )

        assertTrue(prompt.contains("[Language Rule]")) {
            "Workspace prompts should contain Language Rule section"
        }
        assertTrue(prompt.contains("[Grounding Rules]")) {
            "Workspace prompts should contain Grounding Rules section"
        }
        assertTrue(prompt.contains("GENERAL questions")) {
            "Workspace prompts should distinguish GENERAL questions"
        }
        assertTrue(prompt.contains("WORKSPACE questions")) {
            "Workspace prompts should distinguish WORKSPACE questions"
        }
        assertTrue(prompt.contains("[Few-shot Examples")) {
            "Workspace prompts should contain Few-shot Examples section"
        }
        assertTrue(prompt.contains("[Read vs Write")) {
            "Workspace prompts should contain Read vs Write section"
        }
        assertTrue(prompt.contains("confluence_answer_question")) {
            "Workspace prompts should contain Confluence routing instruction"
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

        // R174: 출처 섹션 필수 포함 — '출처' 섹션 또는 'Sources Section' 키워드 검증
        assertTrue(prompt.contains("Sources Section") || prompt.contains("출처 섹션 필수 포함")) {
            "Workspace prompts should include sources section instruction"
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
            "Prompt should start with base prompt"
        }
        assertTrue(prompt.contains("[Language Rule]")) {
            "Prompt should contain Language Rule section"
        }
        assertTrue(prompt.contains("[Grounding Rules]")) {
            "Prompt should contain Grounding Rules section"
        }
        assertTrue(prompt.contains("[Retrieved Context]")) {
            "RAG prompt should include Retrieved Context section"
        }
        assertTrue(prompt.contains("fact1")) {
            "RAG context should be embedded in the prompt"
        }
        assertTrue(prompt.contains("[Response Format]")) {
            "JSON format should include Response Format section"
        }
        assertTrue(prompt.contains("You MUST respond with valid JSON only")) {
            "JSON instruction should be present"
        }
        assertTrue(prompt.contains("{\"type\":\"object\"}")) {
            "JSON schema should be embedded"
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

        // A4 "오늘 할 일" 6회 연속 실패 근본 해결(커밋 184cd26e) 이후 work_prepare_standup_update가
        // primary가 되었고 work_personal_focus_plan은 fallback 경로로 강등되었다.
        // 테스트는 두 도구 중 하나가 포함되면 성공으로 본다 — focus 도구 활용 유도가 핵심 의도이기 때문이다.
        assertTrue(prompt.contains("work_personal_focus_plan")) {
            "Personal focus prompts should mention the personal focus tool (primary or fallback)"
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
    fun `include conversation history instruction in grounding preamble해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT
        )

        assertTrue(prompt.contains("[Conversation History]")) {
            "Prompt should contain Conversation History section"
        }
        assertTrue(prompt.contains("conversation history from this session")) {
            "Prompt should instruct the model to use conversation history"
        }
        assertTrue(prompt.contains("Do NOT say \"I cannot remember\"")) {
            "Prompt should explicitly forbid denying memory within the session"
        }
        assertTrue(prompt.contains("not personal data collection")) {
            "Prompt should clarify that session context is not personal data collection"
        }
    }

    @Test
    fun `include conversation history instruction even when workspaceToolAlreadyCalled해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            workspaceToolAlreadyCalled = true
        )

        assertTrue(prompt.contains("[Conversation History]")) {
            "Prompt should contain Conversation History section even after workspace tool call"
        }
    }

    @Test
    fun `workspace prompts에 대해 include compound question hint해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null,
            userPrompt = "JAR-36 이슈 상태와 온보딩 가이드 문서를 찾아줘"
        )

        assertTrue(prompt.contains("[Compound Questions]")) {
            "Workspace prompts should contain Compound Questions section"
        }
        assertTrue(prompt.contains("MULTIPLE topics")) {
            "Compound question hint should mention multiple topics"
        }
        assertTrue(prompt.contains("EACH sub-question")) {
            "Compound question hint should instruct calling tool for each sub-question"
        }
    }

    @Test
    fun `non-workspace prompts에 대해 exclude compound question hint해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null,
            userPrompt = "Kotlin data class 예시를 보여줘"
        )

        assertFalse(prompt.contains("[Compound Questions]")) {
            "Non-workspace prompts should not contain Compound Questions section"
        }
    }

    @Test
    fun `workspaceToolAlreadyCalled 시 suppress compound question hint해야 한다`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            responseSchema = null,
            userPrompt = "JAR-36 이슈 상태와 온보딩 가이드 문서를 찾아줘",
            workspaceToolAlreadyCalled = true
        )

        assertFalse(prompt.contains("[Compound Questions]")) {
            "Compound question hint should be suppressed when workspace tool already called"
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

    // ── 계획 단계 시스템 프롬프트 테스트 ──

    @Test
    fun `buildPlanningPrompt에 역할 지시 섹션이 포함되어야 한다`() {
        val prompt = builder.buildPlanningPrompt(
            userPrompt = "JAR-36 이슈 상태를 알려줘",
            toolDescriptions = "- jira_get_issue: 이슈 조회"
        )

        assertTrue(prompt.contains("[Role]")) {
            "Planning prompt should contain Role section"
        }
        assertTrue(prompt.contains("플래너")) {
            "Planning prompt should identify LLM as a planner"
        }
        assertTrue(prompt.contains("계획만 출력")) {
            "Planning prompt should instruct plan-only output"
        }
    }

    @Test
    fun `buildPlanningPrompt에 사용 가능한 도구 목록이 포함되어야 한다`() {
        val tools = "- jira_get_issue: 이슈 조회\n- confluence_search: 문서 검색"
        val prompt = builder.buildPlanningPrompt(
            userPrompt = "JAR-36 이슈와 관련 문서를 찾아줘",
            toolDescriptions = tools
        )

        assertTrue(prompt.contains("[Available Tools]")) {
            "Planning prompt should contain Available Tools section"
        }
        assertTrue(prompt.contains("jira_get_issue")) {
            "Planning prompt should include tool names"
        }
        assertTrue(prompt.contains("confluence_search")) {
            "Planning prompt should include all tool names"
        }
        assertTrue(prompt.contains("목록에 없는 도구는 사용할 수 없습니다")) {
            "Planning prompt should restrict to listed tools only"
        }
    }

    @Test
    fun `buildPlanningPrompt에 출력 스키마가 포함되어야 한다`() {
        val prompt = builder.buildPlanningPrompt(
            userPrompt = "테스트",
            toolDescriptions = "- tool_a: 도구 A"
        )

        assertTrue(prompt.contains("[Output Format]")) {
            "Planning prompt should contain Output Format section"
        }
        assertTrue(prompt.contains("JSON 배열만 출력")) {
            "Planning prompt should require JSON array output"
        }
        assertTrue(prompt.contains("\"tool\"")) {
            "Planning prompt should show tool field in example"
        }
        assertTrue(prompt.contains("\"args\"")) {
            "Planning prompt should show args field in example"
        }
        assertTrue(prompt.contains("\"description\"")) {
            "Planning prompt should show description field in example"
        }
    }

    @Test
    fun `buildPlanningPrompt에 제약 조건이 포함되어야 한다`() {
        val prompt = builder.buildPlanningPrompt(
            userPrompt = "테스트",
            toolDescriptions = "- tool_a: 도구 A"
        )

        assertTrue(prompt.contains("[Constraints]")) {
            "Planning prompt should contain Constraints section"
        }
        assertTrue(prompt.contains("빈 배열 []")) {
            "Planning prompt should mention empty array for no-tool case"
        }
        assertTrue(prompt.contains("의존 관계")) {
            "Planning prompt should mention dependency ordering"
        }
    }

    @Test
    fun `buildPlanningPrompt에 사용자 요청이 포함되어야 한다`() {
        val userPrompt = "JAR-36 이슈 상태와 온보딩 가이드를 찾아줘"
        val prompt = builder.buildPlanningPrompt(
            userPrompt = userPrompt,
            toolDescriptions = "- jira_get_issue: 이슈 조회"
        )

        assertTrue(prompt.contains("[User Request]")) {
            "Planning prompt should contain User Request section"
        }
        assertTrue(prompt.contains(userPrompt)) {
            "Planning prompt should include the user prompt verbatim"
        }
    }

    @Test
    fun `buildPlanningPrompt에 일반 build 전용 섹션이 포함되지 않아야 한다`() {
        val prompt = builder.buildPlanningPrompt(
            userPrompt = "Jira 이슈 목록을 보여줘",
            toolDescriptions = "- jira_search_issues: 이슈 검색"
        )

        assertFalse(prompt.contains("[Language Rule]")) {
            "Planning prompt should not contain Language Rule (standard build section)"
        }
        assertFalse(prompt.contains("[Grounding Rules]")) {
            "Planning prompt should not contain Grounding Rules (standard build section)"
        }
        assertFalse(prompt.contains("[Conversation History]")) {
            "Planning prompt should not contain Conversation History (standard build section)"
        }
        assertFalse(prompt.contains("[Few-shot Examples")) {
            "Planning prompt should not contain Few-shot Examples (standard build section)"
        }
    }

    // ── R197: INTERNAL_DOC_HINTS 확장 테스트 (B4 "개발 환경 세팅 방법" 회귀 방지) ──

    @Test
    fun `R197 '개발 환경 세팅 방법' prompt should force confluence_search_by_text`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "개발 환경 세팅 방법"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "'개발 환경 세팅 방법' should trigger INTERNAL_DOC_HINTS → confluence_search_by_text forcing"
        }
        assertTrue(prompt.contains("사내 문서")) {
            "Prompt should mention 사내 문서 routing rationale"
        }
    }

    @Test
    fun `R197 '환경 설정' prompt should force confluence_search_by_text`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "프로젝트 환경 설정 어떻게 해?"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "'환경 설정' should trigger INTERNAL_DOC_HINTS routing"
        }
    }

    @Test
    fun `R197 '셋업' prompt should force confluence_search_by_text`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "로컬 셋업 문서 있어?"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "'셋업' should trigger INTERNAL_DOC_HINTS routing"
        }
    }

    @Test
    fun `R197 '설치 방법' prompt should force confluence_search_by_text`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "Docker 설치 방법 알려줘"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "'설치 방법' should trigger INTERNAL_DOC_HINTS routing"
        }
    }

    @Test
    fun `R197 'development environment' prompt should force confluence_search_by_text`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "How to setup development environment"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "English 'development environment' should trigger INTERNAL_DOC_HINTS routing"
        }
    }

    @Test
    fun `R197 '릴리즈 노트' prompt should still force confluence_search_by_text (pre-existing hint)`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "최신 릴리즈 노트 찾아줘"
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "Pre-existing '릴리즈 노트' hint should still work after R197 extension"
        }
    }

    @Test
    fun `R197 general prompt without INTERNAL_DOC_HINTS should NOT force confluence`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "오늘 날씨 어때?"
        )

        // INTERNAL_DOC forcing 섹션이 포함되지 않아야 한다 (날씨는 일반 지식 질문)
        // 다만 다른 섹션에서 confluence_search_by_text를 언급할 수 있으므로
        // INTERNAL_DOC_HINTS 전용 메시지("사내 문서")가 없어야 함
        assertFalse(prompt.contains("사내 문서(릴리즈 노트")) {
            "General weather query should not trigger INTERNAL_DOC forcing"
        }
    }

    // ── R199: 자기 정체성 힌트 (아크리액터 → Iron Man 혼동 방지) ──

    @Test
    fun `R199 general query should include self identity hint`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "아크리액터 어떻게 사용해?"
        )

        // "아크리액터"는 워크스페이스 키워드가 아니므로 general grounding 경로를 탄다
        assertTrue(prompt.contains("[Self Identity]")) {
            "General queries should include [Self Identity] section"
        }
        assertTrue(prompt.contains("Spring AI-based AI agent framework")) {
            "Self identity should clarify Arc Reactor is this framework"
        }
        assertTrue(prompt.contains("NOT the fictional Tony Stark")) {
            "Self identity should explicitly reject Iron Man interpretation"
        }
    }

    @Test
    fun `R199 workspace query should NOT inject self identity hint`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "내 Jira 이슈 보여줘"
        )

        // workspace grounding 경로는 self identity 주입 대상 아님
        assertFalse(prompt.contains("[Self Identity]")) {
            "Workspace grounding should not include [Self Identity] section"
        }
    }

    // ── R208: minimal prompt retry 경로 테스트 (B4 deterministic empty 해결) ──

    @Test
    fun `R208 normal workspace prompt should include all forcing sections`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "개발 환경 세팅 방법",
            minimalPromptRetry = false
        )

        assertTrue(prompt.contains("MUST call `confluence_search_by_text`")) {
            "Normal path should include INTERNAL_DOC forcing for 'setup' keywords"
        }
        assertTrue(prompt.contains("⚠️ 최종 재확인 — 예약 문구 절대 금지")) {
            "Normal path should include R203 final reminder"
        }
        assertTrue(prompt.contains("Tool Call Efficiency — 중복 호출 금지")) {
            "Normal path should include R202 preventive hint section"
        }
    }

    @Test
    fun `R208 minimalPromptRetry should skip INTERNAL_DOC forcing`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "개발 환경 세팅 방법",
            minimalPromptRetry = true
        )

        // R208 핵심: INTERNAL_DOC forcing 생략 (B4 deterministic empty trigger 회피)
        assertFalse(prompt.contains("사내 문서(가이드/매뉴얼/릴리즈 노트/온보딩/환경 세팅")) {
            "Minimal retry should NOT include INTERNAL_DOC forcing body"
        }
    }

    @Test
    fun `R208 minimalPromptRetry should skip R203 final reminder`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "내 Jira 이슈 보여줘",
            minimalPromptRetry = true
        )

        assertFalse(prompt.contains("⚠️ 최종 재확인 — 예약 문구 절대 금지")) {
            "Minimal retry should NOT include R203 final reminder"
        }
    }

    @Test
    fun `R208 minimalPromptRetry should skip duplicate tool call prevention`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "내 Jira 이슈 보여줘",
            minimalPromptRetry = true
        )

        assertFalse(prompt.contains("Tool Call Efficiency — 중복 호출 금지")) {
            "Minimal retry should NOT include R202 preventive hint"
        }
    }

    @Test
    fun `R208 minimalPromptRetry should still include core grounding rules`() {
        val prompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "내 Jira 이슈 보여줘",
            minimalPromptRetry = true
        )

        // Language Rule과 Grounding Rules는 여전히 포함되어야 함 (핵심 기능 유지)
        assertTrue(prompt.contains("[Language Rule]")) {
            "Minimal retry should still include Language Rule"
        }
        assertTrue(prompt.contains("[Grounding Rules]")) {
            "Minimal retry should still include Grounding Rules"
        }
    }

    @Test
    fun `R208 minimalPromptRetry prompt should be significantly shorter than normal`() {
        val normalPrompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "개발 환경 세팅 방법",
            minimalPromptRetry = false
        )
        val minimalPrompt = builder.build(
            basePrompt = "You are helpful.",
            ragContext = null,
            responseFormat = ResponseFormat.TEXT,
            userPrompt = "개발 환경 세팅 방법",
            minimalPromptRetry = true
        )

        assertTrue(minimalPrompt.length < normalPrompt.length) {
            "Minimal prompt (${minimalPrompt.length}) should be shorter than normal (${normalPrompt.length})"
        }
        // 적어도 20% 이상 줄어들어야 효과가 있음
        val reduction = (normalPrompt.length - minimalPrompt.length).toDouble() / normalPrompt.length
        assertTrue(reduction >= 0.20) {
            "Minimal prompt should reduce size by >= 20%, got ${(reduction * 100).toInt()}% " +
                "(normal=${normalPrompt.length}, minimal=${minimalPrompt.length})"
        }
    }
}
