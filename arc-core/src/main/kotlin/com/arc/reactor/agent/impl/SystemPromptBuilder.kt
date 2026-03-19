package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.support.WorkContextPatterns
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import com.arc.reactor.tool.WorkspaceMutationIntentDetector

/**
 * LLM에 전달할 시스템 프롬프트를 조합하는 빌더.
 *
 * 다음 요소들을 순서대로 결합하여 최종 시스템 프롬프트를 생성한다:
 * 1. **기본 프롬프트**: 에이전트의 기본 시스템 프롬프트
 * 2. **Grounding 규칙**: 사실 기반 응답, 도구 호출 강제 규칙, 읽기 전용 정책
 * 3. **RAG 컨텍스트**: 벡터 검색으로 찾은 관련 문서 (있는 경우)
 * 4. **응답 형식 지시**: JSON/YAML 형식 지정 (있는 경우)
 * 5. **후처리**: [SystemPromptPostProcessor] (카나리 토큰 등)
 *
 * Grounding 규칙에서는 사용자 프롬프트를 분석하여 적절한 도구 호출을 강제한다.
 * (예: Jira 관련 질문 → `jira_get_issue` 호출 강제)
 *
 * @see RagContextRetriever RAG 컨텍스트 검색
 * @see PromptRequestSpecBuilder 조합된 프롬프트를 요청 스펙에 적용
 * @see SystemPromptPostProcessor 카나리 토큰 등 후처리
 * @see WorkContextForcedToolPlanner 도구 호출 강제 계획 수립 (대응 역할)
 */
class SystemPromptBuilder(
    private val postProcessor: SystemPromptPostProcessor? = null
) {

    /**
     * 시스템 프롬프트를 조합한다.
     *
     * @param basePrompt 기본 시스템 프롬프트
     * @param ragContext RAG 검색 결과 텍스트 (없으면 null)
     * @param responseFormat 응답 형식 (TEXT, JSON, YAML)
     * @param responseSchema JSON/YAML 스키마 (있는 경우)
     * @param userPrompt 사용자 프롬프트 (도구 호출 강제 판단용)
     * @param workspaceToolAlreadyCalled workspace 도구가 이미 호출되었는지 여부
     * @return 조합된 최종 시스템 프롬프트
     */
    fun build(
        basePrompt: String,
        ragContext: String?,
        responseFormat: ResponseFormat = ResponseFormat.TEXT,
        responseSchema: String? = null,
        userPrompt: String? = null,
        workspaceToolAlreadyCalled: Boolean = false
    ): String {
        val parts = mutableListOf(basePrompt)
        parts.add(buildGroundingInstruction(responseFormat, userPrompt, workspaceToolAlreadyCalled))

        if (ragContext != null) {
            parts.add(buildRagInstruction(ragContext))
        }

        when (responseFormat) {
            ResponseFormat.JSON -> parts.add(buildJsonInstruction(responseSchema))
            ResponseFormat.YAML -> parts.add(buildYamlInstruction(responseSchema))
            ResponseFormat.TEXT -> {}
        }

        val result = parts.joinToString("\n\n")
        return postProcessor?.process(result) ?: result
    }

    // ── Grounding 규칙 조합 ──

    /**
     * Grounding 규칙 지시문을 구성한다.
     *
     * 사용자 프롬프트를 분석하여 적절한 도구 호출 강제 지시를 추가한다.
     * Jira, Confluence, Bitbucket, Swagger 등 workspace 도구에 대한 라우팅 규칙을 포함한다.
     */
    private fun buildGroundingInstruction(
        responseFormat: ResponseFormat,
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ): String = buildString {
        appendGroundingPreamble(workspaceToolAlreadyCalled)
        appendMutationRefusal(userPrompt)
        appendConfluenceToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendWorkToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendWorkContextToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendJiraToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendBitbucketToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendSwaggerToolForcing(userPrompt, workspaceToolAlreadyCalled)
        appendSourcesInstruction(responseFormat, userPrompt)
    }

    /** Grounding 기본 규칙 (사실 기반 응답, 읽기 전용 정책, Confluence 우선 규칙). */
    private fun StringBuilder.appendGroundingPreamble(workspaceToolAlreadyCalled: Boolean) {
        append("[Language Rule]\n")
        append("ALWAYS respond in Korean (한국어). Even if the user writes in English or mixed languages, your response must be in Korean.\n")
        append("Technical terms (e.g. Jira, Sprint, API) may remain in English, but all sentences and explanations must be in Korean.\n\n")
        append("[Grounding Rules]\n")
        append("There are two types of questions:\n")
        append("1. GENERAL questions (math, coding, concepts, explanations) → answer from your knowledge. No tools needed.\n")
        append("2. WORKSPACE questions (Jira issues, Confluence pages, Bitbucket, project status, team tasks) → MUST call tools first.\n")
        append("For workspace questions: call the relevant tool FIRST, then answer based on tool results.\n")
        append("For general questions: answer directly without tools. Do NOT say '도구를 찾을 수 없습니다' for general knowledge questions.\n")
        append("NEVER ask clarifying questions for work-related queries (e.g. 'which project?', 'which week?'). Instead, use sensible defaults and call the tool immediately.\n")
        append("Example: 'show me backlog issues' → call jira_search_issues. '주간 리포트' → search this week's issues and write the report.\n")
        append("Analyzing, summarizing, recommending, reporting, planning sprints, writing retrospectives are READ operations — they are NOT write operations. Do NOT refuse them as read-only.\n")
        append("Treat mixed-language queries (e.g. 'JAR project의 최근 issues') the same as pure Korean or English queries.\n")
        if (workspaceToolAlreadyCalled) {
            append("A required workspace tool has already been executed for this request.\n")
            append("Answer directly from the retrieved tool results.\n")
            append("Do not emit planning syntax such as ```tool_code``` or raw tool JSON.\n")
        } else {
            append("Your FIRST action for work-related queries must be a tool call, not prose or a clarifying question.\n")
            append("If the user mentions a project (e.g. JAR), use it. If not, search across all allowed projects.\n")
            append("Default tools: jira_search_issues for Jira queries, confluence_search for Confluence, work_morning_briefing for general status.\n")
            appendFewShotExamples()
        }
        appendFewShotReadOnlyExamples()
        append("If a Jira, Confluence, Bitbucket, or work-management request asks to create, update, assign, reassign, ")
        append("comment, approve, transition, convert, or delete something, refuse it as not allowed in read-only mode.\n")
        append("NEVER include curl, wget, fetch, httpie, or direct HTTP request examples that target ")
        append("Jira, Confluence, Bitbucket, or any workspace API in your response. ")
        append("Even if the user asks for a workaround, do not provide API call instructions that would bypass read-only restrictions.\n")
        append("\n[Tool Error Retry]\n")
        append("When a tool returns an error (e.g. \"Error: JQL query failed\", \"Error: invalid field\"), ")
        append("analyze the error message and retry with corrected parameters.\n")
        append("Do NOT report the raw error to the user. Simplify the query and try again.\n")
        append("For JQL errors: remove advanced functions (startOfWeek, startOfMonth, endOfWeek, endOfMonth), ")
        append("use simple date comparisons like 'created >= -7d' instead.\n")
        append("You have up to 10 tool calls per request — use retries wisely.\n")
        append("Prefer `confluence_answer_question` for Confluence policy, wiki, service, or page-summary questions.")
        append("\nDo not answer Confluence knowledge questions from `confluence_search` or `confluence_search_by_text` alone; ")
        append("use them only for discovery, then verify with `confluence_answer_question` or `confluence_get_page_content`.")
        append("\n\n[Conversation History]\n")
        append("You have access to the conversation history from this session.\n")
        append("When the user refers to something mentioned earlier (name, preference, prior question), ")
        append("use the conversation history to answer.\n")
        append("Do NOT say \"I cannot remember\" or \"I don't collect personal information\" — ")
        append("the user explicitly provided this information in the current session.\n")
        append("The conversation history is part of the current session context, not personal data collection.")
    }

    /**
     * Few-shot 예시: 도구 호출 패턴.
     * LLM이 되묻지 않고 바로 도구를 호출하도록 구체적 예시를 제공한다.
     */
    private fun StringBuilder.appendFewShotExamples() {
        append("\n[Few-shot Examples — ALWAYS follow this pattern]\n")
        append("User: '주간 리포트 작성해줘' → call jira_search_issues (this week) → write report from results\n")
        append("User: '스프린트 계획 세워줘' → call jira_search_issues (backlog) → recommend issues for next sprint\n")
        append("User: '회고 자료 만들어줘' → call jira_search_issues (completed) → write retrospective from results\n")
        append("User: '이슈 좀 보여줘' → call jira_search_issues (recent) → show results\n")
        append("User: 'JAR project의 issues 보여줘' → call jira_search_issues(project=JAR) → show results (mixed language = treat as Korean)\n")
        append("User: 'JAR-36을 칸반 카드로 보여줘' → call jira_get_issue(issueKey=JAR-36) → format as kanban card (READ, not write)\n")
        append("User: '회고 자료 만들어줘' → call jira_search_issues → write retrospective from results (READ, not write)\n")
        append("User: 'Kotlin data class 예시 보여줘' → answer directly (GENERAL question, no tool needed)\n")
        append("User: '15 * 23은?' → answer directly (math, no tool needed)\n")
        append("User: '이번 주 완료된 이슈' → call jira_search_issues → if JQL error → simplify query (use 'resolved >= -7d' instead of startOfWeek()) and retry → show results\n")
        append("WRONG: asking 'which project?', 'which week?', saying '도구를 찾을 수 없습니다', refusing to answer general questions\n")
        append("IMPORTANT: All workspace tools listed in your tool list ARE available. NEVER say '도구를 찾을 수 없습니다'.\n")
        append("If you are unsure, just call jira_search_issues — it is ALWAYS available and works for any Jira query.\n")
    }

    /**
     * Few-shot 예시: 읽기 vs 쓰기 작업 구분.
     * LLM이 분석/보고서 작성을 읽기 전용 위반으로 오판하지 않도록 한다.
     */
    private fun StringBuilder.appendFewShotReadOnlyExamples() {
        append("\n[Read vs Write — important distinction]\n")
        append("READ (allowed): search, analyze, summarize, report, recommend, plan, compare, review, retrospect, forecast, 만들어줘(보고서/자료), 작성해줘, 정리해줘\n")
        append("WRITE (refused): create JIRA issue, update status, assign, delete, transition, comment, approve\n")
        append("'회고 자료 만들어줘' = READ (search issues → write summary). '이슈 생성해줘' = WRITE.\n")
        append("'칸반 카드로 보여줘' = READ (get issue → format as card). '이슈 상태 변경해줘' = WRITE.\n")
    }

    /** 변경(mutation) 요청 감지 시 거부 지시를 추가한다. */
    private fun StringBuilder.appendMutationRefusal(userPrompt: String?) {
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(userPrompt)) {
            append("\nFor this request, you MUST refuse the action.")
            append(" State that the workspace is read-only and the requested mutation is not allowed.")
            append(" Do not ask follow-up questions.")
            append(" You may call a single read-only lookup tool only to cite the current item,")
            append(" but you MUST still refuse the mutation itself.")
        }
    }

    /** Confluence 도구 강제 호출 지시를 추가한다 (answer, discovery, page body). */
    private fun StringBuilder.appendConfluenceToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        if (workspaceToolAlreadyCalled) return
        if (looksLikeConfluenceAnswerPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `confluence_answer_question` before answering.")
            append(" Do not reply directly from general knowledge or prior context.")
        }
        if (looksLikeConfluenceDiscoveryPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `confluence_search_by_text` before answering.")
            append(" If the user asks for a list of matching pages, respond from search results and include the returned links.")
        }
        if (looksLikeConfluencePageBodyPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `confluence_get_page_content` or `confluence_answer_question` before answering.")
            append(" Use the page title or obvious keyword from the user message and do not ask follow-up questions before the first tool call.")
        }
    }

    /** work_* 시리즈 도구 강제 호출 지시를 추가한다. */
    private fun StringBuilder.appendWorkToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        if (workspaceToolAlreadyCalled) return
        appendWorkBriefingForcing(userPrompt)
        appendWorkStandupForcing(userPrompt)
        appendWorkReleaseRiskForcing(userPrompt)
        appendWorkHybridPriorityForcing(userPrompt)
        appendWorkReleaseReadinessForcing(userPrompt)
        appendWorkPersonalToolForcing(userPrompt)
        appendWorkProfileAndOwnerForcing(userPrompt)
    }

    /** 브리핑 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkBriefingForcing(userPrompt: String?) {
        if (looksLikeWorkBriefingPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_morning_briefing` before answering.")
            append(" Do not assemble the briefing manually.")
            append(" The tool accepts optional inputs and will use default profile settings when details are omitted.")
            append(" Infer obvious project/repository hints from the user message, but do not ask follow-up questions before the first tool call.")
        }
    }

    /** 스탠드업 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkStandupForcing(userPrompt: String?) {
        if (looksLikeWorkStandupPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_prepare_standup_update` before answering.")
            append(" Use default profile settings when optional parameters are omitted and do not ask follow-up questions before the first tool call.")
        }
    }

    /** 릴리즈 위험 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkReleaseRiskForcing(userPrompt: String?) {
        if (looksLikeWorkReleaseRiskPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_release_risk_digest` before answering.")
            append(" Use obvious release/project/repository hints from the user message and do not ask follow-up questions before the first tool call.")
        }
    }

    /** 혼합 우선순위 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkHybridPriorityForcing(userPrompt: String?) {
        if (looksLikeHybridPriorityPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_release_risk_digest` before answering.")
            append(" Combine blocker and review-queue signals through the digest instead of answering from general knowledge.")
        }
    }

    /** 릴리즈 준비 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkReleaseReadinessForcing(userPrompt: String?) {
        if (looksLikeWorkReleaseReadinessPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_release_readiness_pack` before answering.")
            append(" Use the provided defaults where possible and do not assemble the pack manually.")
            append(" Use preview mode in read-only workspaces and do not refuse unless the user explicitly asks to write data.")
        }
    }

    /** 개인 생산성 도구 (focus, learning, interrupt, wrapup) 강제 호출 지시. */
    private fun StringBuilder.appendWorkPersonalToolForcing(userPrompt: String?) {
        if (looksLikeWorkPersonalFocusPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_personal_focus_plan` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (looksLikeWorkPersonalLearningPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_personal_learning_digest` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (looksLikeWorkPersonalInterruptPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_personal_interrupt_guard` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (looksLikeWorkPersonalWrapupPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_personal_end_of_day_wrapup` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
    }

    /** 프로필 목록 및 소유자 조회 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkProfileAndOwnerForcing(userPrompt: String?) {
        if (looksLikeWorkBriefingProfilePrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_list_briefing_profiles` before answering.")
        }
        if (looksLikeWorkOwnerPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_owner_lookup` before answering.")
            append(" Do not guess ownership from prior context.")
        }
    }

    /** 작업 항목 컨텍스트 및 서비스 컨텍스트 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkContextToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        if (workspaceToolAlreadyCalled) return
        if (looksLikeWorkItemContextPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_item_context` before answering.")
            append(" Do not summarize Jira, Confluence, or Bitbucket context manually.")
        }
        if (looksLikeWorkServiceContextPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_service_context` before answering.")
            append(" Do not summarize service state from general knowledge or prior context.")
        }
    }

    /** Jira 도구 강제 호출 지시 (when 분기). */
    private fun StringBuilder.appendJiraToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        when {
            workspaceToolAlreadyCalled -> Unit
            looksLikeJiraProjectListPrompt(userPrompt) -> appendToolForcing(
                "`jira_list_projects`", " Do not answer from prior knowledge."
            )
            looksLikeJiraIssueTransitionPrompt(userPrompt) -> appendToolForcing(
                "`jira_get_transitions`", " Do not guess the available states."
            )
            looksLikeJiraIssuePrompt(userPrompt) -> appendToolForcing(
                "`jira_get_issue`", " Do not answer from prior knowledge."
            )
            looksLikeJiraDueSoonPrompt(userPrompt) -> appendToolForcing(
                "`jira_due_soon_issues`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            looksLikeJiraBlockerPrompt(userPrompt) -> appendToolForcing(
                "`jira_blocker_digest`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            looksLikeJiraDailyBriefingPrompt(userPrompt) -> appendToolForcing(
                "`jira_daily_briefing`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            looksLikeJiraProjectSummaryPrompt(userPrompt) -> appendToolForcing(
                "`jira_search_issues`",
                " Use the obvious project key from the user message and summarize the returned issues with source links."
            )
            looksLikeJiraSearchPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_search_by_text` or `jira_search_issues` before answering.")
                append(" Prefer `jira_search_by_text` when the user gives a keyword, and `jira_search_issues` for project-scoped searches.")
            }
            looksLikeAgileWorkflowPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_search_issues` before answering.")
                append(" This is a READ-ONLY analysis task (backlog analysis, sprint planning, retrospective, kanban).")
                append(" Search issues first, then analyze and recommend based on the results.")
            }
            looksLikeJiraPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call one or more Jira tools before answering.")
                append(" Prefer `jira_search_issues` for project-scoped status questions.")
            }
        }
    }

    /** Bitbucket 도구 강제 호출 지시 (when 분기). */
    private fun StringBuilder.appendBitbucketToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        when {
            workspaceToolAlreadyCalled -> Unit
            looksLikeBitbucketReviewSlaPrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_review_sla_alerts`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketReviewRiskPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_review_sla_alerts` or `bitbucket_review_queue` before answering.")
                append(" Prefer `bitbucket_review_sla_alerts` for risk summaries and `bitbucket_review_queue` for reviewer backlog.")
            }
            looksLikeBitbucketNeedsReviewPrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_review_queue`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketReviewQueuePrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_review_queue`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketStalePrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_stale_prs`",
                " Use the default stale threshold when the user omits it and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketBranchPrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_list_branches`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketRepositoryPrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_list_repositories`",
                " Use the accessible workspace defaults and do not ask follow-up questions before the first tool call."
            )
            looksLikeBitbucketPrompt(userPrompt) -> appendToolForcing(
                "`bitbucket_list_prs` or `bitbucket_get_pr`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
        }
    }

    /** Swagger/OpenAPI 도구 강제 호출 지시 (when 분기). */
    private fun StringBuilder.appendSwaggerToolForcing(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        when {
            workspaceToolAlreadyCalled -> Unit
            looksLikeSwaggerLoadedSummaryPrompt(userPrompt) -> appendToolChainForcing(
                "`spec_list`", "`spec_summary`", " Do not answer from `spec_list` alone."
            )
            looksLikeSwaggerWrongEndpointPrompt(userPrompt) -> appendToolForcing(
                "`spec_search`",
                " Use the endpoint fragment from the user request and explain the no-match result if nothing is found."
            )
            looksLikeSwaggerListPrompt(userPrompt) -> appendToolForcing("`spec_list`", "")
            looksLikeSwaggerValidatePrompt(userPrompt) -> appendToolForcing("`spec_validate`", "")
            looksLikeSwaggerLoadedSchemaPrompt(userPrompt) -> appendLoadedSpecForcing("`spec_schema`")
            looksLikeSwaggerLoadedDetailPrompt(userPrompt) -> appendLoadedSpecForcing("`spec_detail`")
            looksLikeSwaggerLoadedSearchPrompt(userPrompt) -> appendLoadedSpecForcing("`spec_search`")
            looksLikeSwaggerSchemaPrompt(userPrompt) -> appendToolChainForcing("`spec_load`", "`spec_schema`", "")
            looksLikeSwaggerDetailPrompt(userPrompt) -> appendToolChainForcing("`spec_load`", "`spec_detail`", "")
            looksLikeSwaggerSearchPrompt(userPrompt) -> appendToolChainForcing("`spec_load`", "`spec_search`", "")
            looksLikeSwaggerRemovePrompt(userPrompt) -> appendToolForcing("`spec_remove`", "")
            looksLikeSwaggerPrompt(userPrompt) -> appendSwaggerFallbackForcing(userPrompt)
        }
    }

    /** 단일 도구 강제 호출 지시를 추가하는 헬퍼. */
    private fun StringBuilder.appendToolForcing(toolName: String, suffix: String) {
        append("\nFor this request, you MUST call $toolName before answering.")
        append(suffix)
    }

    /** 두 도구를 순서대로 호출하는 강제 지시를 추가하는 헬퍼. */
    private fun StringBuilder.appendToolChainForcing(first: String, second: String, suffix: String) {
        append("\nFor this request, you MUST call $first and then $second before answering.")
        append(suffix)
    }

    /** 로드된 스펙 기반 도구 강제 호출 지시 (spec_list 선행 + spec_load 제한). */
    private fun StringBuilder.appendLoadedSpecForcing(toolName: String) {
        append("\nFor this request, you MUST call `spec_list` and then $toolName before answering.")
        append(" Only call `spec_load` when the user explicitly provides a spec URL or raw spec content.")
    }

    /** Swagger 프롬프트이지만 세부 분류에 해당하지 않을 때의 폴백 지시. */
    private fun StringBuilder.appendSwaggerFallbackForcing(userPrompt: String?) {
        if (hasSwaggerUrl(userPrompt)) {
            append("\nFor this request, you MUST call `spec_load` and then `spec_summary` before answering.")
        } else {
            append("\nFor this request, you MUST call `spec_list` and then `spec_summary` before answering.")
            append(" Only call `spec_load` when the user explicitly provides a spec URL or raw spec content.")
            append(" Do not answer from `spec_list` alone.")
        }
    }

    /** TEXT 형식 + workspace 요청 시 Sources 섹션 지시를 추가한다. */
    private fun StringBuilder.appendSourcesInstruction(responseFormat: ResponseFormat, userPrompt: String?) {
        if (responseFormat == ResponseFormat.TEXT && looksLikeWorkspacePrompt(userPrompt)) {
            append("\nEnd the response with a 'Sources' section that lists the supporting links.")
        }
    }

    // ── 응답 형식 및 RAG 지시문 ──

    /** JSON 형식 응답 지시문을 구성한다. */
    private fun buildJsonInstruction(responseSchema: String?): String = buildString {
        append("[Response Format]\n")
        append("You MUST respond with valid JSON only.\n")
        append("- Do NOT wrap the response in markdown code blocks (no ```json or ```).\n")
        append("- Do NOT include any text before or after the JSON.\n")
        append("- The response MUST start with '{' or '[' and end with '}' or ']'.")
        if (responseSchema != null) {
            append("\n\nExpected JSON schema:\n$responseSchema")
        }
    }

    /** YAML 형식 응답 지시문을 구성한다. */
    private fun buildYamlInstruction(responseSchema: String?): String = buildString {
        append("[Response Format]\n")
        append("You MUST respond with valid YAML only.\n")
        append("- Do NOT wrap the response in markdown code blocks (no ```yaml or ```).\n")
        append("- Do NOT include any text before or after the YAML.\n")
        append("- Use proper YAML indentation (2 spaces).")
        if (responseSchema != null) {
            append("\n\nExpected YAML structure:\n$responseSchema")
        }
    }

    /** RAG 검색 결과를 시스템 프롬프트에 주입하는 지시문을 구성한다. */
    private fun buildRagInstruction(ragContext: String): String = buildString {
        append("[Retrieved Context]\n")
        append("The following information was retrieved from the knowledge base and may be relevant.\n")
        append("Use this context to inform your answer when relevant. ")
        append("If the context does not contain the answer, say so rather than guessing.\n")
        append("When using this context, cite the source. ")
        append("If the context doesn't answer the question, use general knowledge and say so.\n")
        append("Do not mention the retrieval process to the user.\n\n")
        append(ragContext)
    }

    // ── 프롬프트 분류 함수 (Confluence) ──

    private fun looksLikeConfluenceAnswerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val knowledgeHint = CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) }
        val answerHint = CONFLUENCE_ANSWER_HINTS.any { normalized.contains(it) }
        return knowledgeHint && answerHint && !looksLikeConfluenceDiscoveryPrompt(prompt)
    }

    private fun looksLikeConfluenceDiscoveryPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) } &&
            CONFLUENCE_DISCOVERY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeConfluencePageBodyPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) } &&
            CONFLUENCE_PAGE_BODY_HINTS.any { normalized.contains(it) }
    }

    // ── 프롬프트 분류 함수 (Work 도구) ──

    private fun looksLikeWorkBriefingPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        // "회의 준비", "회의 브리핑" 등은 meeting prep 라우트로 처리
        if (MEETING_PREP_EXCLUDE_HINTS.any { normalized.contains(it) }) return false
        return WORK_BRIEFING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkStandupPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_STANDUP_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkReleaseRiskPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_RELEASE_RISK_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeHybridPriorityPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val hasPriorityHint = HYBRID_PRIORITY_HINTS.any { normalized.contains(it) }
        val hasReviewSignal = REVIEW_QUEUE_HINTS.any { normalized.contains(it) } || REVIEW_SLA_HINTS.any { normalized.contains(it) }
        return hasPriorityHint && BLOCKER_HINTS.any { normalized.contains(it) } && hasReviewSignal
    }

    private fun looksLikeWorkReleaseReadinessPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_RELEASE_READINESS_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkPersonalFocusPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_PERSONAL_FOCUS_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkPersonalLearningPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_PERSONAL_LEARNING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkPersonalInterruptPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_PERSONAL_INTERRUPT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkPersonalWrapupPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_PERSONAL_WRAPUP_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkBriefingProfilePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_BRIEFING_PROFILE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkOwnerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return !MISSING_ASSIGNEE_HINTS.any { normalized.contains(it) } &&
            WORK_OWNER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkItemContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val hasIssueKey = ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
        return hasIssueKey && WORK_ITEM_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkServiceContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val hasServiceMention = normalized.contains("service") || normalized.contains("서비스")
        return hasServiceMention && WORK_SERVICE_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    // ── 프롬프트 분류 함수 (Jira) ──

    /** 백로그/스프린트/회고/칸반 등 애자일 워크플로우 분석 요청인지 판단한다. */
    private fun looksLikeAgileWorkflowPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return AGILE_WORKFLOW_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return JIRA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraProjectListPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && PROJECT_LIST_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraIssueTransitionPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) && TRANSITION_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraIssuePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
    }

    private fun looksLikeJiraDueSoonPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && DUE_SOON_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraBlockerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && BLOCKER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraDailyBriefingPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && DAILY_BRIEFING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraProjectSummaryPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) &&
            JIRA_PROJECT_SUMMARY_HINTS.any { normalized.contains(it) } &&
            !looksLikeJiraDueSoonPrompt(prompt) &&
            !looksLikeJiraBlockerPrompt(prompt) &&
            !looksLikeJiraDailyBriefingPrompt(prompt) &&
            !looksLikeJiraSearchPrompt(prompt) &&
            !looksLikeJiraProjectListPrompt(prompt)
    }

    private fun looksLikeJiraSearchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && SEARCH_HINTS.any { normalized.contains(it) }
    }

    // ── 프롬프트 분류 함수 (Bitbucket) ──

    private fun looksLikeBitbucketPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return BITBUCKET_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketRepositoryPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REPOSITORY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketBranchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && BRANCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketStalePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && STALE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketReviewQueuePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REVIEW_QUEUE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketReviewRiskPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REVIEW_RISK_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketNeedsReviewPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && MY_REVIEW_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketReviewSlaPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REVIEW_SLA_HINTS.any { normalized.contains(it) }
    }

    // ── 프롬프트 분류 함수 (Swagger/OpenAPI) ──

    private fun looksLikeSwaggerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) || SWAGGER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerListPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && LIST_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerLoadedSummaryPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) &&
            !hasSwaggerUrl(prompt) &&
            LOADED_HINTS.any { normalized.contains(it) } &&
            SUMMARY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerLoadedSchemaPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) &&
            !hasSwaggerUrl(prompt) &&
            SCHEMA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerLoadedDetailPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) &&
            !hasSwaggerUrl(prompt) &&
            DETAIL_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerLoadedSearchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) &&
            !hasSwaggerUrl(prompt) &&
            SEARCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerWrongEndpointPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && WRONG_ENDPOINT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerValidatePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && VALIDATE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerSchemaPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && SCHEMA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerDetailPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && DETAIL_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerSearchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && SEARCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerRemovePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && REMOVE_HINTS.any { normalized.contains(it) }
    }

    private fun hasSwaggerUrl(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return OPENAPI_URL_REGEX.containsMatchIn(prompt)
    }

    // ── 프롬프트 분류 함수 (Workspace 통합) ──

    private fun looksLikeWorkspacePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return looksLikeJiraPrompt(prompt) ||
            looksLikeBitbucketPrompt(prompt) ||
            looksLikeSwaggerPrompt(prompt) ||
            looksLikeConfluenceAnswerPrompt(prompt) ||
            looksLikeConfluenceDiscoveryPrompt(prompt) ||
            looksLikeConfluencePageBodyPrompt(prompt) ||
            looksLikeWorkBriefingPrompt(prompt) ||
            looksLikeWorkStandupPrompt(prompt) ||
            looksLikeWorkReleaseRiskPrompt(prompt) ||
            looksLikeWorkReleaseReadinessPrompt(prompt) ||
            looksLikeWorkOwnerPrompt(prompt) ||
            looksLikeWorkItemContextPrompt(prompt) ||
            looksLikeWorkServiceContextPrompt(prompt)
    }

    // ── 프롬프트 분류를 위한 힌트 키워드 집합 (한국어/영어) ──
    companion object {
        private val CONFLUENCE_KNOWLEDGE_HINTS = setOf(
            "confluence", "wiki", "page", "document", "policy", "policies", "guideline", "guidelines",
            "runbook", "knowledge", "internal", "service", "space", "컨플루언스", "위키", "페이지",
            "문서", "정책", "규정", "가이드", "런북", "사내", "서비스", "스페이스"
        )
        private val CONFLUENCE_ANSWER_HINTS = setOf(
            "what", "who", "why", "how", "describe", "explain", "summary", "summarize", "tell me",
            "알려", "설명", "요약", "정리", "무엇", "왜", "어떻게", "누구", "본문", "body", "read", "읽"
        )
        private val CONFLUENCE_DISCOVERY_HINTS = setOf(
            "search", "find", "look up", "keyword", "list", "recent", "latest",
            "찾아", "검색", "키워드", "목록", "어떤 문서", "최근", "보여줘", "조회"
        )
        private val CONFLUENCE_PAGE_BODY_HINTS = setOf(
            "본문", "body", "content", "read", "읽고", "읽어", "내용", "핵심만"
        )
        private val WORK_BRIEFING_HINTS = setOf(
            "morning briefing", "daily briefing", "briefing", "work summary", "daily digest",
            "브리핑", "요약 브리핑", "아침 브리핑", "데일리 브리핑"
        )
        /** 회의 준비 라우트와 충돌 방지 — 이 키워드가 포함되면 briefing 대신 meeting prep으로 라우팅 */
        private val MEETING_PREP_EXCLUDE_HINTS = setOf(
            "회의 준비", "회의 브리핑", "미팅 준비", "미팅 브리핑",
            "meeting prep", "meeting preparation", "회의 전", "회의 들어가기 전"
        )
        private val WORK_STANDUP_HINTS = setOf(
            "standup", "스탠드업", "daily update", "업데이트 초안", "standup update"
        )
        private val WORK_RELEASE_RISK_HINTS = setOf(
            "release risk", "risk digest", "릴리즈 위험", "출시 위험", "release digest"
        )
        private val HYBRID_PRIORITY_HINTS = setOf(
            "priority", "priorities", "우선순위", "오늘 우선", "today priority"
        )
        private val WORK_RELEASE_READINESS_HINTS = setOf(
            "release readiness", "readiness pack", "릴리즈 준비", "출시 준비", "readiness"
        )
        private val WORK_PERSONAL_FOCUS_HINTS = setOf(
            "focus plan", "personal focus plan", "개인 focus plan", "개인 집중 계획", "오늘 집중 계획"
        )
        private val WORK_PERSONAL_LEARNING_HINTS = setOf(
            "learning digest", "personal learning digest", "학습 digest", "학습 다이제스트"
        )
        private val WORK_PERSONAL_INTERRUPT_HINTS = setOf(
            "interrupt guard", "personal interrupt guard", "interrupt plan", "인터럽트 가드", "집중 방해"
        )
        private val WORK_PERSONAL_WRAPUP_HINTS = setOf(
            "end of day wrapup", "end-of-day wrapup", "eod wrapup", "wrapup", "wrap-up", "마감 정리", "하루 마감"
        )
        private val WORK_BRIEFING_PROFILE_HINTS = setOf(
            "briefing profile", "profile list", "profiles", "브리핑 프로필", "프로필 목록"
        )
        private val WORK_OWNER_HINTS = setOf(
            "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
        )
        private val WORK_ITEM_CONTEXT_HINTS = setOf(
            "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr", "오픈 pr", "다음 액션", "next action"
        )
        private val WORK_SERVICE_CONTEXT_HINTS = setOf(
            "서비스 상황", "서비스 현황", "service context", "service summary", "현재 상황", "현재 현황",
            "최근 jira", "최근 jira 이슈", "열린 pr", "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
        )
        private val ISSUE_KEY_REGEX = WorkContextPatterns.ISSUE_KEY_REGEX
        private val AGILE_WORKFLOW_HINTS = setOf(
            "백로그", "backlog", "스프린트 계획", "sprint plan", "스프린트 플래닝", "sprint planning",
            "회고", "retrospective", "retro", "칸반", "kanban", "칸반 카드", "kanban card",
            "스프린트 회고", "sprint retro"
        )
        private val JIRA_HINTS = setOf(
            "jira", "지라", "이슈", "issues", "프로젝트", "project", "jql", "ticket", "티켓",
            "blocker", "마감", "due", "transition", "전이",
            "백로그", "backlog", "스프린트", "sprint", "회고", "retrospective", "retro",
            "칸반", "kanban", "할 일", "todo", "업무", "작업"
        )
        private val BITBUCKET_HINTS = setOf(
            "bitbucket", "repository", "repo", "pull request", "pr", "branch", "브랜치", "저장소", "리뷰", "sla"
        )
        private val SWAGGER_HINTS = setOf(
            "swagger", "openapi", "spec", "schema", "endpoint", "api spec", "스펙", "엔드포인트", "스키마"
        )
        private val OPENAPI_URL_REGEX = WorkContextPatterns.OPENAPI_URL_REGEX
        private val PROJECT_LIST_HINTS = setOf("project list", "projects", "프로젝트 목록", "프로젝트 리스트")
        private val DUE_SOON_HINTS = setOf("due soon", "마감", "임박", "due")
        private val BLOCKER_HINTS = setOf("blocker", "차단", "막힌")
        private val DAILY_BRIEFING_HINTS = setOf(
            "daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest", "오늘의 jira 브리핑", "오늘 jira 브리핑"
        )
        private val JIRA_PROJECT_SUMMARY_HINTS = setOf(
            "recent", "latest", "summary", "summarize", "최근", "요약", "정리", "브리핑"
        )
        private val SEARCH_HINTS = setOf("search", "찾아", "검색", "look up", "find")
        private val TRANSITION_HINTS = setOf("transition", "상태 전이", "전이", "possible states")
        private val REPOSITORY_HINTS = setOf("repository", "repo", "저장소")
        private val BRANCH_HINTS = setOf("branch", "브랜치")
        private val STALE_HINTS = setOf("stale", "오래된", "방치된")
        private val REVIEW_QUEUE_HINTS = setOf("queue", "대기열", "리뷰가 필요한", "검토가 필요한", "review needed")
        private val REVIEW_SLA_HINTS = setOf("sla", "응답 지연", "리뷰 sla")
        private val REVIEW_RISK_HINTS = setOf("review risk", "리뷰 리스크", "코드 리뷰 리스크")
        private val MY_REVIEW_HINTS = setOf(
            "내가 검토",
            "검토해야",
            "review for me",
            "needs review",
            "리뷰가 필요한",
            "검토가 필요한"
        )
        private val MISSING_ASSIGNEE_HINTS = setOf("담당자가 없는", "담당자 없는", "미할당", "unassigned", "assignee 없는")
        private val VALIDATE_HINTS = setOf("validate", "검증", "유효성")
        private val SCHEMA_HINTS = setOf("schema", "스키마", "model", "dto")
        private val DETAIL_HINTS = setOf("detail", "상세", "parameter", "response", "security")
        private val SUMMARY_HINTS = setOf("summary", "summarize", "요약", "정리")
        private val LIST_HINTS = setOf("loaded specs", "list specs", "목록", "list")
        private val LOADED_HINTS = setOf("loaded", "로드된", "현재 로드된")
        private val REMOVE_HINTS = setOf("remove", "삭제")
        private val WRONG_ENDPOINT_HINTS = setOf("wrong endpoint", "invalid endpoint", "잘못된 endpoint", "없는 endpoint")
    }
}
