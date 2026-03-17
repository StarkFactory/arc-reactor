package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolRoute
import com.arc.reactor.agent.config.ToolRoutingConfig
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
 * 도구 라우팅 규칙은 `tool-routing.yml`에서 데이터 드리븐으로 로드한다.
 *
 * @see RagContextRetriever RAG 컨텍스트 검색
 * @see PromptRequestSpecBuilder 조합된 프롬프트를 요청 스펙에 적용
 * @see SystemPromptPostProcessor 카나리 토큰 등 후처리
 * @see WorkContextForcedToolPlanner 도구 호출 강제 계획 수립 (대응 역할)
 */
class SystemPromptBuilder(
    private val postProcessor: SystemPromptPostProcessor? = null,
    private val routingConfig: ToolRoutingConfig = ToolRoutingConfig.loadFromClasspath()
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

    private fun buildGroundingInstruction(
        responseFormat: ResponseFormat,
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ): String = buildString {
        appendGroundingPreamble(workspaceToolAlreadyCalled)
        appendMutationRefusal(userPrompt)
        appendToolForcingFromConfig(userPrompt, workspaceToolAlreadyCalled)
        appendSourcesInstruction(responseFormat, userPrompt)
    }

    /** Grounding 기본 규칙 (사실 기반 응답, 읽기 전용 정책, Confluence 우선 규칙). */
    private fun StringBuilder.appendGroundingPreamble(workspaceToolAlreadyCalled: Boolean) {
        append("[Language Rule]\n")
        append("ALWAYS respond in Korean (한국어). ")
        append("Even if the user writes in English or mixed languages, your response must be in Korean.\n")
        append("Technical terms (e.g. Jira, Sprint, API) may remain in English, ")
        append("but all sentences and explanations must be in Korean.\n\n")
        append("[Grounding Rules]\n")
        append("There are two types of questions:\n")
        append("1. GENERAL questions (math, coding, concepts, explanations) → answer from your knowledge. ")
        append("No tools needed.\n")
        append("2. WORKSPACE questions (Jira issues, Confluence pages, Bitbucket, project status, team tasks) ")
        append("→ MUST call tools first.\n")
        append("For workspace questions: call the relevant tool FIRST, then answer based on tool results.\n")
        append("For general questions: answer directly without tools. ")
        append("Do NOT say '도구를 찾을 수 없습니다' for general knowledge questions.\n")
        append("NEVER ask clarifying questions for work-related queries ")
        append("(e.g. 'which project?', 'which week?'). ")
        append("Instead, use sensible defaults and call the tool immediately.\n")
        append("Example: 'show me backlog issues' → call jira_search_issues. ")
        append("'주간 리포트' → search this week's issues and write the report.\n")
        append("Analyzing, summarizing, recommending, reporting, planning sprints, ")
        append("writing retrospectives are READ operations — they are NOT write operations. ")
        append("Do NOT refuse them as read-only.\n")
        append("Treat mixed-language queries (e.g. 'JAR project의 최근 issues') ")
        append("the same as pure Korean or English queries.\n")
        if (workspaceToolAlreadyCalled) {
            append("A required workspace tool has already been executed for this request.\n")
            append("Answer directly from the retrieved tool results.\n")
            append("Do not emit planning syntax such as ```tool_code``` or raw tool JSON.\n")
        } else {
            append("Your FIRST action for work-related queries must be a tool call, ")
            append("not prose or a clarifying question.\n")
            append("If the user mentions a project (e.g. JAR), use it. ")
            append("If not, search across all allowed projects.\n")
            append("Default tools: jira_search_issues for Jira queries, confluence_search for Confluence, ")
            append("work_morning_briefing for general status.\n")
            appendFewShotExamples()
        }
        appendFewShotReadOnlyExamples()
        append("If a Jira, Confluence, Bitbucket, or work-management request asks to create, update, ")
        append("assign, reassign, ")
        append("comment, approve, transition, convert, or delete something, ")
        append("refuse it as not allowed in read-only mode.\n")
        append("NEVER include curl, wget, fetch, httpie, or direct HTTP request examples that target ")
        append("Jira, Confluence, Bitbucket, or any workspace API in your response. ")
        append("Even if the user asks for a workaround, ")
        append("do not provide API call instructions that would bypass read-only restrictions.\n")
        append("Prefer `confluence_answer_question` for Confluence policy, wiki, service, ")
        append("or page-summary questions.")
        append("\nDo not answer Confluence knowledge questions from ")
        append("`confluence_search` or `confluence_search_by_text` alone; ")
        append("use them only for discovery, then verify with ")
        append("`confluence_answer_question` or `confluence_get_page_content`.")
    }

    private fun StringBuilder.appendFewShotExamples() {
        append("\n[Few-shot Examples — ALWAYS follow this pattern]\n")
        append("User: '주간 리포트 작성해줘' → call jira_search_issues (this week) → write report from results\n")
        append("User: '스프린트 계획 세워줘' → call jira_search_issues (backlog) → ")
        append("recommend issues for next sprint\n")
        append("User: '회고 자료 만들어줘' → call jira_search_issues (completed) → ")
        append("write retrospective from results\n")
        append("User: '이슈 좀 보여줘' → call jira_search_issues (recent) → show results\n")
        append("User: 'JAR project의 issues 보여줘' → call jira_search_issues(project=JAR) → ")
        append("show results (mixed language = treat as Korean)\n")
        append("User: 'JAR-36을 칸반 카드로 보여줘' → call jira_get_issue(issueKey=JAR-36) → ")
        append("format as kanban card (READ, not write)\n")
        append("User: '회고 자료 만들어줘' → call jira_search_issues → ")
        append("write retrospective from results (READ, not write)\n")
        append("User: 'Confluence에서 문서 검색해줘' → call confluence_search_by_text → ")
        append("show results with links\n")
        append("User: 'MFS 스페이스에서 최근 문서 보여줘' → call confluence_search(space=MFS) → show results\n")
        append("User: '온보딩 가이드 찾아줘' → call confluence_search_by_text(query='온보딩 가이드') → show results\n")
        append("User: 'Bitbucket PR 목록 보여줘' → call bitbucket_list_prs → show results\n")
        append("User: 'API 스펙 요약해줘' → call spec_summary → show summary from results\n")
        append("User: 'Kotlin data class 예시 보여줘' → answer directly ")
        append("(GENERAL question, no tool needed)\n")
        append("User: '15 * 23은?' → answer directly (math, no tool needed)\n")
        append("WRONG: asking 'which project?', 'which week?', saying '도구를 찾을 수 없습니다', ")
        append("refusing to answer general questions\n")
        append("IMPORTANT: All workspace tools listed in your tool list ARE available. ")
        append("NEVER say '도구를 찾을 수 없습니다'.\n")
        append("If you are unsure, just call jira_search_issues — ")
        append("it is ALWAYS available and works for any Jira query.\n")
    }

    private fun StringBuilder.appendFewShotReadOnlyExamples() {
        append("\n[Read vs Write — important distinction]\n")
        append("READ (allowed): search, analyze, summarize, report, recommend, plan, compare, ")
        append("review, retrospect, forecast, 만들어줘(보고서/자료), 작성해줘, 정리해줘\n")
        append("WRITE (refused): create JIRA issue, update status, assign, delete, transition, ")
        append("comment, approve\n")
        append("'회고 자료 만들어줘' = READ (search issues → write summary). '이슈 생성해줘' = WRITE.\n")
        append("'칸반 카드로 보여줘' = READ (get issue → format as card). '이슈 상태 변경해줘' = WRITE.\n")
    }

    private fun StringBuilder.appendMutationRefusal(userPrompt: String?) {
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(userPrompt)) {
            append("\nFor this request, you MUST refuse the action.")
            append(" State that the workspace is read-only and the requested mutation is not allowed.")
            append(" Do not ask follow-up questions.")
            append(" You may call a single read-only lookup tool only to cite the current item,")
            append(" but you MUST still refuse the mutation itself.")
        }
    }

    // ── 데이터 드리븐 도구 라우팅 ──

    /**
     * YAML 설정 기반으로 모든 카테고리의 도구 강제 호출 지시를 추가한다.
     * 각 카테고리 내에서는 priority 순서대로 평가하며, when 분기 카테고리는
     * 첫 번째 매칭에서 멈춘다.
     */
    private fun StringBuilder.appendToolForcingFromConfig(
        userPrompt: String?,
        workspaceToolAlreadyCalled: Boolean
    ) {
        if (workspaceToolAlreadyCalled) return

        val normalized = userPrompt?.lowercase()
        val matchedIds = mutableSetOf<String>()

        for (category in CATEGORY_ORDER) {
            val routes = routingConfig.routesByCategory[category] ?: continue
            if (category in WHEN_BRANCH_CATEGORIES) {
                appendFirstMatchingRoute(routes, userPrompt, normalized, matchedIds)
            } else {
                appendAllMatchingRoutes(routes, userPrompt, normalized, matchedIds)
            }
        }

        if (matchedIds.none { routingConfig.routesById[it]?.category == "swagger" } &&
            isSwaggerPrompt(userPrompt)
        ) {
            appendSwaggerFallbackForcing(userPrompt)
        }
    }

    /** when 분기 카테고리: 첫 번째 매칭 라우트만 적용한다. */
    private fun StringBuilder.appendFirstMatchingRoute(
        routes: List<ToolRoute>,
        prompt: String?,
        normalized: String?,
        matchedIds: MutableSet<String>
    ) {
        for (route in routes) {
            if (route.priority >= 999) continue
            if (matchesRoute(route, prompt, normalized, matchedIds)) {
                append("\n")
                append(route.promptInstruction)
                matchedIds.add(route.id)
                return
            }
        }
        val fallback = routes.find { it.priority >= 999 }
        if (fallback != null && matchesRoute(fallback, prompt, normalized, matchedIds)) {
            append("\n")
            append(fallback.promptInstruction)
            matchedIds.add(fallback.id)
        }
    }

    /** 일반 카테고리: 매칭되는 모든 라우트를 적용한다. */
    private fun StringBuilder.appendAllMatchingRoutes(
        routes: List<ToolRoute>,
        prompt: String?,
        normalized: String?,
        matchedIds: MutableSet<String>
    ) {
        for (route in routes) {
            if (matchesRoute(route, prompt, normalized, matchedIds)) {
                append("\n")
                append(route.promptInstruction)
                matchedIds.add(route.id)
            }
        }
    }

    /**
     * 라우트가 주어진 프롬프트와 매칭되는지 판단한다.
     */
    private fun matchesRoute(
        route: ToolRoute,
        prompt: String?,
        normalized: String?,
        matchedIds: Set<String>
    ): Boolean {
        if (prompt.isNullOrBlank() || normalized == null) return false

        if (route.excludeRoutes.any { it in matchedIds }) return false

        if (route.requiresNoUrl && hasSwaggerUrl(prompt)) return false

        if (route.excludeKeywords.isNotEmpty() &&
            route.excludeKeywords.any { normalized.contains(it) }
        ) return false

        if (route.multiKeywordGroups.isNotEmpty()) {
            val allGroupsMatch = route.multiKeywordGroups.all { group ->
                group.any { normalized.contains(it) }
            }
            if (!allGroupsMatch) return false
            if (route.keywords.isEmpty() && route.regexPatternRef == null &&
                route.parentRoute == null
            ) {
                return matchesRequiredKeywords(route, normalized) &&
                    matchesSwaggerBase(route, prompt, normalized)
            }
        }

        if (route.parentRoute != null) {
            val parent = routingConfig.routesById[route.parentRoute]
            if (parent != null && !matchesKeywords(parent, prompt, normalized)) return false
        }

        val hasKeywordMatch = if (route.keywords.isNotEmpty()) {
            route.keywords.any { normalized.contains(it) }
        } else {
            route.parentRoute != null || route.regexPatternRef != null ||
                route.multiKeywordGroups.isNotEmpty() ||
                (route.category == "swagger" && route.requiredKeywords.isNotEmpty())
        }

        if (!hasKeywordMatch) return false

        if (route.regexPatternRef != null) {
            val regex = ToolRoutingConfig.resolveRegex(route.regexPatternRef)
            val target = if (route.regexPatternRef == "ISSUE_KEY") {
                prompt.uppercase()
            } else {
                prompt
            }
            if (!regex.containsMatchIn(target)) return false
        }

        return matchesRequiredKeywords(route, normalized) &&
            matchesSwaggerBase(route, prompt, normalized)
    }

    private fun matchesRequiredKeywords(route: ToolRoute, normalized: String): Boolean {
        if (route.requiredKeywords.isEmpty()) return true
        return route.requiredKeywords.any { normalized.contains(it) }
    }

    /** swagger 카테고리 라우트는 기본 swagger 프롬프트 조건도 충족해야 한다. */
    private fun matchesSwaggerBase(
        route: ToolRoute,
        prompt: String,
        normalized: String
    ): Boolean {
        if (route.category != "swagger") return true
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) ||
            SWAGGER_HINTS.any { normalized.contains(it) }
    }

    /** 부모 라우트의 keywords 매칭 확인. */
    private fun matchesKeywords(
        route: ToolRoute,
        prompt: String,
        normalized: String
    ): Boolean {
        if (route.keywords.isNotEmpty()) {
            return route.keywords.any { normalized.contains(it) }
        }
        if (route.regexPatternRef != null) {
            val regex = ToolRoutingConfig.resolveRegex(route.regexPatternRef)
            val target = if (route.regexPatternRef == "ISSUE_KEY") {
                prompt.uppercase()
            } else {
                prompt
            }
            return regex.containsMatchIn(target)
        }
        return false
    }

    // ── Swagger 기본 판단 및 폴백 ──

    private fun isSwaggerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) ||
            SWAGGER_HINTS.any { prompt.lowercase().contains(it) }
    }

    private fun hasSwaggerUrl(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return OPENAPI_URL_REGEX.containsMatchIn(prompt)
    }

    private fun StringBuilder.appendSwaggerFallbackForcing(userPrompt: String?) {
        if (hasSwaggerUrl(userPrompt)) {
            append("\nFor this request, you MUST call `spec_load` and then `spec_summary` before answering.")
        } else {
            append("\nFor this request, you MUST call `spec_list` and then `spec_summary` before answering.")
            append(" Only call `spec_load` when the user explicitly provides a spec URL or raw spec content.")
            append(" Do not answer from `spec_list` alone.")
        }
    }

    // ── Sources 지시 및 Workspace 판단 ──

    private fun StringBuilder.appendSourcesInstruction(
        responseFormat: ResponseFormat,
        userPrompt: String?
    ) {
        if (responseFormat == ResponseFormat.TEXT && looksLikeWorkspacePrompt(userPrompt)) {
            append("\nEnd the response with a 'Sources' section that lists the supporting links.")
        }
    }

    /**
     * 프롬프트가 워크스페이스 관련 요청인지 판단한다.
     * Sources 섹션 추가 여부를 결정하는 데 사용된다.
     */
    private fun looksLikeWorkspacePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()

        val jiraMatch = JIRA_HINTS.any { normalized.contains(it) }
        val bitbucketMatch = BITBUCKET_HINTS.any { normalized.contains(it) }
        val swaggerMatch = isSwaggerPrompt(prompt)
        val confluenceKnowledge = CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) }
        val confluenceAnswer = confluenceKnowledge &&
            CONFLUENCE_ANSWER_HINTS.any { normalized.contains(it) }
        val confluenceDiscovery = confluenceKnowledge &&
            CONFLUENCE_DISCOVERY_HINTS.any { normalized.contains(it) }
        val confluencePageBody = confluenceKnowledge &&
            CONFLUENCE_PAGE_BODY_HINTS.any { normalized.contains(it) }
        val workBriefing = WORK_BRIEFING_HINTS.any { normalized.contains(it) }
        val workStandup = WORK_STANDUP_HINTS.any { normalized.contains(it) }
        val workReleaseRisk = WORK_RELEASE_RISK_HINTS.any { normalized.contains(it) }
        val workReleaseReadiness = WORK_RELEASE_READINESS_HINTS.any { normalized.contains(it) }
        val workOwner = !MISSING_ASSIGNEE_HINTS.any { normalized.contains(it) } &&
            WORK_OWNER_HINTS.any { normalized.contains(it) }
        val workItemContext = ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) &&
            WORK_ITEM_CONTEXT_HINTS.any { normalized.contains(it) }
        val workServiceContext =
            (normalized.contains("service") || normalized.contains("서비스")) &&
                WORK_SERVICE_CONTEXT_HINTS.any { normalized.contains(it) }

        return jiraMatch || bitbucketMatch || swaggerMatch ||
            (confluenceAnswer && !confluenceDiscovery) || confluenceDiscovery ||
            confluencePageBody ||
            workBriefing || workStandup || workReleaseRisk || workReleaseReadiness ||
            workOwner || workItemContext || workServiceContext
    }

    // ── 응답 형식 및 RAG 지시문 ──

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

    private fun buildRagInstruction(ragContext: String): String = buildString {
        append("[Retrieved Context]\n")
        append("The following information was retrieved from the knowledge base ")
        append("and may be relevant.\n")
        append("Use this context to inform your answer when relevant. ")
        append("If the context does not contain the answer, say so rather than guessing.\n")
        append("When using this context, cite the source. ")
        append("If the context doesn't answer the question, use general knowledge and say so.\n")
        append("Do not mention the retrieval process to the user.\n\n")
        append(ragContext)
    }

    companion object {
        /** 카테고리 평가 순서 (원본 메서드 호출 순서 유지). */
        private val CATEGORY_ORDER = listOf(
            "confluence", "work", "workContext", "jira", "bitbucket", "swagger"
        )

        /** when 분기로 동작하는 카테고리 (첫 번째 매칭에서 멈춤). */
        private val WHEN_BRANCH_CATEGORIES = setOf("jira", "bitbucket", "swagger")

        // ── looksLikeWorkspacePrompt에서 사용하는 키워드 집합 (Sources 판단용) ──

        private val CONFLUENCE_KNOWLEDGE_HINTS = setOf(
            "confluence", "wiki", "page", "document", "policy", "policies",
            "guideline", "guidelines", "runbook", "knowledge", "internal",
            "service", "space", "컨플루언스", "위키", "페이지", "문서", "정책",
            "규정", "가이드", "런북", "사내", "서비스", "스페이스"
        )
        private val CONFLUENCE_ANSWER_HINTS = setOf(
            "what", "who", "why", "how", "describe", "explain", "summary",
            "summarize", "tell me", "알려", "설명", "요약", "정리", "무엇",
            "왜", "어떻게", "누구", "본문", "body", "read", "읽"
        )
        private val CONFLUENCE_DISCOVERY_HINTS = setOf(
            "search", "find", "look up", "keyword", "list", "recent", "latest",
            "찾아", "검색", "키워드", "목록", "어떤 문서", "최근", "보여줘", "조회"
        )
        private val CONFLUENCE_PAGE_BODY_HINTS = setOf(
            "본문", "body", "content", "read", "읽고", "읽어", "내용", "핵심만"
        )
        private val WORK_BRIEFING_HINTS = setOf(
            "morning briefing", "daily briefing", "briefing", "work summary",
            "daily digest", "브리핑", "요약 브리핑", "아침 브리핑", "데일리 브리핑"
        )
        private val WORK_STANDUP_HINTS = setOf(
            "standup", "스탠드업", "daily update", "업데이트 초안", "standup update"
        )
        private val WORK_RELEASE_RISK_HINTS = setOf(
            "release risk", "risk digest", "릴리즈 위험", "출시 위험", "release digest"
        )
        private val WORK_RELEASE_READINESS_HINTS = setOf(
            "release readiness", "readiness pack", "릴리즈 준비", "출시 준비",
            "readiness"
        )
        private val WORK_OWNER_HINTS = setOf(
            "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
        )
        private val MISSING_ASSIGNEE_HINTS = setOf(
            "담당자가 없는", "담당자 없는", "미할당", "unassigned", "assignee 없는"
        )
        private val WORK_ITEM_CONTEXT_HINTS = setOf(
            "전체 맥락", "맥락", "context", "관련 문서", "관련 pr",
            "열린 pr", "오픈 pr", "다음 액션", "next action"
        )
        private val WORK_SERVICE_CONTEXT_HINTS = setOf(
            "서비스 상황", "서비스 현황", "service context", "service summary",
            "현재 상황", "현재 현황", "최근 jira", "최근 jira 이슈", "열린 pr",
            "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
        )
        private val JIRA_HINTS = setOf(
            "jira", "이슈", "issues", "프로젝트", "project", "jql", "ticket",
            "티켓", "blocker", "마감", "due", "transition", "전이", "백로그",
            "backlog", "스프린트", "sprint", "회고", "retrospective", "retro",
            "칸반", "kanban", "할 일", "todo", "업무", "작업"
        )
        private val BITBUCKET_HINTS = setOf(
            "bitbucket", "repository", "repo", "pull request", "pr", "branch",
            "브랜치", "저장소", "리뷰", "sla"
        )
        private val SWAGGER_HINTS = setOf(
            "swagger", "openapi", "spec", "schema", "endpoint", "api spec",
            "스펙", "엔드포인트", "스키마"
        )
        private val OPENAPI_URL_REGEX = WorkContextPatterns.OPENAPI_URL_REGEX
        private val ISSUE_KEY_REGEX = WorkContextPatterns.ISSUE_KEY_REGEX
    }
}
