package com.arc.reactor.agent.impl

internal data class ForcedToolCallPlan(
    val toolName: String,
    val arguments: Map<String, Any?>
)

internal object WorkContextForcedToolPlanner {
    private val issueKeyRegex = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b")
    private val projectRegexes = listOf(
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*프로젝트"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*팀"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*릴리즈"),
        Regex("\\bproject\\s+([A-Z][A-Z0-9_]{1,15})\\b", RegexOption.IGNORE_CASE)
    )
    private val repositoryRegex = Regex("\\b([A-Za-z0-9._-]{2,64})/([A-Za-z0-9._-]{2,64})\\b")
    private val quotedKeywordRegexes = listOf(
        Regex("'([^']{2,80})'"),
        Regex("\"([^\"]{2,80})\"")
    )
    private val keywordRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*키워드"),
        Regex("\\bkeyword\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b", RegexOption.IGNORE_CASE)
    )
    private val workOwnerHints = setOf(
        "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
    )
    private val workItemContextHints = setOf(
        "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr", "오픈 pr", "다음 액션", "next action"
    )
    private val workServiceContextHints = setOf(
        "서비스 상황", "서비스 현황", "service context", "service summary", "현재 상황", "현재 현황",
        "최근 jira", "최근 jira 이슈", "열린 pr", "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
    )
    private val workTeamStatusHints = setOf(
        "팀 상태", "team status", "주간 상태", "weekly status", "이번 주", "this week"
    )
    private val confluenceDiscoveryHints = setOf(
        "confluence", "컨플루언스", "wiki", "위키", "search", "검색", "keyword", "키워드", "어떤 문서", "목록"
    )
    private val jiraBlockerHints = setOf("blocker", "차단", "막힌")
    private val jiraRecentIssueHints = setOf("최근 jira 이슈", "최근 이슈", "recent jira issue", "recent issues")
    private val jiraBriefingHints = setOf(
        "daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest", "오늘의 jira 브리핑", "오늘 jira 브리핑"
    )
    private val jiraProjectListHints = setOf(
        "jira 프로젝트 목록", "접근 가능한 jira 프로젝트 목록", "jira project list", "list jira projects"
    )
    private val jiraSearchHints = setOf("검색", "search")
    private val jiraReleaseHints = setOf("release 관련", "release issues", "release related", "release")
    private val hybridReleaseRiskHints = setOf(
        "위험 신호", "risk signal", "release risk", "릴리즈 리스크", "risk digest"
    )
    private val hybridPriorityHints = setOf("우선순위", "priority", "priorities", "오늘 우선", "today priority")
    private val reviewQueueHints = setOf("review queue", "리뷰 대기열", "review sla", "리뷰 sla", "code review")
    private val workReleaseReadinessHints = setOf(
        "release readiness", "readiness pack", "릴리즈 준비", "출시 준비", "readiness"
    )
    private val workPersonalFocusHints = setOf(
        "focus plan", "personal focus plan", "개인 focus plan", "개인 집중 계획", "오늘 집중 계획"
    )
    private val workPersonalLearningHints = setOf(
        "learning digest", "personal learning digest", "학습 digest", "학습 다이제스트"
    )
    private val workPersonalInterruptHints = setOf(
        "interrupt guard", "personal interrupt guard", "interrupt plan", "인터럽트 가드", "집중 방해"
    )
    private val workPersonalWrapupHints = setOf(
        "end of day wrapup", "end-of-day wrapup", "eod wrapup", "wrapup", "wrap-up", "마감 정리", "하루 마감"
    )
    private val bitbucketReviewRiskHints = setOf(
        "review risk", "리뷰 리스크", "코드 리뷰 리스크"
    )
    private val bitbucketMyReviewHints = setOf(
        "내가 검토", "검토해야", "review for me", "needs review"
    )
    private val bitbucketOpenPrHints = setOf(
        "열린 pr", "오픈 pr", "open pr", "open prs", "pull request 목록", "pr 목록"
    )
    private val bitbucketStalePrHints = setOf(
        "stale pr", "오래된 pr", "방치된 pr", "stale pull request"
    )
    private val bitbucketReviewQueueHints = setOf(
        "review queue", "리뷰 대기열", "검토 대기열"
    )
    private val bitbucketReviewSlaHints = setOf(
        "review sla", "리뷰 sla", "sla 경고", "리뷰 sla 경고"
    )
    private val bitbucketBranchListHints = setOf(
        "branch 목록", "브랜치 목록", "list branches", "branches"
    )
    private val swaggerWrongEndpointHints = setOf(
        "wrong endpoint", "invalid endpoint", "잘못된 endpoint", "없는 endpoint"
    )
    private val serviceRegexes = listOf(
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*서비스", RegexOption.IGNORE_CASE),
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*service\\b", RegexOption.IGNORE_CASE)
    )

    fun plan(prompt: String?): ForcedToolCallPlan? {
        if (prompt.isNullOrBlank()) return null

        val normalized = prompt.lowercase()
        val issueKey = extractIssueKey(prompt)

        if (workOwnerHints.any { normalized.contains(it) }) {
            val query = issueKey ?: extractServiceName(prompt) ?: return null
            return ForcedToolCallPlan(
                toolName = "work_owner_lookup",
                arguments = mapOf("query" to query)
            )
        }

        if (issueKey != null && workItemContextHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_item_context",
                arguments = mapOf("issueKey" to issueKey)
            )
        }

        val serviceName = extractServiceName(prompt)
        if (serviceName != null && workServiceContextHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_service_context",
                arguments = mapOf("service" to serviceName)
            )
        }

        if (normalized.contains("jira") &&
            normalized.contains("confluence") &&
            workTeamStatusHints.any { normalized.contains(it) }
        ) {
            val teamKey = extractProjectKey(prompt)
            return ForcedToolCallPlan(
                toolName = "work_morning_briefing",
                arguments = buildMap {
                    teamKey?.let { put("jiraProject", it) }
                    put(
                        "confluenceKeyword",
                        if (normalized.contains("이번 주") || normalized.contains("this week")) "weekly" else "status"
                    )
                    put("reviewSlaHours", 24)
                    put("dueSoonDays", 7)
                    put("jiraMaxResults", 20)
                }
            )
        }

        if (workReleaseReadinessHints.any { normalized.contains(it) }) {
            val projectKey = extractProjectKey(prompt)
            val repository = extractRepository(prompt)
            return ForcedToolCallPlan(
                toolName = "work_release_readiness_pack",
                arguments = buildMap {
                    put("releaseName", inferReleaseName(prompt, projectKey, repository))
                    put("stalePrDays", 3)
                    put("reviewSlaHours", 24)
                    put("daysLookback", 1)
                    put("jiraMaxResults", 20)
                    put("dryRunActionItems", true)
                    put("actionItemsMaxCreate", 10)
                    put("blockerWeight", 4)
                    put("overdueWeight", 2)
                    put("reviewSlaBreachWeight", 3)
                    put("stalePrWeight", 1)
                    put("missingReleaseDocWeight", 2)
                    put("highRiskThreshold", 18)
                    put("mediumRiskThreshold", 10)
                    put("autoExecuteActionItems", false)
                    put("autoExecuteMaxRiskLevel", "MEDIUM")
                    put("autoExecuteRequireNoBlockers", true)
                    projectKey?.let { put("jiraProject", it) }
                    repository?.let {
                        put("bitbucketWorkspace", it.first)
                        put("bitbucketRepo", it.second)
                    }
                }
            )
        }

        if (workPersonalFocusHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_personal_focus_plan",
                arguments = mapOf("topN" to 5)
            )
        }

        if (workPersonalLearningHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_personal_learning_digest",
                arguments = mapOf(
                    "lookbackDays" to 14,
                    "topTopics" to 4,
                    "docsPerTopic" to 2
                )
            )
        }

        if (workPersonalInterruptHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_personal_interrupt_guard",
                arguments = mapOf(
                    "maxInterrupts" to 5,
                    "focusBlockMinutes" to 90
                )
            )
        }

        if (workPersonalWrapupHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_personal_end_of_day_wrapup",
                arguments = mapOf(
                    "lookbackDays" to 1,
                    "tomorrowTopN" to 3
                )
            )
        }

        if (jiraProjectListHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_list_projects",
                arguments = emptyMap()
            )
        }

        val projectKey = extractProjectKey(prompt)
        val repository = extractRepository(prompt)
        val searchKeyword = extractSearchKeyword(prompt)
        if (normalized.contains("jira") &&
            jiraSearchHints.any { normalized.contains(it) } &&
            (normalized.contains("키워드") || normalized.contains("keyword")) &&
            searchKeyword != null
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_search_by_text",
                arguments = mapOf(
                    "keyword" to searchKeyword,
                    "limit" to 10
                )
            )
        }

        if (projectKey != null && jiraRecentIssueHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_search_issues",
                arguments = mapOf(
                    "jql" to """project = "$projectKey" ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }

        if (projectKey != null &&
            jiraReleaseHints.any { normalized.contains(it) } &&
            jiraSearchHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_search_by_text",
                arguments = mapOf(
                    "keyword" to "release",
                    "project" to projectKey,
                    "limit" to 10
                )
            )
        }

        if (repository != null && bitbucketOpenPrHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_list_prs",
                arguments = mapOf(
                    "workspace" to repository.first,
                    "repo" to repository.second,
                    "state" to "OPEN"
                )
            )
        }

        if (repository != null && bitbucketStalePrHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_stale_prs",
                arguments = mapOf(
                    "workspace" to repository.first,
                    "repo" to repository.second,
                    "staleDays" to 7
                )
            )
        }

        if (repository != null && bitbucketReviewQueueHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_review_queue",
                arguments = mapOf(
                    "workspace" to repository.first,
                    "repo" to repository.second
                )
            )
        }

        if (repository != null && bitbucketReviewSlaHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_review_sla_alerts",
                arguments = mapOf(
                    "workspace" to repository.first,
                    "repo" to repository.second,
                    "slaHours" to 24
                )
            )
        }

        if (repository != null && bitbucketBranchListHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_list_branches",
                arguments = mapOf(
                    "workspace" to repository.first,
                    "repo" to repository.second
                )
            )
        }

        if (normalized.contains("bitbucket") && bitbucketMyReviewHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_review_queue",
                arguments = emptyMap()
            )
        }

        if (normalized.contains("bitbucket") && bitbucketReviewRiskHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_review_sla_alerts",
                arguments = mapOf("slaHours" to 24)
            )
        }

        if ((normalized.contains("swagger") || normalized.contains("openapi") || normalized.contains("spec") || normalized.contains("스펙")) &&
            swaggerWrongEndpointHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "spec_list",
                arguments = emptyMap()
            )
        }

        if (hybridPriorityHints.any { normalized.contains(it) } &&
            jiraBlockerHints.any { normalized.contains(it) } &&
            reviewQueueHints.any { normalized.contains(it) }
        ) {
            val projectKey = extractProjectKey(prompt)
            val repository = extractRepository(prompt)
            return ForcedToolCallPlan(
                toolName = "work_release_risk_digest",
                arguments = buildMap {
                    put("releaseName", inferReleaseName(prompt, projectKey, repository))
                    put("stalePrDays", 3)
                    put("reviewSlaHours", 24)
                    put("jiraMaxResults", 20)
                    projectKey?.let { put("jiraProject", it) }
                    repository?.let {
                        put("bitbucketWorkspace", it.first)
                        put("bitbucketRepo", it.second)
                    }
                }
            )
        }

        if (normalized.contains("jira") &&
            normalized.contains("bitbucket") &&
            hybridReleaseRiskHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "work_release_risk_digest",
                arguments = buildMap {
                    put("releaseName", inferReleaseName(prompt, projectKey, repository))
                    put("stalePrDays", 3)
                    put("reviewSlaHours", 24)
                    put("jiraMaxResults", 20)
                    projectKey?.let { put("jiraProject", it) }
                    repository?.let {
                        put("bitbucketWorkspace", it.first)
                        put("bitbucketRepo", it.second)
                    }
                }
            )
        }

        if (confluenceDiscoveryHints.any { normalized.contains(it) }) {
            val keyword = extractQuotedKeyword(prompt)
            if (keyword != null) {
                return ForcedToolCallPlan(
                    toolName = "confluence_search_by_text",
                    arguments = mapOf(
                        "keyword" to keyword,
                        "limit" to 10
                    )
                )
            }
        }

        if (projectKey != null && jiraBlockerHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_blocker_digest",
                arguments = mapOf(
                    "project" to projectKey,
                    "maxResults" to 25
                )
            )
        }

        if (projectKey != null && jiraBriefingHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_daily_briefing",
                arguments = mapOf(
                    "project" to projectKey,
                    "dueSoonDays" to 3,
                    "maxResults" to 30
                )
            )
        }

        return null
    }

    private fun extractIssueKey(prompt: String): String? {
        return issueKeyRegex.find(prompt.uppercase())?.value
    }

    private fun extractServiceName(prompt: String): String? {
        return serviceRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractProjectKey(prompt: String): String? {
        return projectRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim().uppercase() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractRepository(prompt: String): Pair<String, String>? {
        val match = repositoryRegex.find(prompt) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun extractQuotedKeyword(prompt: String): String? {
        return quotedKeywordRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractSearchKeyword(prompt: String): String? {
        return extractQuotedKeyword(prompt)
            ?: keywordRegexes.asSequence()
                .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
    }

    private fun inferReleaseName(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): String {
        return extractQuotedKeyword(prompt)
            ?: projectKey
            ?: repository?.second
            ?: "release-readiness"
    }
}
