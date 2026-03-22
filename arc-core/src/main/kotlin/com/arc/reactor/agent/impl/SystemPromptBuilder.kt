package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
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
internal class SystemPromptBuilder(
    private val postProcessor: SystemPromptPostProcessor? = null
) {

    /**
     * 프롬프트 분류 시 반복되는 lowercase() 호출을 캐싱한다.
     * 동일 입력에 대해 30회+ 호출되는 것을 1회로 줄인다.
     * == (동등성) 비교로 JSON 역직렬화 등 다른 인스턴스도 캐시 히트한다.
     */
    @Volatile
    private var promptCache: Pair<String, String>? = null  // (original, normalized)

    private fun normalizePrompt(prompt: String?): String {
        if (prompt.isNullOrBlank()) return ""
        val cached = promptCache
        if (cached != null && cached.first == prompt) return cached.second
        val normalized = prompt.lowercase()
        promptCache = prompt to normalized
        return normalized
    }

    /** 프롬프트가 주어진 키워드 집합 중 하나라도 포함하는지 검사한다. */
    private fun matchesHints(prompt: String?, vararg hintSets: Set<String>): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = normalizePrompt(prompt)
        return hintSets.any { hints -> hints.any { normalized.contains(it) } }
    }

    /**
     * 시스템 프롬프트를 조합한다.
     *
     * @param basePrompt 기본 시스템 프롬프트
     * @param ragContext RAG 검색 결과 텍스트 (없으면 null)
     * @param responseFormat 응답 형식 (TEXT, JSON, YAML)
     * @param responseSchema JSON/YAML 스키마 (있는 경우)
     * @param userPrompt 사용자 프롬프트 (도구 호출 강제 판단용)
     * @param workspaceToolAlreadyCalled workspace 도구가 이미 호출되었는지 여부
     * @param userMemoryContext 사용자 메모리 컨텍스트 (UserMemoryInjectionHook에서 주입, 없으면 null)
     * @return 조합된 최종 시스템 프롬프트
     */
    fun build(
        basePrompt: String,
        ragContext: String?,
        responseFormat: ResponseFormat = ResponseFormat.TEXT,
        responseSchema: String? = null,
        userPrompt: String? = null,
        workspaceToolAlreadyCalled: Boolean = false,
        userMemoryContext: String? = null
    ): String {
        val effectiveBase = if (userMemoryContext != null) {
            "$basePrompt\n\n[User Context]\n$userMemoryContext"
        } else {
            basePrompt
        }
        val parts = mutableListOf(effectiveBase)
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

    /**
     * 계획 단계 전용 시스템 프롬프트를 조합한다.
     *
     * PLAN_EXECUTE 모드의 1단계(계획 생성)에서 LLM에게 도구 호출 순서를
     * JSON 배열로만 출력하도록 지시한다. 실행은 하지 않는다.
     *
     * @param userPrompt 사용자 프롬프트
     * @param toolDescriptions 사용 가능한 도구 이름과 설명 목록
     * @return 계획 단계 전용 시스템 프롬프트
     */
    fun buildPlanningPrompt(
        userPrompt: String,
        toolDescriptions: String
    ): String = buildString {
        appendPlanningRole()
        appendPlanningToolContext(toolDescriptions)
        appendPlanningOutputSchema()
        appendPlanningConstraints()
        appendPlanningUserRequest(userPrompt)
    }

    /** 계획 단계 역할 지시 — 계획만 세우고 실행하지 않음을 명시한다. */
    private fun StringBuilder.appendPlanningRole() {
        append("[Role]\n")
        append("당신은 도구 호출 계획을 세우는 플래너입니다.\n")
        append("사용자의 요청을 분석하고, ")
        append("필요한 도구 호출 순서를 JSON으로 출력하세요.\n")
        append("절대 도구를 직접 실행하지 마세요. ")
        append("계획만 출력합니다.\n")
    }

    /** 계획 단계 도구 컨텍스트 — 사용 가능한 도구 목록을 포함한다. */
    private fun StringBuilder.appendPlanningToolContext(
        toolDescriptions: String
    ) {
        append("\n[Available Tools]\n")
        append("아래 도구만 계획에 포함할 수 있습니다.\n")
        append("목록에 없는 도구는 사용할 수 없습니다.\n\n")
        append(toolDescriptions)
        append("\n")
    }

    /** 계획 출력 스키마 — JSON 배열 형식을 지정한다. */
    private fun StringBuilder.appendPlanningOutputSchema() {
        append("\n[Output Format]\n")
        append("반드시 JSON 배열만 출력하세요. ")
        append("다른 텍스트, 설명, 마크다운은 금지합니다.\n")
        append("각 단계는 다음 필드를 포함합니다:\n")
        append("- tool: 도구 이름 (Available Tools에 있는 것만)\n")
        append("- args: 도구에 전달할 인자 (객체)\n")
        append("- description: 이 단계의 목적 (간단한 한국어 설명)\n\n")
        append("예시:\n")
        append("[{\"tool\":\"jira_get_issue\",")
        append("\"args\":{\"issueKey\":\"JAR-36\"},")
        append("\"description\":\"이슈 상세 조회\"},\n")
        append(" {\"tool\":\"confluence_search\",")
        append("\"args\":{\"query\":\"온보딩 가이드\"},")
        append("\"description\":\"관련 문서 검색\"}]\n")
    }

    /** 계획 제약 조건 — 실행 금지, 빈 배열 허용 등. */
    private fun StringBuilder.appendPlanningConstraints() {
        append("\n[Constraints]\n")
        append("1. 도구가 필요 없으면 빈 배열 []을 반환하세요.\n")
        append("2. 단계 순서는 실행 순서입니다. ")
        append("의존 관계를 고려하세요.\n")
        append("3. 동일 도구를 다른 인자로 여러 번 호출할 수 있습니다.\n")
        append("4. 각 단계의 args는 해당 도구의 입력 스키마에 맞춰야 합니다.\n")
        append("5. 응답은 [ 로 시작하고 ] 로 끝나야 합니다.\n")
    }

    /** 계획 단계 사용자 요청 — 분석 대상 프롬프트를 포함한다. */
    private fun StringBuilder.appendPlanningUserRequest(userPrompt: String) {
        append("\n[User Request]\n")
        append(userPrompt)
        append("\n")
    }

    // ── Grounding 규칙 조합 ──

    /**
     * Grounding 규칙 지시문을 구성한다.
     *
     * 사용자 프롬프트를 분석하여 워크스페이스 관련 여부를 판별한다.
     * 비워크스페이스 쿼리(수학, 코딩 등)에서는 워크스페이스 규칙을 생략하여 ~700토큰을 절약한다.
     */
    private fun buildGroundingInstruction(
        responseFormat: ResponseFormat,
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ): String = buildString {
        appendLanguageRule()
        appendConversationHistoryRule()
        val workspaceRelated = workspaceToolAlreadyCalled ||
            looksLikeWorkspacePrompt(userPrompt)
        if (workspaceRelated) {
            appendWorkspaceGroundingRules(workspaceToolAlreadyCalled)
            appendFewShotReadOnlyExamples()
            appendCompoundQuestionHint(workspaceToolAlreadyCalled)
            appendReadOnlyPolicy()
            appendToolErrorRetryHint()
            appendConfluencePreferenceHint()
            appendMutationRefusal(userPrompt)
            appendConfluenceToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendWorkToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendWorkContextToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendJiraToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendBitbucketToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendSwaggerToolForcing(userPrompt, workspaceToolAlreadyCalled)
            appendSourcesInstruction(responseFormat, userPrompt)
        } else {
            appendGeneralGroundingRule()
        }
    }

    /** 언어 규칙 — 모든 요청에 항상 포함한다. */
    private fun StringBuilder.appendLanguageRule() {
        append("[Language Rule]\n")
        append("ALWAYS respond in Korean (한국어). ")
        append("Even if the user writes in English or mixed languages, ")
        append("your response must be in Korean.\n")
        append("Technical terms (e.g. Jira, Sprint, API) may remain in English, ")
        append("but all sentences and explanations must be in Korean.\n")
    }

    /** 대화 히스토리 규칙 — 모든 요청에 항상 포함한다. */
    private fun StringBuilder.appendConversationHistoryRule() {
        append("\n[Conversation History]\n")
        append("You have access to the conversation history from this session.\n")
        append("When the user refers to something mentioned earlier ")
        append("(name, preference, prior question), ")
        append("use the conversation history to answer.\n")
        append("Do NOT say \"I cannot remember\" or ")
        append("\"I don't collect personal information\" — ")
        append("the user explicitly provided this information ")
        append("in the current session.\n")
        append("The conversation history is part of the current ")
        append("session context, not personal data collection.")
    }

    /** 일반(비워크스페이스) 질문 전용 규칙. */
    private fun StringBuilder.appendGeneralGroundingRule() {
        append("\n\n[Grounding Rules]\n")
        append("Answer directly from your knowledge. No tools needed.\n")
        append("Do NOT say '도구를 찾을 수 없습니다' for general knowledge questions.")
    }

    /** 워크스페이스 Grounding 규칙 (사실 기반 응답, 도구 호출 강제). */
    private fun StringBuilder.appendWorkspaceGroundingRules(
        workspaceToolAlreadyCalled: Boolean
    ) {
        append("\n\n[Grounding Rules]\n")
        append("There are two types of questions:\n")
        append("1. GENERAL questions (math, coding, concepts, explanations)")
        append(" → answer from your knowledge. No tools needed.\n")
        append("2. WORKSPACE questions (Jira issues, Confluence pages, ")
        append("Bitbucket, project status, team tasks)")
        append(" → MUST call tools first.\n")
        append("For workspace questions: call the relevant tool FIRST, ")
        append("then answer based on tool results.\n")
        append("For general questions: answer directly without tools. ")
        append("Do NOT say '도구를 찾을 수 없습니다' for general knowledge questions.\n")
        append("NEVER ask clarifying questions for work-related queries ")
        append("(e.g. 'which project?', 'which week?'). ")
        append("Instead, use sensible defaults and call the tool immediately.\n")
        append("Example: 'show me backlog issues' → call jira_search_issues. ")
        append("'주간 리포트' → search this week's issues and write the report.\n")
        append("Analyzing, summarizing, recommending, reporting, planning sprints, ")
        append("writing retrospectives are READ operations — ")
        append("they are NOT write operations. Do NOT refuse them as read-only.\n")
        append("Treat mixed-language queries (e.g. 'JAR project의 최근 issues') ")
        append("the same as pure Korean or English queries.\n")
        if (workspaceToolAlreadyCalled) {
            append("A required workspace tool has already been executed ")
            append("for this request.\n")
            append("Answer directly from the retrieved tool results.\n")
            append("Do not emit planning syntax such as ")
            append("```tool_code``` or raw tool JSON.\n")
        } else {
            append("Your FIRST action for work-related queries must be a ")
            append("tool call, not prose or a clarifying question.\n")
            append("If the user mentions a project (e.g. JAR), use it. ")
            append("If not, search across all allowed projects.\n")
            append("Default tools: jira_search_issues for Jira queries, ")
            append("confluence_search for Confluence, ")
            append("work_morning_briefing for general status.\n")
            appendFewShotExamples()
        }
    }

    /** 읽기 전용 정책 — 워크스페이스 쿼리에서만 포함한다. */
    private fun StringBuilder.appendReadOnlyPolicy() {
        append("If a Jira, Confluence, Bitbucket, or work-management request ")
        append("asks to create, update, assign, reassign, ")
        append("comment, approve, transition, convert, or delete something, ")
        append("refuse it as not allowed in read-only mode.\n")
        append("NEVER include curl, wget, fetch, httpie, or direct HTTP ")
        append("request examples that target ")
        append("Jira, Confluence, Bitbucket, or any workspace API ")
        append("in your response. ")
        append("Even if the user asks for a workaround, do not provide ")
        append("API call instructions that would bypass ")
        append("read-only restrictions.\n")
    }

    /** 도구 에러 재시도 힌트 — 워크스페이스 쿼리에서만 포함한다. */
    private fun StringBuilder.appendToolErrorRetryHint() {
        append("\n[Tool Error Retry]\n")
        append("When a tool returns an error ")
        append("(e.g. \"Error: JQL query failed\", \"Error: invalid field\"), ")
        append("analyze the error message and retry with corrected parameters.\n")
        append("Do NOT report the raw error to the user. ")
        append("Simplify the query and try again.\n")
        append("For JQL errors: remove advanced functions ")
        append("(startOfWeek, startOfMonth, endOfWeek, endOfMonth), ")
        append("use simple date comparisons like 'created >= -7d' instead.\n")
        append("You have up to 10 tool calls per request — use retries wisely.\n")
    }

    /** Confluence 도구 우선순위 힌트 — 워크스페이스 쿼리에서만 포함한다. */
    private fun StringBuilder.appendConfluencePreferenceHint() {
        append("Prefer `confluence_answer_question` for Confluence policy, ")
        append("wiki, service, or page-summary questions.")
        append("\nDo not answer Confluence knowledge questions from ")
        append("`confluence_search` or `confluence_search_by_text` alone; ")
        append("use them only for discovery, then verify with ")
        append("`confluence_answer_question` or `confluence_get_page_content`.")
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

    /**
     * 복합 질문 처리 힌트 — 하나의 메시지에 여러 주제가 포함된 경우
     * 각 하위 질문에 대해 별도 도구를 호출하도록 안내한다.
     */
    private fun StringBuilder.appendCompoundQuestionHint(
        workspaceToolAlreadyCalled: Boolean
    ) {
        if (workspaceToolAlreadyCalled) return
        append("\n[Compound Questions]\n")
        append("When the user asks about MULTIPLE topics in one message ")
        append("(indicators: 와/과, 그리고, 동시에, 함께, 또한, ")
        append("multiple question marks, comma-separated requests):\n")
        append("1. Identify each sub-question separately\n")
        append("2. Call the appropriate tool for EACH sub-question\n")
        append("3. Combine all results into one comprehensive response\n")
        append("4. Do NOT stop after answering only the first part\n")
        append("Example: 'JAR-36 이슈 상태와 온보딩 가이드 문서를 찾아줘'\n")
        append("→ call jira_get_issue(JAR-36) AND ")
        append("confluence_search_by_text(온보딩 가이드)\n")
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
        if (matchesHints(userPrompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
            matchesHints(userPrompt, CONFLUENCE_DISCOVERY_HINTS)
        ) {
            append("\nFor this request, you MUST call `confluence_search_by_text` before answering.")
            append(" If the user asks for a list of matching pages, respond from search results and include the returned links.")
        }
        if (matchesHints(userPrompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
            matchesHints(userPrompt, CONFLUENCE_PAGE_BODY_HINTS)
        ) {
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
        if (matchesHints(userPrompt, WORK_STANDUP_HINTS)) {
            append("\nFor this request, you MUST call `work_prepare_standup_update` before answering.")
            append(" Use default profile settings when optional parameters are omitted and do not ask follow-up questions before the first tool call.")
        }
    }

    /** 릴리즈 위험 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkReleaseRiskForcing(userPrompt: String?) {
        if (matchesHints(userPrompt, WORK_RELEASE_RISK_HINTS)) {
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
        if (matchesHints(userPrompt, WORK_RELEASE_READINESS_HINTS)) {
            append("\nFor this request, you MUST call `work_release_readiness_pack` before answering.")
            append(" Use the provided defaults where possible and do not assemble the pack manually.")
            append(" Use preview mode in read-only workspaces and do not refuse unless the user explicitly asks to write data.")
        }
    }

    /** 개인 생산성 도구 (focus, learning, interrupt, wrapup) 강제 호출 지시. */
    private fun StringBuilder.appendWorkPersonalToolForcing(userPrompt: String?) {
        if (matchesHints(userPrompt, WORK_PERSONAL_FOCUS_HINTS)) {
            append("\nFor this request, you MUST call `work_personal_focus_plan` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (matchesHints(userPrompt, WORK_PERSONAL_LEARNING_HINTS)) {
            append("\nFor this request, you MUST call `work_personal_learning_digest` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (matchesHints(userPrompt, WORK_PERSONAL_INTERRUPT_HINTS)) {
            append("\nFor this request, you MUST call `work_personal_interrupt_guard` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
        if (matchesHints(userPrompt, WORK_PERSONAL_WRAPUP_HINTS)) {
            append("\nFor this request, you MUST call `work_personal_end_of_day_wrapup` before answering.")
            append(" Use the default profile and defaults when optional parameters are omitted.")
            append(" Do not ask follow-up questions before the first tool call.")
        }
    }

    /** 프로필 목록 및 소유자 조회 도구 강제 호출 지시. */
    private fun StringBuilder.appendWorkProfileAndOwnerForcing(userPrompt: String?) {
        if (matchesHints(userPrompt, WORK_BRIEFING_PROFILE_HINTS)) {
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
            matchesHints(userPrompt, JIRA_HINTS) &&
                matchesHints(userPrompt, PROJECT_LIST_HINTS) -> appendToolForcing(
                "`jira_list_projects`", " Do not answer from prior knowledge."
            )
            looksLikeJiraIssueTransitionPrompt(userPrompt) -> appendToolForcing(
                "`jira_get_transitions`", " Do not guess the available states."
            )
            looksLikeJiraIssuePrompt(userPrompt) -> appendToolForcing(
                "`jira_get_issue`", " Do not answer from prior knowledge."
            )
            matchesHints(userPrompt, JIRA_HINTS) &&
                matchesHints(userPrompt, DUE_SOON_HINTS) -> appendToolForcing(
                "`jira_due_soon_issues`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, JIRA_HINTS) &&
                matchesHints(userPrompt, BLOCKER_HINTS) -> appendToolForcing(
                "`jira_blocker_digest`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, JIRA_HINTS) &&
                matchesHints(userPrompt, DAILY_BRIEFING_HINTS) -> appendToolForcing(
                "`jira_daily_briefing`",
                " Infer the Jira project key from the user message and do not ask follow-up questions before the first tool call."
            )
            looksLikeJiraProjectSummaryPrompt(userPrompt) -> appendToolForcing(
                "`jira_search_issues`",
                " Use the obvious project key from the user message and summarize the returned issues with source links."
            )
            matchesHints(userPrompt, JIRA_HINTS) &&
                matchesHints(userPrompt, SEARCH_HINTS) -> {
                append("\nFor this request, you MUST call `jira_search_by_text` or `jira_search_issues` before answering.")
                append(" Prefer `jira_search_by_text` when the user gives a keyword, and `jira_search_issues` for project-scoped searches.")
            }
            matchesHints(userPrompt, AGILE_WORKFLOW_HINTS) -> {
                append("\nFor this request, you MUST call `jira_search_issues` before answering.")
                append(" This is a READ-ONLY analysis task (backlog analysis, sprint planning, retrospective, kanban).")
                append(" Search issues first, then analyze and recommend based on the results.")
            }
            matchesHints(userPrompt, JIRA_HINTS) -> {
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
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, REVIEW_SLA_HINTS) -> appendToolForcing(
                "`bitbucket_review_sla_alerts`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, REVIEW_RISK_HINTS) -> {
                append("\nFor this request, you MUST call `bitbucket_review_sla_alerts` or `bitbucket_review_queue` before answering.")
                append(" Prefer `bitbucket_review_sla_alerts` for risk summaries and `bitbucket_review_queue` for reviewer backlog.")
            }
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, MY_REVIEW_HINTS) -> appendToolForcing(
                "`bitbucket_review_queue`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, REVIEW_QUEUE_HINTS) -> appendToolForcing(
                "`bitbucket_review_queue`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, STALE_HINTS) -> appendToolForcing(
                "`bitbucket_stale_prs`",
                " Use the default stale threshold when the user omits it and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, BRANCH_HINTS) -> appendToolForcing(
                "`bitbucket_list_branches`",
                " Use default workspace/repository values when the user omits them and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) &&
                matchesHints(userPrompt, REPOSITORY_HINTS) -> appendToolForcing(
                "`bitbucket_list_repositories`",
                " Use the accessible workspace defaults and do not ask follow-up questions before the first tool call."
            )
            matchesHints(userPrompt, BITBUCKET_HINTS) -> appendToolForcing(
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
            looksLikeSwaggerPrompt(userPrompt) && !hasSwaggerUrl(userPrompt) &&
                matchesHints(userPrompt, LOADED_HINTS) &&
                matchesHints(userPrompt, SUMMARY_HINTS) -> appendToolChainForcing(
                "`spec_list`", "`spec_summary`", " Do not answer from `spec_list` alone."
            )
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, WRONG_ENDPOINT_HINTS) -> appendToolForcing(
                "`spec_search`",
                " Use the endpoint fragment from the user request and explain the no-match result if nothing is found."
            )
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, LIST_HINTS) -> appendToolForcing("`spec_list`", "")
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, VALIDATE_HINTS) -> appendToolForcing("`spec_validate`", "")
            looksLikeSwaggerPrompt(userPrompt) && !hasSwaggerUrl(userPrompt) &&
                matchesHints(userPrompt, SCHEMA_HINTS) -> appendLoadedSpecForcing("`spec_schema`")
            looksLikeSwaggerPrompt(userPrompt) && !hasSwaggerUrl(userPrompt) &&
                matchesHints(userPrompt, DETAIL_HINTS) -> appendLoadedSpecForcing("`spec_detail`")
            looksLikeSwaggerPrompt(userPrompt) && !hasSwaggerUrl(userPrompt) &&
                matchesHints(userPrompt, SEARCH_HINTS) -> appendLoadedSpecForcing("`spec_search`")
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, SCHEMA_HINTS) -> appendToolChainForcing("`spec_load`", "`spec_schema`", "")
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, DETAIL_HINTS) -> appendToolChainForcing("`spec_load`", "`spec_detail`", "")
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, SEARCH_HINTS) -> appendToolChainForcing("`spec_load`", "`spec_search`", "")
            looksLikeSwaggerPrompt(userPrompt) &&
                matchesHints(userPrompt, REMOVE_HINTS) -> appendToolForcing("`spec_remove`", "")
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

    // ── 복합 로직이 있는 프롬프트 분류 함수 ──

    /** Confluence 답변 요청: knowledge + answer 힌트가 모두 있고 discovery가 아닌 경우. */
    private fun looksLikeConfluenceAnswerPrompt(prompt: String?): Boolean {
        return matchesHints(prompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
            matchesHints(prompt, CONFLUENCE_ANSWER_HINTS) &&
            !(matchesHints(prompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
                matchesHints(prompt, CONFLUENCE_DISCOVERY_HINTS))
    }

    /** 브리핑 요청: 회의 준비 키워드 제외 후 브리핑 힌트 매칭. */
    private fun looksLikeWorkBriefingPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return !matchesHints(prompt, MEETING_PREP_EXCLUDE_HINTS) &&
            matchesHints(prompt, WORK_BRIEFING_HINTS)
    }

    /** 혼합 우선순위 요청: priority + blocker + (review queue 또는 review SLA) 모두 필요. */
    private fun looksLikeHybridPriorityPrompt(prompt: String?): Boolean {
        return matchesHints(prompt, HYBRID_PRIORITY_HINTS) &&
            matchesHints(prompt, BLOCKER_HINTS) &&
            (matchesHints(prompt, REVIEW_QUEUE_HINTS) || matchesHints(prompt, REVIEW_SLA_HINTS))
    }

    /** 소유자 조회 요청: 미할당 힌트가 없고 소유자 힌트가 있는 경우. */
    private fun looksLikeWorkOwnerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return !matchesHints(prompt, MISSING_ASSIGNEE_HINTS) &&
            matchesHints(prompt, WORK_OWNER_HINTS)
    }

    /** 작업 항목 컨텍스트 요청: 이슈 키 + 컨텍스트 힌트. */
    private fun looksLikeWorkItemContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) &&
            matchesHints(prompt, WORK_ITEM_CONTEXT_HINTS)
    }

    /** 서비스 컨텍스트 요청: "service"/"서비스" + 서비스 컨텍스트 힌트. */
    private fun looksLikeWorkServiceContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = normalizePrompt(prompt)
        val hasServiceMention = normalized.contains("service") || normalized.contains("서비스")
        return hasServiceMention && matchesHints(prompt, WORK_SERVICE_CONTEXT_HINTS)
    }

    /** 이슈 키 + 상태 전이 힌트. */
    private fun looksLikeJiraIssueTransitionPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) &&
            matchesHints(prompt, TRANSITION_HINTS)
    }

    /** 이슈 키만 포함된 경우 (힌트 셋 없이 정규식만 검사). */
    private fun looksLikeJiraIssuePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
    }

    /** Jira 프로젝트 요약: Jira + 요약 힌트 + 다른 세부 분류 제외. */
    private fun looksLikeJiraProjectSummaryPrompt(prompt: String?): Boolean {
        return matchesHints(prompt, JIRA_HINTS) &&
            matchesHints(prompt, JIRA_PROJECT_SUMMARY_HINTS) &&
            !matchesHints(prompt, DUE_SOON_HINTS) &&
            !matchesHints(prompt, BLOCKER_HINTS) &&
            !matchesHints(prompt, DAILY_BRIEFING_HINTS) &&
            !matchesHints(prompt, SEARCH_HINTS) &&
            !matchesHints(prompt, PROJECT_LIST_HINTS)
    }

    /** Swagger/OpenAPI 요청: URL 정규식 또는 Swagger 힌트. */
    private fun looksLikeSwaggerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) ||
            matchesHints(prompt, SWAGGER_HINTS)
    }

    /** Swagger URL이 포함된 경우. */
    private fun hasSwaggerUrl(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return OPENAPI_URL_REGEX.containsMatchIn(prompt)
    }

    /** workspace 관련 프롬프트인지 통합 판단한다. */
    private fun looksLikeWorkspacePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return matchesHints(prompt, JIRA_HINTS) ||
            matchesHints(prompt, BITBUCKET_HINTS) ||
            matchesHints(prompt, AGILE_WORKFLOW_HINTS) ||
            looksLikeSwaggerPrompt(prompt) ||
            looksLikeConfluenceAnswerPrompt(prompt) ||
            (matchesHints(prompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
                matchesHints(prompt, CONFLUENCE_DISCOVERY_HINTS)) ||
            (matchesHints(prompt, CONFLUENCE_KNOWLEDGE_HINTS) &&
                matchesHints(prompt, CONFLUENCE_PAGE_BODY_HINTS)) ||
            looksLikeWorkBriefingPrompt(prompt) ||
            matchesHints(prompt, WORK_STANDUP_HINTS) ||
            matchesHints(prompt, WORK_RELEASE_RISK_HINTS) ||
            matchesHints(prompt, WORK_RELEASE_READINESS_HINTS) ||
            matchesHints(prompt, WORK_BRIEFING_PROFILE_HINTS) ||
            matchesHints(prompt, WORK_PERSONAL_FOCUS_HINTS) ||
            matchesHints(prompt, WORK_PERSONAL_LEARNING_HINTS) ||
            matchesHints(prompt, WORK_PERSONAL_INTERRUPT_HINTS) ||
            matchesHints(prompt, WORK_PERSONAL_WRAPUP_HINTS) ||
            looksLikeWorkOwnerPrompt(prompt) ||
            looksLikeWorkItemContextPrompt(prompt) ||
            looksLikeWorkServiceContextPrompt(prompt)
    }

    // ── 프롬프트 분류를 위한 힌트 키워드 집합 (한국어/영어) ──
    // 공유 힌트 셋은 WorkContextPatterns에서 참조, 이 클래스 전용 힌트만 companion에 정의
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
        private val WORK_BRIEFING_PROFILE_HINTS = setOf(
            "briefing profile", "profile list", "profiles", "브리핑 프로필", "프로필 목록"
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
        private val DAILY_BRIEFING_HINTS = setOf(
            "daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest",
            "오늘의 jira 브리핑", "오늘 jira 브리핑"
        )
        private val JIRA_PROJECT_SUMMARY_HINTS = setOf(
            "recent", "latest", "summary", "summarize", "최근", "요약", "정리", "브리핑"
        )
        private val SEARCH_HINTS = setOf("search", "찾아", "검색", "look up", "find")
        private val TRANSITION_HINTS = setOf("transition", "상태 전이", "전이", "possible states")
        private val REPOSITORY_HINTS = setOf("repository", "repo", "저장소")
        private val BRANCH_HINTS = setOf("branch", "브랜치")
        private val STALE_HINTS = setOf("stale", "오래된", "방치된")
        private val MY_REVIEW_HINTS = setOf(
            "내가 검토", "검토해야", "review for me", "needs review",
            "리뷰가 필요한", "검토가 필요한"
        )
        private val SCHEMA_HINTS = setOf("schema", "스키마", "model", "dto")
        private val DETAIL_HINTS = setOf("detail", "상세", "parameter", "response", "security")
        private val LIST_HINTS = setOf("loaded specs", "list specs", "목록", "list")
        private val LOADED_HINTS = setOf("loaded", "로드된", "현재 로드된")
        private val REMOVE_HINTS = setOf("remove", "삭제")

        // WorkContextPatterns 공유 힌트 참조 (17쌍)
        private val BLOCKER_HINTS = WorkContextPatterns.BLOCKER_HINTS
        private val REVIEW_QUEUE_HINTS = WorkContextPatterns.REVIEW_QUEUE_HINTS
        private val REVIEW_SLA_HINTS = WorkContextPatterns.REVIEW_SLA_HINTS
        private val REVIEW_RISK_HINTS = WorkContextPatterns.REVIEW_RISK_HINTS
        private val HYBRID_PRIORITY_HINTS = WorkContextPatterns.HYBRID_PRIORITY_HINTS
        private val WORK_RELEASE_READINESS_HINTS = WorkContextPatterns.WORK_RELEASE_READINESS_HINTS
        private val WORK_PERSONAL_FOCUS_HINTS = WorkContextPatterns.WORK_PERSONAL_FOCUS_HINTS
        private val WORK_PERSONAL_LEARNING_HINTS = WorkContextPatterns.WORK_PERSONAL_LEARNING_HINTS
        private val WORK_PERSONAL_INTERRUPT_HINTS = WorkContextPatterns.WORK_PERSONAL_INTERRUPT_HINTS
        private val WORK_PERSONAL_WRAPUP_HINTS = WorkContextPatterns.WORK_PERSONAL_WRAPUP_HINTS
        private val WORK_OWNER_HINTS = WorkContextPatterns.WORK_OWNER_HINTS
        private val MISSING_ASSIGNEE_HINTS = WorkContextPatterns.MISSING_ASSIGNEE_HINTS
        private val WORK_ITEM_CONTEXT_HINTS = WorkContextPatterns.WORK_ITEM_CONTEXT_HINTS
        private val WORK_SERVICE_CONTEXT_HINTS = WorkContextPatterns.WORK_SERVICE_CONTEXT_HINTS
        private val VALIDATE_HINTS = WorkContextPatterns.VALIDATE_HINTS
        private val SUMMARY_HINTS = WorkContextPatterns.SUMMARY_HINTS
        private val WRONG_ENDPOINT_HINTS = WorkContextPatterns.WRONG_ENDPOINT_HINTS
    }
}
