package com.arc.reactor.agent.impl

import com.arc.reactor.support.WorkContextPatterns

/**
 * 강제 도구 호출 계획 — 사용자 프롬프트 분석 결과에 따라 특정 도구를 강제로 호출하도록 지시한다.
 *
 * @param toolName 호출할 도구 이름
 * @param arguments 도구에 전달할 인자 맵
 */
internal data class ForcedToolCallPlan(
    val toolName: String,
    val arguments: Map<String, Any?>
)

/**
 * 사용자 프롬프트를 분석하여 강제로 호출해야 할 workspace 도구와 인자를 결정하는 planner.
 *
 * ReAct 루프의 첫 번째 반복에서 LLM이 도구를 호출하지 않더라도,
 * 프롬프트의 의도에 맞는 도구를 강제로 호출할 수 있도록 [ForcedToolCallPlan]을 생성한다.
 *
 * 지원하는 workspace 도구 카테고리:
 * - **Jira**: 이슈 조회, 검색, 브리핑, 블로커, 마감 임박 등
 * - **Confluence**: 문서 답변, 검색, 페이지 본문, 스페이스 목록 등
 * - **Bitbucket**: PR 목록, 리뷰 큐, SLA 경고, 브랜치 등
 * - **Swagger/OpenAPI**: 스펙 로드, 검색, 스키마, 검증 등
 * - **Work 통합**: 브리핑, 스탠드업, 릴리즈 리스크, 소유자 조회, 서비스 컨텍스트 등
 * - **개인화**: 포커스 플랜, 학습 다이제스트, 인터럽트 가드, 마감 정리 등
 *
 * @see SystemPromptBuilder 시스템 프롬프트에서 도구 호출 강제 지시를 추가하는 대응 역할
 * @see SpringAiAgentExecutor ReAct 루프에서 강제 도구 호출 계획을 실행
 */
internal object WorkContextForcedToolPlanner {
    private val specNameSanitizeRegex = Regex("[^a-z0-9._-]")
    private val issueKeyRegex = WorkContextPatterns.ISSUE_KEY_REGEX
    private val projectRegexes = listOf(
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*프로젝트"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*팀"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*릴리즈"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*이슈"),
        Regex("\\bproject\\s+([A-Z][A-Z0-9_]{1,15})\\b", RegexOption.IGNORE_CASE)
    )
    private val looseProjectRegex = Regex("\\b([A-Z][A-Z0-9_]{1,15})\\b")
    private val looseProjectStopWords = setOf(
        "API", "JIRA", "CONFLUENCE", "BITBUCKET", "SWAGGER", "OPENAPI", "GET", "POST", "PUT",
        "PATCH", "DELETE", "HEAD", "HTTP", "HTTPS", "MCP", "PDF", "URL", "JSON", "XML", "SQL", "UI", "UX"
    )
    private val repositoryRegex = Regex("\\b([A-Za-z0-9._-]{2,64})/([A-Za-z0-9._-]{2,64})\\b")
    private val repositorySlugRegex = Regex("([A-Za-z0-9._-]{2,64})\\s*저장소", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE)
    private val quotedKeywordRegexes = listOf(
        Regex("'([^']{2,80})'"),
        Regex("\"([^\"]{2,80})\"")
    )
    private val keywordRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*키워드"),
        Regex("\\bkeyword\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b", RegexOption.IGNORE_CASE)
    )
    private val swaggerSpecNameRegex = Regex("\\b([A-Za-z][A-Za-z0-9._-]{2,63})\\b")
    private val swaggerSpecStopWords = setOf(
        "swagger", "openapi", "spec", "summary", "summarize", "schema", "endpoint", "security",
        "methods", "method", "detail", "details", "loaded", "local", "current", "show", "tell",
        "the", "and", "for", "with", "pet", "store", "user"
    )
    private val apiRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*api\\b", RegexOption.IGNORE_CASE),
        Regex("\\bapi\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b", RegexOption.IGNORE_CASE)
    )
    private val workOwnerHints = setOf(
        "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
    )
    private val ownershipDiscoveryHints = setOf(
        "누가 관리", "누가 쓰는지", "누가 개발", "주로 관리", "owner 문서", "owner를 확인", "담당 팀이 적힌"
    )
    private val missingAssigneeHints = setOf(
        "담당자가 없는", "담당자 없는", "미할당", "unassigned", "assignee is empty", "assignee 없는"
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
    private val confluenceSpaceListHints = setOf(
        "confluence 스페이스 목록", "컨플루언스 스페이스 목록", "접근 가능한 confluence 스페이스 목록",
        "confluence spaces", "list confluence spaces", "space 목록"
    )
    private val documentDiscoveryHints = setOf(
        "관련 문서", "문서가 있으면", "문서가 있는지", "관련 문서가 있으면", "없으면 없다고",
        "링크와 함께", "핵심만 요약", "키워드로 검색", "search and summarize", "document if exists"
    )
    private val jiraBlockerHints = setOf("blocker", "차단", "막힌")
    private val jiraRecentIssueHints = setOf("최근 jira 이슈", "최근 이슈", "최근 운영 이슈", "recent jira issue", "recent issues")
    private val jiraUnassignedHints = setOf("unassigned", "미할당", "담당자가 없는", "담당자 없는", "assignee 없는")
    private val jiraDelayedHints = setOf("늦어지고", "지연", "밀리고", "delay", "delayed", "overdue")
    private val jiraStatusChangeHints = setOf("상태가 많이 바뀐", "상태 변화", "status changed", "status changes")
    private val jiraBriefingHints = setOf(
        "daily briefing",
        "아침 브리핑",
        "업무 브리핑",
        "데일리 브리핑",
        "daily digest",
        "오늘의 jira 브리핑",
        "오늘 jira 브리핑",
        "jira 브리핑",
        "jira briefing",
        "오늘의 jira briefing"
    )
    private val jiraProjectListHints = setOf(
        "jira 프로젝트 목록", "접근 가능한 jira 프로젝트 목록", "jira project list", "list jira projects"
    )
    private val jiraSearchHints = setOf("검색", "search")
    private val jiraReleaseHints = setOf("release 관련", "release issues", "release related", "release")
    private val jiraMyWorkHints = setOf("my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈")
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
    private val workPersonalFocusGeneralHints = setOf(
        "내가 지금 해야 할 작업", "지금 해야 할 작업", "오늘 집중해야", "오늘 해야 할 일", "내가 오늘 집중해야",
        "내가 오늘 해야 할", "마감 전에 끝내", "끝내면 좋은 일",
        "미뤄도 되는 일", "우선순위 순", "open issue와 due soon", "due soon", "리스크가 큰 것",
        "집중해야 할 api 관련", "review queue를", "carry-over", "내일 아침 바로 봐야", "내 open issue",
        "오늘 브리핑", "morning briefing", "해야 할 일과 미뤄도 되는 일"
    )
    private val workPersonalLearningHints = setOf(
        "learning digest", "personal learning digest", "학습 digest", "학습 다이제스트"
    )
    private val workPersonalLearningGeneralHints = setOf(
        "최근에 관여한 이슈와 문서", "최근 참여한 작업", "읽어야 할 runbook", "incident 문서",
        "최근에 본 문서", "이번 주 팀 변화", "봐야 할 pr과 문서", "jira와 bitbucket 기준으로 묶어",
        "알아야 할 이번 주 팀 변화", "최근 참여한 작업을 jira와 bitbucket 기준으로"
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
    private val bitbucketMyReviewLateHints = setOf(
        "늦게 보고 있는 리뷰", "review queue", "내 review queue", "리뷰 대기열", "리뷰 sla 경고"
    )
    private val bitbucketMyAuthoredPrHints = setOf(
        "리뷰를 기다리게 만든 pr", "내가 만든 pr", "내 pr", "내 pull request", "내가 올린 pr"
    )
    private val bitbucketOpenPrHints = setOf(
        "열린 pr", "오픈 pr", "open pr", "open prs", "pull request 목록", "pr 목록"
    )
    private val bitbucketStalePrHints = setOf(
        "stale pr", "오래된 pr", "방치된 pr", "stale pull request"
    )
    private val bitbucketReviewQueueHints = setOf(
        "review queue", "리뷰 대기열", "검토 대기열", "리뷰가 필요한", "검토가 필요한", "needs review", "review needed"
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
    private val swaggerSummaryHints = setOf("summary", "summarize", "요약", "정리")
    private val swaggerDiscoveryHints = setOf(
        "endpoint", "엔드포인트", "schema", "스키마", "인증", "auth", "에러 응답", "error response",
        "파라미터", "parameter", "로드된 스펙", "load", "로드한 뒤"
    )
    private val personalDocumentHints = setOf(
        "휴가 규정", "남은 휴가", "내 이름 기준", "owner로 적혀", "회의록", "runbook", "incident 문서",
        "owner 문서", "서비스 owner", "api 문서"
    )
    private val personalIdentityPhrases = setOf(
        "내가", "내 기준", "내 기준으로", "내 이름", "내 휴가", "내 오픈", "내 open", "내 review", "내 리뷰",
        "내 jira", "내 pr", "내 pull request", "내가 맡은", "내가 오늘", "내가 최근", "내 이름으로",
        "내가 담당", "내 기준으로 오늘", "내 owner"
    )
    private val personalIdentityRegexes = listOf(
        Regex("(^|\\s)내\\s"),
        Regex("(^|\\s)내(가|를|가요|가야|기준으로|기준|이름으로|이름|휴가|오픈|리뷰|jira|pr)(\\b|\\s|$)")
    )
    private val preDeployReadinessHints = setOf("배포 전에", "출시 전에", "release 전에", "pre-release")
    private val serviceRegexes = listOf(
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*서비스", RegexOption.IGNORE_CASE),
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*service\\b", RegexOption.IGNORE_CASE)
    )

    /**
     * 사용자 프롬프트를 분석하여 강제 도구 호출 계획을 수립한다.
     *
     * 프롬프트에서 이슈 키, 서비스명, 프로젝트 키, 레포지토리, URL 등을 추출하고,
     * 힌트 키워드 매칭을 통해 가장 적합한 도구와 인자를 결정한다.
     * 우선순위가 높은 규칙부터 순서대로 평가하여 첫 번째 매칭된 계획을 반환한다.
     *
     * @param prompt 사용자 프롬프트 (null/빈 문자열이면 null 반환)
     * @return 강제 호출 계획. 매칭되는 규칙이 없으면 null
     */
    fun plan(prompt: String?): ForcedToolCallPlan? {
        if (prompt.isNullOrBlank()) return null

        val normalized = prompt.lowercase()
        val issueKey = extractIssueKey(prompt)
        val serviceName = extractServiceName(prompt)
        val projectKey = extractProjectKey(prompt)
        val inferredProjectKey = projectKey ?: extractLooseProjectKey(prompt)
        val repository = extractRepository(prompt)
        val specUrl = extractUrl(prompt)
        val swaggerSpecName = extractSwaggerSpecName(prompt)
        val ownershipKeyword = extractOwnershipKeyword(prompt)
        val isPersonalPrompt = isPersonalPrompt(normalized)

        if (serviceName != null &&
            workOwnerHints.any { normalized.contains(it) } &&
            (workItemContextHints.any { normalized.contains(it) } ||
                workServiceContextHints.any { normalized.contains(it) } ||
                normalized.contains("최근 이슈") ||
                normalized.contains("관련 이슈"))
        ) {
            return ForcedToolCallPlan(
                toolName = "work_service_context",
                arguments = mapOf("service" to serviceName)
            )
        }

        if (!missingAssigneeHints.any { normalized.contains(it) } &&
            workOwnerHints.any { normalized.contains(it) }) {
            val query = issueKey ?: extractServiceName(prompt)
            if (query != null) {
                return ForcedToolCallPlan(
                    toolName = "work_owner_lookup",
                    arguments = mapOf("query" to query)
                )
            }
        }

        val repositorySlug = repository?.second ?: extractRepositorySlug(prompt)

        if (repositorySlug != null &&
            !missingAssigneeHints.any { normalized.contains(it) } &&
            (workOwnerHints.any { normalized.contains(it) } || ownershipDiscoveryHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "work_owner_lookup",
                arguments = mapOf(
                    "query" to repositorySlug,
                    "entityType" to "repository"
                )
            )
        }

        if (serviceName != null &&
            !missingAssigneeHints.any { normalized.contains(it) } &&
            (workOwnerHints.any { normalized.contains(it) } || ownershipDiscoveryHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "work_owner_lookup",
                arguments = mapOf(
                    "query" to serviceName,
                    "entityType" to "service"
                )
            )
        }

        if (ownershipKeyword != null &&
            !missingAssigneeHints.any { normalized.contains(it) } &&
            (workOwnerHints.any { normalized.contains(it) } || ownershipDiscoveryHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "confluence_search_by_text",
                arguments = mapOf(
                    "keyword" to ownershipKeyword,
                    "limit" to 10
                )
            )
        }

        if (ownershipKeyword == null &&
            !missingAssigneeHints.any { normalized.contains(it) } &&
            (workOwnerHints.any { normalized.contains(it) } || ownershipDiscoveryHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "confluence_search_by_text",
                arguments = mapOf(
                    "keyword" to "owner",
                    "limit" to 10
                )
            )
        }

        if (issueKey != null && workItemContextHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_item_context",
                arguments = mapOf("issueKey" to issueKey)
            )
        }

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
            val teamKey = inferredProjectKey
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

        if (inferredProjectKey != null && normalized.contains("standup")) {
            return ForcedToolCallPlan(
                toolName = "work_prepare_standup_update",
                arguments = mapOf(
                    "jiraProject" to inferredProjectKey,
                    "daysLookback" to 7,
                    "jiraMaxResults" to 20
                )
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

        if (isPersonalPrompt &&
            (normalized.contains("release risk") || normalized.contains("릴리즈 위험") || normalized.contains("release blocker"))
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_blocker_digest",
                arguments = mapOf("maxResults" to 25)
            )
        }

        if (isPersonalPrompt &&
            (normalized.contains("jira blocker") || normalized.contains("제일 먼저 처리해야 할 jira blocker"))
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_blocker_digest",
                arguments = mapOf("maxResults" to 25)
            )
        }

        if (isPersonalPrompt &&
            normalized.contains("리뷰 대기열") &&
            (normalized.contains("jira") || normalized.contains("due soon") || normalized.contains("open issue"))
        ) {
            return ForcedToolCallPlan(
                toolName = "work_personal_focus_plan",
                arguments = mapOf("topN" to 5)
            )
        }

        if (isPersonalPrompt &&
            (normalized.contains("overdue") || normalized.contains("due soon") || normalized.contains("마감 전에") || normalized.contains("마감이 가까")))
        {
            return ForcedToolCallPlan(
                toolName = "jira_due_soon_issues",
                arguments = mapOf(
                    "days" to 7,
                    "maxResults" to 20
                )
            )
        }

        if (isPersonalPrompt &&
            normalized.contains("release 관련") &&
            (normalized.contains("jira 작업") || normalized.contains("내 jira")))
        {
            return ForcedToolCallPlan(
                toolName = "jira_search_my_issues_by_text",
                arguments = mapOf(
                    "keyword" to "release",
                    "maxResults" to 10
                )
            )
        }

        if (isPersonalPrompt &&
            normalized.contains("api 관련") &&
            (normalized.contains("작업") || normalized.contains("이슈")))
        {
            return ForcedToolCallPlan(
                toolName = "jira_search_my_issues_by_text",
                arguments = mapOf(
                    "keyword" to "api",
                    "maxResults" to 10
                )
            )
        }

        if (isPersonalPrompt &&
            normalized.contains("open issue") &&
            normalized.contains("due soon")
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_daily_briefing",
                arguments = mapOf(
                    "dueSoonDays" to 7,
                    "maxResults" to 20
                )
            )
        }

        if (isPersonalPrompt && bitbucketMyAuthoredPrHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "bitbucket_my_authored_prs",
                arguments = mapOf("reviewPendingOnly" to true)
            )
        }

        if (isPersonalPrompt &&
            (normalized.contains("morning briefing") || normalized.contains("아침 브리핑") || normalized.contains("개인화해서")))
        {
            return ForcedToolCallPlan(
                toolName = "work_personal_focus_plan",
                arguments = mapOf("topN" to 5)
            )
        }

        if (isPersonalPrompt &&
            workPersonalFocusGeneralHints.any { normalized.contains(it) }
        ) {
            val topN = if (normalized.contains("3개")) 3 else 5
            return ForcedToolCallPlan(
                toolName = "work_personal_focus_plan",
                arguments = mapOf("topN" to topN)
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

        if (isPersonalPrompt &&
            workPersonalLearningGeneralHints.any { normalized.contains(it) }
        ) {
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

        if (isPersonalPrompt &&
            bitbucketMyReviewLateHints.any { normalized.contains(it) }
        ) {
            val toolName = if (normalized.contains("sla")) "bitbucket_review_sla_alerts" else "bitbucket_review_queue"
            val args = if (toolName == "bitbucket_review_sla_alerts") mapOf("slaHours" to 24) else emptyMap()
            return ForcedToolCallPlan(toolName = toolName, arguments = args)
        }

        if (isPersonalPrompt &&
            personalDocumentHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "work_personal_document_search",
                arguments = buildMap {
                    when {
                        normalized.contains("휴가") -> put("keyword", "휴가")
                        normalized.contains("회의록") -> put("keyword", "회의록")
                        normalized.contains("runbook") -> put("keyword", "runbook")
                        normalized.contains("incident") -> put("keyword", "incident")
                        normalized.contains("owner") -> put("keyword", "owner")
                    }
                    put("limit", 5)
                }
            )
        }

        if (confluenceSpaceListHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "confluence_list_spaces",
                arguments = emptyMap()
            )
        }

        if (jiraProjectListHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_list_projects",
                arguments = emptyMap()
            )
        }

        val searchKeyword = extractSearchKeyword(prompt)

        if ((normalized.contains("jira") || projectKey != null) &&
            jiraMyWorkHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_my_open_issues",
                arguments = buildMap {
                    projectKey?.let { put("project", it) }
                    put("maxResults", 20)
                }
            )
        }

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

        if (projectKey != null && jiraDelayedHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_morning_briefing",
                arguments = mapOf(
                    "jiraProject" to projectKey,
                    "confluenceKeyword" to "status",
                    "reviewSlaHours" to 24,
                    "dueSoonDays" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        if (projectKey != null && jiraStatusChangeHints.any { normalized.contains(it) }) {
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
            (jiraSearchHints.any { normalized.contains(it) } || normalized.contains("이슈"))
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

        if (projectKey != null && jiraUnassignedHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_search_issues",
                arguments = mapOf(
                    "jql" to """project = "$projectKey" AND assignee is EMPTY ORDER BY updated DESC""",
                    "maxResults" to 10
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

        if (isPersonalPrompt && bitbucketMyReviewHints.any { normalized.contains(it) }) {
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

        if (normalized.contains("어떤 api가 지금 제일 많이 바뀌") ||
            normalized.contains("which api") && normalized.contains("changed")
        ) {
            return ForcedToolCallPlan(
                toolName = "jira_search_by_text",
                arguments = mapOf(
                    "keyword" to "api",
                    "limit" to 10
                )
            )
        }

        if (normalized.contains("누가 어떤 서비스나 api를 맡") ||
            normalized.contains("owner 문서") ||
            normalized.contains("owner가 누구")
        ) {
            return ForcedToolCallPlan(
                toolName = "confluence_search_by_text",
                arguments = mapOf(
                    "keyword" to "owner",
                    "limit" to 10
                )
            )
        }

        if (specUrl == null &&
            swaggerSpecName != null &&
            (normalized.contains("swagger") || normalized.contains("openapi") || normalized.contains("spec") || normalized.contains("스펙")) &&
            swaggerSummaryHints.any { normalized.contains(it) } &&
            !normalized.contains("목록") &&
            !normalized.contains("list")
        ) {
            return ForcedToolCallPlan(
                toolName = "spec_summary",
                arguments = mapOf(
                    "specName" to swaggerSpecName,
                    "scope" to "published"
                )
            )
        }

        if ((normalized.contains("swagger") || normalized.contains("api")) &&
            (normalized.contains("consumer") || normalized.contains("schema를 어디서") || normalized.contains("schema"))
        ) {
            return ForcedToolCallPlan(
                toolName = "spec_list",
                arguments = emptyMap()
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

        val quotedKeyword = extractQuotedKeyword(prompt)
        if (quotedKeyword != null &&
            (confluenceDiscoveryHints.any { normalized.contains(it) } ||
                documentDiscoveryHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "confluence_search_by_text",
                arguments = mapOf(
                    "keyword" to quotedKeyword,
                    "limit" to 10
                )
            )
        }

        if (projectKey != null &&
            (normalized.contains("confluence") || normalized.contains("문서") || normalized.contains("지식")) &&
            (normalized.contains("이슈") || normalized.contains("jira") || normalized.contains("운영"))
        ) {
            return ForcedToolCallPlan(
                toolName = "work_morning_briefing",
                arguments = mapOf(
                    "jiraProject" to projectKey,
                    "confluenceKeyword" to "status",
                    "reviewSlaHours" to 24,
                    "dueSoonDays" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        if (projectKey != null &&
            (normalized.contains("standup") || normalized.contains("스탠드업")) &&
            normalized.contains("confluence") &&
            normalized.contains("jira")
        ) {
            return ForcedToolCallPlan(
                toolName = "work_prepare_standup_update",
                arguments = mapOf(
                    "jiraProject" to projectKey,
                    "daysLookback" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        if ((normalized.contains("standup") || normalized.contains("스탠드업")) &&
            normalized.contains("confluence") &&
            normalized.contains("jira")
        ) {
            return ForcedToolCallPlan(
                toolName = "work_prepare_standup_update",
                arguments = mapOf(
                    "daysLookback" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        if (inferredProjectKey != null &&
            (normalized.contains("standup") || normalized.contains("스탠드업")) &&
            (normalized.contains("바로 말해야") || normalized.contains("정리해줘"))
        ) {
            return ForcedToolCallPlan(
                toolName = "work_prepare_standup_update",
                arguments = mapOf(
                    "jiraProject" to inferredProjectKey,
                    "daysLookback" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        if (preDeployReadinessHints.any { normalized.contains(it) } &&
            (normalized.contains("문서") || normalized.contains("이슈"))
        ) {
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

        if (projectKey != null &&
            repository != null &&
            hybridReleaseRiskHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "work_release_risk_digest",
                arguments = buildMap {
                    put("releaseName", inferReleaseName(prompt, projectKey, repository))
                    put("stalePrDays", 3)
                    put("reviewSlaHours", 24)
                    put("jiraMaxResults", 20)
                    put("jiraProject", projectKey)
                    put("bitbucketWorkspace", repository.first)
                    put("bitbucketRepo", repository.second)
                }
            )
        }

        if (specUrl != null && (normalized.contains("swagger") || normalized.contains("openapi") || normalized.contains("스펙"))) {
            return ForcedToolCallPlan(
                toolName = "spec_load",
                arguments = mapOf(
                    "name" to inferSpecName(specUrl),
                    "url" to specUrl,
                    "content" to null
                )
            )
        }

        if ((normalized.contains("로드된 스펙") || normalized.contains("로컬에 로드된") || normalized.contains("loaded spec")) &&
            swaggerDiscoveryHints.any { normalized.contains(it) }
        ) {
            return ForcedToolCallPlan(
                toolName = "spec_list",
                arguments = emptyMap()
            )
        }

        if (inferredProjectKey != null && jiraBlockerHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "jira_blocker_digest",
                arguments = mapOf(
                    "project" to inferredProjectKey,
                    "maxResults" to 25
                )
            )
        }

        if (inferredProjectKey != null && jiraBriefingHints.any { normalized.contains(it) }) {
            if (normalized.contains("업무 브리핑") || normalized.contains("work briefing")) {
                return ForcedToolCallPlan(
                    toolName = "work_morning_briefing",
                    arguments = mapOf(
                        "jiraProject" to inferredProjectKey,
                        "confluenceKeyword" to "status",
                        "reviewSlaHours" to 24,
                        "dueSoonDays" to 7,
                        "jiraMaxResults" to 20
                    )
                )
            }
            return ForcedToolCallPlan(
                toolName = "jira_daily_briefing",
                arguments = mapOf(
                    "project" to inferredProjectKey,
                    "dueSoonDays" to 3,
                    "maxResults" to 30
                )
            )
        }

        if (inferredProjectKey != null &&
            (normalized.contains("오늘") || normalized.contains("이번 주") || normalized.contains("현재") ||
                normalized.contains("상태") || normalized.contains("장애") || normalized.contains("위험") ||
                normalized.contains("우선순위") || workTeamStatusHints.any { normalized.contains(it) })
        ) {
            return ForcedToolCallPlan(
                toolName = "work_morning_briefing",
                arguments = mapOf(
                    "jiraProject" to inferredProjectKey,
                    "confluenceKeyword" to if (normalized.contains("장애") || normalized.contains("위험")) "risk" else "status",
                    "reviewSlaHours" to 24,
                    "dueSoonDays" to 7,
                    "jiraMaxResults" to 20
                )
            )
        }

        return null
    }

    // ── 프롬프트에서 엔티티를 추출하는 헬퍼 메서드들 ──

    /** Jira 이슈 키(예: PROJ-123)를 추출한다. */
    private fun extractIssueKey(prompt: String): String? {
        return issueKeyRegex.find(prompt.uppercase())?.value
    }

    /** 프롬프트가 개인화된 요청("내 이슈", "내가 담당" 등)인지 판별한다. */
    private fun isPersonalPrompt(normalizedPrompt: String): Boolean {
        if (personalIdentityPhrases.any { normalizedPrompt.contains(it) }) {
            return true
        }
        return personalIdentityRegexes.any { it.containsMatchIn(normalizedPrompt) }
    }

    /** 프롬프트에서 서비스명을 추출한다 (예: "payment-service 서비스"). */
    private fun extractServiceName(prompt: String): String? {
        return serviceRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /** 프롬프트에서 Jira 프로젝트 키를 추출한다 (예: "PROJ 프로젝트"). */
    private fun extractProjectKey(prompt: String): String? {
        return projectRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim().uppercase() }
            .firstOrNull { it.isNotBlank() }
    }

    /** 느슨한 규칙으로 프로젝트 키를 추출한다. 일반 단어와 stop word를 제외한다. */
    private fun extractLooseProjectKey(prompt: String): String? {
        return looseProjectRegex.find(prompt)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.uppercase()
            ?.let { candidate ->
                if (candidate.isBlank() || candidate.length < 2) return null
                if (candidate.length > 12) return null
                if (candidate in looseProjectStopWords) return null
                if (candidate.all { it.isDigit() }) return null
                return candidate
            }
    }

    /** "workspace/repository" 형식의 레포지토리 참조를 추출한다. */
    private fun extractRepository(prompt: String): Pair<String, String>? {
        val match = repositoryRegex.find(prompt) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** "xxx 저장소" 형식에서 레포지토리 slug를 추출한다. */
    private fun extractRepositorySlug(prompt: String): String? {
        return repositorySlugRegex.find(prompt)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 프롬프트에서 HTTP/HTTPS URL을 추출한다. */
    private fun extractUrl(prompt: String): String? {
        return urlRegex.find(prompt)?.value?.trim()
    }

    /** 따옴표('...' 또는 "...")로 감싼 키워드를 추출한다. */
    private fun extractQuotedKeyword(prompt: String): String? {
        return quotedKeywordRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /** 검색 키워드를 추출한다. 따옴표 키워드를 우선하고, 없으면 "키워드" 패턴을 사용한다. */
    private fun extractSearchKeyword(prompt: String): String? {
        return extractQuotedKeyword(prompt)
            ?: keywordRegexes.asSequence()
                .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
    }

    /** 소유자 조회에 사용할 키워드를 추출한다. 레포지토리, 서비스명, API명 등을 시도한다. */
    private fun extractOwnershipKeyword(prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null
        extractRepository(prompt)?.second?.takeIf { it.isNotBlank() }?.let { return it }
        extractRepositorySlug(prompt)?.let { return it }
        extractServiceName(prompt)?.let { return it }
        extractQuotedKeyword(prompt)?.let { return it }
        apiRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it }
        val normalized = prompt.lowercase()
        return when {
            normalized.contains("release note") -> "release note"
            normalized.contains("incident") -> "incident"
            normalized.contains("runbook") -> "runbook"
            normalized.contains("vacation") || normalized.contains("휴가") -> "휴가"
            normalized.contains("billing") -> "billing"
            normalized.contains("auth") -> "auth"
            normalized.contains("frontend") -> "frontend"
            normalized.contains("backend") -> "backend"
            normalized.contains("owner") -> "owner"
            else -> null
        }
    }

    /** 릴리즈 준비 팩에 사용할 릴리즈 이름을 추론한다. */
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

    /** URL에서 스펙 이름을 추론한다 (파일명 기반, 소문자/특수문자 정리). */
    private fun inferSpecName(url: String): String {
        val sanitized = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val base = sanitized.substringBeforeLast('.').ifBlank { "openapi-spec" }
        return base.lowercase()
            .replace(specNameSanitizeRegex, "-")
            .trim('-')
            .ifBlank { "openapi-spec" }
    }

    /** Swagger/OpenAPI 스펙 이름을 추출한다. 따옴표 키워드 → 패턴 매칭 순으로 시도한다. */
    private fun extractSwaggerSpecName(prompt: String): String? {
        extractQuotedKeyword(prompt)
            ?.trim()
            ?.takeIf(::looksLikeSwaggerSpecName)
            ?.let { return it }

        return swaggerSpecNameRegex.findAll(prompt)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull(::looksLikeSwaggerSpecName)
    }

    /** 후보 문자열이 Swagger 스펙 이름처럼 보이는지 판별한다 (하이픈/언더스코어 필수). */
    private fun looksLikeSwaggerSpecName(candidate: String): Boolean {
        val normalized = candidate.lowercase()
        if (normalized.isBlank()) return false
        if (normalized in swaggerSpecStopWords) return false
        if (normalized.startsWith("http")) return false
        if (candidate.startsWith("/")) return false
        if (candidate.contains('/')) return false
        return candidate.contains('-') || candidate.contains('_')
    }
}
