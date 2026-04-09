package com.arc.reactor.agent.impl

/**
 * Jira 도메인 강제 도구 호출 계획 — Jira 검색, 프로젝트 스코프, 블로커/브리핑 폴백을 담당한다.
 *
 * [WorkContextForcedToolPlanner]의 plan() 체인에서 Jira 관련 분기를 처리한다.
 *
 * @see WorkContextForcedToolPlanner 오케스트레이터
 */
internal object WorkContextJiraPlanner {

    // ── 힌트 키워드 셋 ──

    private val jiraMyWorkHints = setOf(
        "my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈",
        "내 담당", "내 담당 이슈", "나한테", "나한테 할당", "내가 맡은"
    )
    private val jiraSearchHints = setOf("검색", "search")
    private val jiraRecentIssueHints = setOf(
        "최근 jira 이슈", "최근 이슈", "최근 운영 이슈",
        "recent jira issue", "recent issues"
    )
    private val jiraStatusChangeHints = setOf(
        "상태가 많이 바뀐", "상태 변화", "status changed", "status changes"
    )
    private val jiraDelayedHints = setOf(
        "늦어지고", "지연", "밀리고", "delay", "delayed", "overdue"
    )
    private val jiraReleaseHints = setOf(
        "release 관련", "release issues", "release related", "release"
    )
    private val jiraUnassignedHints = setOf(
        "unassigned", "미할당", "담당자가 없는", "담당자 없는",
        "assignee 없는"
    )

    /** 마감/기한 관련 힌트 — 개인화 여부와 무관하게 due-soon 도구를 호출한다. */
    private val jiraDueDateHints = setOf(
        "마감", "마감일", "마감 임박", "기한", "due date", "deadline",
        "due soon", "overdue", "임박"
    )

    // ── Jira 검색 계획 ──

    /** Jira 내 오픈 이슈 및 키워드 검색 계획. */
    fun planJiraSearch(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        // "jira" 키워드 또는 프로젝트 키가 있거나, 이슈/티켓/담당 관련 한국어 컨텍스트만으로도 개인 이슈 조회 가능
        if (n.matchesAnyHint(jiraMyWorkHints) &&
            (n.contains("jira") || n.contains("지라") ||
                n.contains("이슈") || n.contains("티켓") ||
                n.contains("담당") || n.contains("할당") || n.contains("맡은") ||
                ctx.projectKey != null)
        ) {
            return ForcedToolCallPlan(
                "jira_my_open_issues",
                buildMap {
                    ctx.projectKey?.let { put("project", it) }
                    put("maxResults", 20)
                }
            )
        }
        val searchKeyword =
            WorkContextEntityExtractor.extractSearchKeyword(prompt)
        if (n.contains("jira") && n.matchesAnyHint(jiraSearchHints) &&
            (n.contains("키워드") || n.contains("keyword")) &&
            searchKeyword != null
        ) {
            return ForcedToolCallPlan(
                "jira_search_by_text",
                mapOf("keyword" to searchKeyword, "limit" to 10)
            )
        }
        // 마감/기한 관련 질문 — 개인화 여부와 무관하게 due-soon 도구 호출
        if (n.matchesAnyHint(jiraDueDateHints) &&
            (n.contains("jira") || n.contains("지라") ||
                n.contains("이슈") || n.contains("티켓") ||
                ctx.projectKey != null)
        ) {
            return ForcedToolCallPlan(
                "jira_due_soon_issues",
                buildMap {
                    ctx.projectKey?.let { put("project", it) }
                    put("days", 7)
                    put("maxResults", 20)
                }
            )
        }
        return null
    }

    // ── 프로젝트 스코프 Jira 계획 ──

    /** 프로젝트 키 기반 Jira 조회 계획. */
    fun planJiraProjectScoped(
        ctx: PlannerCtx,
        hasDownstreamProjectHints: (String) -> Boolean
    ): ForcedToolCallPlan? {
        val pk = ctx.projectKey ?: return null
        val n = ctx.normalized

        if (n.matchesAnyHint(jiraRecentIssueHints) ||
            n.matchesAnyHint(jiraStatusChangeHints)
        ) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to
                        """project = "$pk" ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }
        if (n.matchesAnyHint(jiraDelayedHints)) {
            return ForcedToolCallPlan(
                "work_morning_briefing",
                WorkContextArgBuilder.buildMorningBriefingArgs(pk)
            )
        }
        if (n.matchesAnyHint(jiraReleaseHints) &&
            (n.matchesAnyHint(jiraSearchHints) || n.contains("이슈"))
        ) {
            return ForcedToolCallPlan(
                "jira_search_by_text",
                mapOf(
                    "keyword" to "release",
                    "project" to pk,
                    "limit" to 10
                )
            )
        }
        if (n.matchesAnyHint(jiraUnassignedHints)) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to """project = "$pk" AND assignee is EMPTY ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }
        if (hasDownstreamProjectHints(n)) return null
        return ForcedToolCallPlan(
            "jira_search_issues",
            mapOf(
                "jql" to
                    """project = "$pk" ORDER BY updated DESC""",
                "maxResults" to 10
            )
        )
    }

    // ── 블로커/브리핑 폴백 계획 ──

    /** 블로커 다이제스트 및 최종 브리핑 폴백 계획. */
    fun planBlockerAndBriefingFallback(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val ipk = ctx.inferredProjectKey ?: return null

        if (n.matchesAnyHint(WorkContextPatterns.BLOCKER_HINTS)) {
            return ForcedToolCallPlan(
                "jira_blocker_digest",
                mapOf("project" to ipk, "maxResults" to 25)
            )
        }
        if (n.matchesAnyHint(WorkContextPatterns.JIRA_BRIEFING_HINTS)) {
            if (n.contains("업무 브리핑") ||
                n.contains("work briefing")
            ) {
                return ForcedToolCallPlan(
                    "work_morning_briefing",
                    WorkContextArgBuilder
                        .buildMorningBriefingArgs(ipk)
                )
            }
            return ForcedToolCallPlan(
                "jira_daily_briefing",
                mapOf(
                    "project" to ipk,
                    "dueSoonDays" to 3,
                    "maxResults" to 30
                )
            )
        }
        if (n.matchesAnyHint(
                WorkContextPatterns.EXPLICIT_BRIEFING_FALLBACK_HINTS
            )
        ) {
            val keyword = if (n.contains("장애") ||
                n.contains("위험")
            ) "risk" else "status"
            return ForcedToolCallPlan(
                "work_morning_briefing",
                WorkContextArgBuilder
                    .buildMorningBriefingArgs(ipk, keyword)
            )
        }
        return null
    }
}
