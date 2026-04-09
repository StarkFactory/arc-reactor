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
        "recent jira issue", "recent issues",
        "이슈 현황", "이슈 상황", "issue status"
    )

    /** 시간 범위 기반 Jira 검색 힌트 — 일주일/월 범위의 이슈 조회. */
    private val jiraTimeRangeHints = setOf(
        "최근 일주일", "지난 일주일", "이번 주", "이번주",
        "지난 주", "지난주", "이번 달", "이번달", "지난 달", "지난달",
        "last week", "this week", "this month", "last month",
        "일주일 이내", "한 달 이내", "7일", "30일"
    )
    private val jiraCompletedIssueHints = setOf(
        "최근 완료", "최근 완료 이슈", "완료된 이슈", "완료한 이슈",
        "recently completed", "recently done", "recently resolved",
        "done issues", "resolved issues", "closed issues"
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
        // 시간 범위 기반 이슈 조회 — "최근 일주일", "이번 주", "이번 달" 등
        if (n.matchesAnyHint(jiraTimeRangeHints) &&
            (n.contains("jira") || n.contains("지라") ||
                n.contains("이슈") || n.contains("티켓") ||
                ctx.projectKey != null)
        ) {
            val jql = buildTimeRangeJql(ctx.projectKey, n)
            return ForcedToolCallPlan(
                "jira_search_issues",
                buildMap {
                    put("jql", jql)
                    put("maxResults", 20)
                }
            )
        }
        // 완료 이슈 조회 — 프로젝트 키 없이도 완료 상태 JQL로 검색
        if (n.matchesAnyHint(jiraCompletedIssueHints) &&
            (n.contains("jira") || n.contains("지라") ||
                n.contains("이슈") || n.contains("티켓") ||
                ctx.projectKey != null)
        ) {
            val jql = if (ctx.projectKey != null) {
                """project = "${ctx.projectKey}" AND status in (Done, Resolved, Closed) ORDER BY updated DESC"""
            } else {
                "status in (Done, Resolved, Closed) ORDER BY updated DESC"
            }
            return ForcedToolCallPlan(
                "jira_search_issues",
                buildMap {
                    put("jql", jql)
                    put("maxResults", 10)
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

        if (n.matchesAnyHint(jiraCompletedIssueHints)) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to
                        """project = "$pk" AND status in (Done, Resolved, Closed) ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }
        if (n.matchesAnyHint(jiraTimeRangeHints)) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to buildTimeRangeJql(pk, n),
                    "maxResults" to 20
                )
            )
        }
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

    // ── 내부 유틸리티 ──

    /**
     * 시간 범위 힌트에서 JQL 조건 문자열을 생성한다.
     *
     * "이번 달"/"한 달 이내"이면 -30d, 나머지는 -7d 기준으로 생성한다.
     */
    private fun buildTimeRangeJql(projectKey: String?, normalized: String): String {
        val period = if (normalized.contains("이번 달") || normalized.contains("이번달") ||
            normalized.contains("지난 달") || normalized.contains("지난달") ||
            normalized.contains("this month") || normalized.contains("last month") ||
            normalized.contains("30일") || normalized.contains("한 달 이내")
        ) "-30d" else "-7d"
        return if (projectKey != null) {
            """project = "$projectKey" AND updated >= $period ORDER BY updated DESC"""
        } else {
            "updated >= $period ORDER BY updated DESC"
        }
    }
}
