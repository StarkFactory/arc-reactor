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

    // ── 정규식 상수 ──

    /** 이모지 및 기호 유니코드를 제거한다. JQL 파싱 오류 방지. */
    private val emojiRegex = Regex(
        "[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}" +
            "\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}\\p{So}\\p{Cn}]"
    )
    private val multiSpaceRegex = Regex("\\s{2,}")

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
        "PATCH", "DELETE", "HEAD", "HTTP", "HTTPS", "MCP", "PDF", "URL", "JSON", "XML", "SQL",
        "UI", "UX"
    )
    private val repositoryRegex =
        Regex("\\b([A-Za-z0-9._-]{2,64})/([A-Za-z0-9._-]{2,64})\\b")
    private val repositorySlugRegex =
        Regex("([A-Za-z0-9._-]{2,64})\\s*저장소", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE)
    private val quotedKeywordRegexes = listOf(
        Regex("'([^']{2,80})'"),
        Regex("\"([^\"]{2,80})\"")
    )
    private val keywordRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*키워드"),
        Regex("\\bkeyword\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b", RegexOption.IGNORE_CASE)
    )
    private val swaggerSpecNameRegex =
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{2,63})\\b")
    private val swaggerSpecStopWords = setOf(
        "swagger", "openapi", "spec", "summary", "summarize", "schema", "endpoint",
        "security", "methods", "method", "detail", "details", "loaded", "local", "current",
        "show", "tell", "the", "and", "for", "with", "pet", "store", "user"
    )
    private val apiRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*api\\b", RegexOption.IGNORE_CASE),
        Regex("\\bapi\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b", RegexOption.IGNORE_CASE)
    )
    private val serviceRegexes = listOf(
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*서비스", RegexOption.IGNORE_CASE),
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*service\\b", RegexOption.IGNORE_CASE)
    )
    private val personalIdentityRegexes = listOf(
        Regex("(^|\\s)내\\s"),
        Regex(
            "(^|\\s)내(가|를|가요|가야|기준으로|기준|이름으로|이름|휴가|오픈|리뷰|jira|pr)" +
                "(\\b|\\s|$)"
        )
    )

    // ── 힌트 키워드 셋 ──

    private val workOwnerHints = setOf(
        "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
    )
    private val ownershipDiscoveryHints = setOf(
        "누가 관리", "누가 쓰는지", "누가 개발", "주로 관리", "owner 문서", "owner를 확인",
        "담당 팀이 적힌"
    )
    private val missingAssigneeHints = setOf(
        "담당자가 없는", "담당자 없는", "미할당", "unassigned", "assignee is empty",
        "assignee 없는"
    )
    private val workItemContextHints = setOf(
        "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr", "오픈 pr",
        "다음 액션", "next action"
    )
    private val workServiceContextHints = setOf(
        "서비스 상황", "서비스 현황", "service context", "service summary", "현재 상황",
        "현재 현황", "최근 jira", "최근 jira 이슈", "열린 pr", "오픈 pr", "관련 문서",
        "한 번에 요약", "요약해줘", "기준으로"
    )
    private val workTeamStatusHints = setOf(
        "팀 상태", "team status", "주간 상태", "weekly status", "이번 주", "this week"
    )
    private val confluenceDiscoveryHints = setOf(
        "confluence", "컨플루언스", "wiki", "위키", "search", "검색", "keyword", "키워드",
        "어떤 문서", "목록"
    )
    private val confluenceSpaceListHints = setOf(
        "confluence 스페이스 목록", "컨플루언스 스페이스 목록",
        "접근 가능한 confluence 스페이스 목록", "confluence spaces",
        "list confluence spaces", "space 목록",
        "confluence에 어떤 스페이스", "컨플루언스에 어떤 스페이스",
        "스페이스가 있어", "사용 가능한 confluence 스페이스",
        "접근 가능한 스페이스", "스페이스 보여"
    )
    private val confluenceSearchHints = setOf(
        "confluence에서 검색", "컨플루언스에서 검색",
        "confluence에서 찾아", "컨플루언스에서 찾아",
        "문서 검색해줘", "페이지 검색해줘",
        "confluence 검색", "컨플루언스 검색",
        "스페이스에서 검색", "스페이스에서 찾아",
        "confluence에서 문서", "컨플루언스에서 문서"
    )
    private val documentDiscoveryHints = setOf(
        "관련 문서", "문서가 있으면", "문서가 있는지", "관련 문서가 있으면", "없으면 없다고",
        "링크와 함께", "핵심만 요약", "키워드로 검색", "search and summarize",
        "document if exists"
    )
    private val jiraBlockerHints = setOf("blocker", "차단", "막힌")
    private val jiraRecentIssueHints = setOf(
        "최근 jira 이슈", "최근 이슈", "최근 운영 이슈", "recent jira issue", "recent issues"
    )
    private val jiraUnassignedHints = setOf(
        "unassigned", "미할당", "담당자가 없는", "담당자 없는", "assignee 없는"
    )
    private val jiraDelayedHints = setOf(
        "늦어지고", "지연", "밀리고", "delay", "delayed", "overdue"
    )
    private val jiraStatusChangeHints = setOf(
        "상태가 많이 바뀐", "상태 변화", "status changed", "status changes"
    )
    private val jiraBriefingHints = setOf(
        "daily briefing", "아침 브리핑", "업무 브리핑", "데일리 브리핑", "daily digest",
        "오늘의 jira 브리핑", "오늘 jira 브리핑", "jira 브리핑", "jira briefing",
        "오늘의 jira briefing"
    )
    private val jiraProjectListHints = setOf(
        "jira 프로젝트 목록", "접근 가능한 jira 프로젝트 목록", "jira project list",
        "list jira projects"
    )
    private val jiraSearchHints = setOf("검색", "search")
    private val jiraReleaseHints = setOf(
        "release 관련", "release issues", "release related", "release"
    )
    private val jiraMyWorkHints = setOf(
        "my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈"
    )
    private val hybridReleaseRiskHints = setOf(
        "위험 신호", "risk signal", "release risk", "릴리즈 리스크", "risk digest"
    )
    private val hybridPriorityHints = setOf(
        "우선순위", "priority", "priorities", "오늘 우선", "today priority"
    )
    private val reviewQueueHints = setOf(
        "review queue", "리뷰 대기열", "review sla", "리뷰 sla", "code review"
    )
    private val workReleaseReadinessHints = setOf(
        "release readiness", "readiness pack", "릴리즈 준비", "출시 준비", "readiness"
    )
    private val workPersonalFocusHints = setOf(
        "focus plan", "personal focus plan", "개인 focus plan", "개인 집중 계획",
        "오늘 집중 계획"
    )
    private val workPersonalFocusGeneralHints = setOf(
        "내가 지금 해야 할 작업", "지금 해야 할 작업", "오늘 집중해야", "오늘 해야 할 일",
        "내가 오늘 집중해야", "내가 오늘 해야 할", "마감 전에 끝내", "끝내면 좋은 일",
        "미뤄도 되는 일", "우선순위 순", "open issue와 due soon", "due soon",
        "리스크가 큰 것", "집중해야 할 api 관련", "review queue를", "carry-over",
        "내일 아침 바로 봐야", "내 open issue", "오늘 브리핑", "morning briefing",
        "해야 할 일과 미뤄도 되는 일"
    )
    private val workPersonalLearningHints = setOf(
        "learning digest", "personal learning digest", "학습 digest", "학습 다이제스트"
    )
    private val workPersonalLearningGeneralHints = setOf(
        "최근에 관여한 이슈와 문서", "최근 참여한 작업", "읽어야 할 runbook",
        "incident 문서", "최근에 본 문서", "이번 주 팀 변화", "봐야 할 pr과 문서",
        "jira와 bitbucket 기준으로 묶어", "알아야 할 이번 주 팀 변화",
        "최근 참여한 작업을 jira와 bitbucket 기준으로"
    )
    private val workPersonalInterruptHints = setOf(
        "interrupt guard", "personal interrupt guard", "interrupt plan", "인터럽트 가드",
        "집중 방해"
    )
    private val workPersonalWrapupHints = setOf(
        "end of day wrapup", "end-of-day wrapup", "eod wrapup", "wrapup", "wrap-up",
        "마감 정리", "하루 마감"
    )
    private val bitbucketReviewRiskHints = setOf(
        "review risk", "리뷰 리스크", "코드 리뷰 리스크"
    )
    private val bitbucketMyReviewHints = setOf(
        "내가 검토", "검토해야", "review for me", "needs review"
    )
    private val bitbucketMyReviewLateHints = setOf(
        "늦게 보고 있는 리뷰", "review queue", "내 review queue", "리뷰 대기열",
        "리뷰 sla 경고"
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
        "review queue", "리뷰 대기열", "검토 대기열", "리뷰가 필요한", "검토가 필요한",
        "needs review", "review needed"
    )
    private val bitbucketReviewSlaHints = setOf(
        "review sla", "리뷰 sla", "sla 경고", "리뷰 sla 경고"
    )
    private val bitbucketRepositoryListHints = setOf(
        "저장소 목록", "repository list", "list repositories", "repo 목록",
        "어떤 저장소", "사용 가능한 저장소", "접근 가능한 저장소",
        "저장소를 보여", "저장소가 있어", "repositories",
        "bitbucket 저장소", "bitbucket repo"
    )
    private val bitbucketBranchListHints = setOf(
        "branch 목록", "브랜치 목록", "list branches", "branches",
        "어떤 브랜치", "사용 가능한 브랜치", "접근 가능한 브랜치"
    )
    private val swaggerWrongEndpointHints = setOf(
        "wrong endpoint", "invalid endpoint", "잘못된 endpoint", "없는 endpoint"
    )
    private val swaggerSummaryHints = setOf("summary", "summarize", "요약", "정리")
    private val swaggerDiscoveryHints = setOf(
        "endpoint", "엔드포인트", "schema", "스키마", "인증", "auth", "에러 응답",
        "error response", "파라미터", "parameter", "로드된 스펙", "load", "로드한 뒤"
    )
    private val personalDocumentHints = setOf(
        "휴가 규정", "남은 휴가", "내 이름 기준", "owner로 적혀", "회의록", "runbook",
        "incident 문서", "owner 문서", "서비스 owner", "api 문서"
    )
    private val personalIdentityPhrases = setOf(
        "내가", "내 기준", "내 기준으로", "내 이름", "내 휴가", "내 오픈", "내 open",
        "내 review", "내 리뷰", "내 jira", "내 pr", "내 pull request", "내가 맡은",
        "내가 오늘", "내가 최근", "내 이름으로", "내가 담당", "내 기준으로 오늘", "내 owner"
    )
    private val preDeployReadinessHints = setOf(
        "배포 전에", "출시 전에", "release 전에", "pre-release"
    )

    // ── 힌트 매칭 헬퍼 ──

    /** 정규화된 문자열이 힌트 셋 중 하나라도 포함하면 true를 반환한다. */
    private fun String.matchesAny(hints: Set<String>): Boolean =
        hints.any { this.contains(it) }

    /** 미할당 힌트가 없고, 소유자/소유권 탐색 힌트가 있으면 true를 반환한다. */
    private fun hasOwnershipIntent(normalized: String): Boolean =
        !normalized.matchesAny(missingAssigneeHints) &&
            (normalized.matchesAny(workOwnerHints) ||
                normalized.matchesAny(ownershipDiscoveryHints))

    // ── 반복되는 인자 맵 빌더 ──

    /** 릴리즈 리스크 다이제스트 도구의 공통 인자 맵을 생성한다. */
    private fun buildReleaseRiskArgs(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): Map<String, Any?> = buildMap {
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

    /** 릴리즈 준비 팩 도구의 공통 인자 맵을 생성한다. */
    private fun buildReadinessPackArgs(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): Map<String, Any?> = buildMap {
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

    /** 아침 브리핑 도구의 공통 인자 맵을 생성한다. */
    private fun buildMorningBriefingArgs(
        projectKey: String,
        confluenceKeyword: String = "status"
    ): Map<String, Any?> = mapOf(
        "jiraProject" to projectKey,
        "confluenceKeyword" to confluenceKeyword,
        "reviewSlaHours" to 24,
        "dueSoonDays" to 7,
        "jiraMaxResults" to 20
    )

    /** 스탠드업 준비 도구의 공통 인자 맵을 생성한다. */
    private fun buildStandupArgs(projectKey: String?): Map<String, Any?> = buildMap {
        projectKey?.let { put("jiraProject", it) }
        put("daysLookback", 7)
        put("jiraMaxResults", 20)
    }

    /** 학습 다이제스트 도구의 공통 인자 맵을 생성한다. */
    private fun buildLearningDigestArgs(): Map<String, Any?> = mapOf(
        "lookbackDays" to 14,
        "topTopics" to 4,
        "docsPerTopic" to 2
    )

    // ── 프롬프트 분석에서 추출된 엔티티를 담는 컨테이너 ──

    /** [plan] 메서드 내에서 반복 추출을 방지하기 위한 파싱 결과 컨테이너. */
    private data class ParsedPrompt(
        val normalized: String,
        val issueKey: String?,
        val serviceName: String?,
        val projectKey: String?,
        val inferredProjectKey: String?,
        val repository: Pair<String, String>?,
        val specUrl: String?,
        val swaggerSpecName: String?,
        val ownershipKeyword: String?,
        val isPersonal: Boolean
    )

    /** 이모지 및 특수 유니코드 문자를 제거하여 정규식 매칭과 JQL 생성을 안전하게 한다. */
    private fun stripEmoji(text: String): String =
        emojiRegex.replace(text, "").replace(multiSpaceRegex, " ").trim()

    /** 원본 프롬프트에서 모든 엔티티를 일괄 추출한다. */
    private fun parsePrompt(prompt: String): ParsedPrompt {
        val normalized = prompt.lowercase()
        val projectKey = extractProjectKey(prompt)
        return ParsedPrompt(
            normalized = normalized,
            issueKey = extractIssueKey(prompt),
            serviceName = extractServiceName(prompt),
            projectKey = projectKey,
            inferredProjectKey = projectKey ?: extractLooseProjectKey(prompt),
            repository = extractRepository(prompt),
            specUrl = extractUrl(prompt),
            swaggerSpecName = extractSwaggerSpecName(prompt),
            ownershipKeyword = extractOwnershipKeyword(prompt),
            isPersonal = isPersonalPrompt(normalized)
        )
    }

    // ── 메인 계획 수립 ──

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
        val clean = stripEmoji(prompt)
        if (clean.isBlank()) return null
        val ctx = parsePrompt(clean)

        return planOwnership(clean, ctx)
            ?: planWorkContext(ctx)
            ?: planTeamBriefing(clean, ctx)
            ?: planReadinessAndRisk(clean, ctx)
            ?: planPersonalTools(clean, ctx)
            ?: planListAndSearch(clean, ctx)
            ?: planJiraProjectScoped(ctx)
            ?: planBitbucketRepoScoped(ctx)
            ?: planBitbucketPersonal(ctx)
            ?: planMiscBitbucket(ctx)
            ?: planApiAndOwnerMisc(ctx)
            ?: planSwagger(clean, ctx)
            ?: planHybridRiskAndDiscovery(clean, ctx)
            ?: planCrossSourceAndStandup(clean, ctx)
            ?: planPreDeployAndFallback(clean, ctx)
    }

    // ── 카테고리별 계획 수립 메서드 ──

    /** 소유자 조회 관련 계획 — 서비스 컨텍스트 + 소유자 조회 우선. */
    private fun planOwnership(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (ctx.serviceName != null &&
            n.matchesAny(workOwnerHints) &&
            (n.matchesAny(workItemContextHints) ||
                n.matchesAny(workServiceContextHints) ||
                n.contains("최근 이슈") || n.contains("관련 이슈"))
        ) {
            return ForcedToolCallPlan(
                "work_service_context",
                mapOf("service" to ctx.serviceName)
            )
        }

        // "담당자"가 Jira 이슈 검색 맥락에서 사용된 경우 소유자 조회 대신 Jira 검색으로 분기
        // 단, 명시적 이슈 키(PAY-123 등)가 있으면 해당 이슈의 소유자 조회가 의도이므로 스킵하지 않는다
        val jiraIssueContext = ctx.issueKey == null && (
            n.contains("이슈") || n.contains("jira") || n.contains("프로젝트"))
        val hasOwnership = !n.matchesAny(missingAssigneeHints) &&
            n.matchesAny(workOwnerHints)
        if (jiraIssueContext && hasOwnership) return null

        if (hasOwnership) {
            val query = ctx.issueKey ?: extractServiceName(prompt)
            if (query != null) {
                return ForcedToolCallPlan(
                    "work_owner_lookup",
                    mapOf("query" to query)
                )
            }
        }

        return planOwnershipByEntity(prompt, ctx)
    }

    /** 저장소·서비스·키워드 기반 소유권 조회. */
    private fun planOwnershipByEntity(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!hasOwnershipIntent(n)) return null

        val repoSlug = ctx.repository?.second ?: extractRepositorySlug(prompt)
        if (repoSlug != null) {
            return ForcedToolCallPlan(
                "work_owner_lookup",
                mapOf("query" to repoSlug, "entityType" to "repository")
            )
        }
        if (ctx.serviceName != null) {
            return ForcedToolCallPlan(
                "work_owner_lookup",
                mapOf("query" to ctx.serviceName, "entityType" to "service")
            )
        }
        val keyword = ctx.ownershipKeyword ?: "owner"
        return ForcedToolCallPlan(
            "confluence_search_by_text",
            mapOf("keyword" to keyword, "limit" to 10)
        )
    }

    /** 이슈/서비스 컨텍스트 조회 계획. */
    private fun planWorkContext(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (ctx.issueKey != null && n.matchesAny(workItemContextHints)) {
            return ForcedToolCallPlan(
                "work_item_context",
                mapOf("issueKey" to ctx.issueKey)
            )
        }
        if (ctx.serviceName != null && n.matchesAny(workServiceContextHints)) {
            return ForcedToolCallPlan(
                "work_service_context",
                mapOf("service" to ctx.serviceName)
            )
        }
        return null
    }

    /** Jira+Confluence 팀 상태 및 스탠드업 브리핑 계획. */
    private fun planTeamBriefing(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("jira") && n.contains("confluence") &&
            n.matchesAny(workTeamStatusHints)
        ) {
            val keyword = if (n.contains("이번 주") || n.contains("this week")) {
                "weekly"
            } else {
                "status"
            }
            return ForcedToolCallPlan(
                "work_morning_briefing",
                buildMap {
                    ctx.inferredProjectKey?.let { put("jiraProject", it) }
                    put("confluenceKeyword", keyword)
                    put("reviewSlaHours", 24)
                    put("dueSoonDays", 7)
                    put("jiraMaxResults", 20)
                }
            )
        }
        if (ctx.inferredProjectKey != null && n.contains("standup")) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                buildStandupArgs(ctx.inferredProjectKey)
            )
        }
        return null
    }

    /** 릴리즈 준비팩 및 릴리즈 리스크 계획. */
    private fun planReadinessAndRisk(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        if (!ctx.normalized.matchesAny(workReleaseReadinessHints)) return null
        return ForcedToolCallPlan(
            "work_release_readiness_pack",
            buildReadinessPackArgs(prompt, ctx.projectKey, ctx.repository)
        )
    }

    /** 개인화 도구 계획 — 포커스, 학습, 인터럽트, 마감 정리 등. */
    private fun planPersonalTools(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(workPersonalFocusHints)) {
            return ForcedToolCallPlan(
                "work_personal_focus_plan", mapOf("topN" to 5)
            )
        }
        return planPersonalJira(prompt, ctx)
            ?: planPersonalBitbucket(ctx)
            ?: planPersonalFocusGeneral(ctx)
            ?: planPersonalLearning(ctx)
            ?: planPersonalInterruptAndWrapup(ctx)
            ?: planPersonalLateReview(ctx)
            ?: planPersonalDocument(ctx)
    }

    /** 개인화 Jira 관련 계획. */
    private fun planPersonalJira(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!ctx.isPersonal) return null

        if (n.contains("release risk") || n.contains("릴리즈 위험") ||
            n.contains("release blocker")
        ) {
            return ForcedToolCallPlan(
                "jira_blocker_digest", mapOf("maxResults" to 25)
            )
        }
        if (n.contains("jira blocker") ||
            n.contains("제일 먼저 처리해야 할 jira blocker")
        ) {
            return ForcedToolCallPlan(
                "jira_blocker_digest", mapOf("maxResults" to 25)
            )
        }
        if (n.contains("리뷰 대기열") &&
            (n.contains("jira") || n.contains("due soon") || n.contains("open issue"))
        ) {
            return ForcedToolCallPlan(
                "work_personal_focus_plan", mapOf("topN" to 5)
            )
        }
        return planPersonalDueAndRelease(prompt, ctx)
    }

    /** 개인화 마감/릴리즈/API Jira 검색 계획. planPersonalJira에서만 호출된다. */
    private fun planPersonalDueAndRelease(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (n.contains("overdue") || n.contains("due soon") ||
            n.contains("마감 전에") || n.contains("마감이 가까")
        ) {
            return ForcedToolCallPlan(
                "jira_due_soon_issues",
                mapOf("days" to 7, "maxResults" to 20)
            )
        }
        if (n.contains("release 관련") &&
            (n.contains("jira 작업") || n.contains("내 jira"))
        ) {
            return ForcedToolCallPlan(
                "jira_search_my_issues_by_text",
                mapOf("keyword" to "release", "maxResults" to 10)
            )
        }
        if (n.contains("api 관련") &&
            (n.contains("작업") || n.contains("이슈"))
        ) {
            return ForcedToolCallPlan(
                "jira_search_my_issues_by_text",
                mapOf("keyword" to "api", "maxResults" to 10)
            )
        }
        if (n.contains("open issue") && n.contains("due soon")) {
            return ForcedToolCallPlan(
                "jira_daily_briefing",
                mapOf("dueSoonDays" to 7, "maxResults" to 20)
            )
        }
        return null
    }

    /** 개인화 Bitbucket 관련 계획. */
    private fun planPersonalBitbucket(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!ctx.isPersonal) return null
        if (n.matchesAny(bitbucketMyAuthoredPrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_my_authored_prs",
                mapOf("reviewPendingOnly" to true)
            )
        }
        if (n.contains("morning briefing") || n.contains("아침 브리핑") ||
            n.contains("개인화해서")
        ) {
            return ForcedToolCallPlan(
                "work_personal_focus_plan", mapOf("topN" to 5)
            )
        }
        return null
    }

    /** 개인화 일반 포커스 플랜 계획. */
    private fun planPersonalFocusGeneral(ctx: ParsedPrompt): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(workPersonalFocusGeneralHints)) return null
        val topN = if (ctx.normalized.contains("3개")) 3 else 5
        return ForcedToolCallPlan(
            "work_personal_focus_plan", mapOf("topN" to topN)
        )
    }

    /** 학습 다이제스트 계획 (직접 힌트 또는 개인화 일반 힌트). */
    private fun planPersonalLearning(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(workPersonalLearningHints)) {
            return ForcedToolCallPlan(
                "work_personal_learning_digest", buildLearningDigestArgs()
            )
        }
        if (ctx.isPersonal && n.matchesAny(workPersonalLearningGeneralHints)) {
            return ForcedToolCallPlan(
                "work_personal_learning_digest", buildLearningDigestArgs()
            )
        }
        return null
    }

    /** 인터럽트 가드 및 하루 마감 정리 계획. */
    private fun planPersonalInterruptAndWrapup(
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(workPersonalInterruptHints)) {
            return ForcedToolCallPlan(
                "work_personal_interrupt_guard",
                mapOf("maxInterrupts" to 5, "focusBlockMinutes" to 90)
            )
        }
        if (n.matchesAny(workPersonalWrapupHints)) {
            return ForcedToolCallPlan(
                "work_personal_end_of_day_wrapup",
                mapOf("lookbackDays" to 1, "tomorrowTopN" to 3)
            )
        }
        return null
    }

    /** 개인화 늦은 리뷰/SLA 계획. */
    private fun planPersonalLateReview(ctx: ParsedPrompt): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(bitbucketMyReviewLateHints)) return null
        val tool = if (ctx.normalized.contains("sla")) {
            "bitbucket_review_sla_alerts"
        } else {
            "bitbucket_review_queue"
        }
        val args = if (tool == "bitbucket_review_sla_alerts") {
            mapOf("slaHours" to 24)
        } else {
            emptyMap()
        }
        return ForcedToolCallPlan(tool, args)
    }

    /** 개인화 문서 검색 계획. */
    private fun planPersonalDocument(ctx: ParsedPrompt): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(personalDocumentHints)) return null
        return ForcedToolCallPlan(
            "work_personal_document_search",
            buildMap {
                val n = ctx.normalized
                when {
                    n.contains("휴가") -> put("keyword", "휴가")
                    n.contains("회의록") -> put("keyword", "회의록")
                    n.contains("runbook") -> put("keyword", "runbook")
                    n.contains("incident") -> put("keyword", "incident")
                    n.contains("owner") -> put("keyword", "owner")
                }
                put("limit", 5)
            }
        )
    }

    /** Confluence 스페이스 목록, Jira 프로젝트 목록, 검색 계획. */
    private fun planListAndSearch(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(confluenceSpaceListHints)) {
            return ForcedToolCallPlan("confluence_list_spaces", emptyMap())
        }
        if (n.matchesAny(confluenceSearchHints)) {
            val keyword = extractQuotedKeyword(prompt)
                ?: extractSearchKeyword(prompt)
            return ForcedToolCallPlan(
                "confluence_search_by_text",
                buildMap {
                    keyword?.let { put("keyword", it) }
                    put("limit", 10)
                }
            )
        }
        if (n.matchesAny(bitbucketRepositoryListHints)) {
            return ForcedToolCallPlan("bitbucket_list_repositories", emptyMap())
        }
        if (n.matchesAny(jiraProjectListHints)) {
            return ForcedToolCallPlan("jira_list_projects", emptyMap())
        }
        return planJiraSearch(prompt, ctx)
    }

    /** Jira 내 오픈 이슈 및 키워드 검색 계획. */
    private fun planJiraSearch(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if ((n.contains("jira") || ctx.projectKey != null) &&
            n.matchesAny(jiraMyWorkHints)
        ) {
            return ForcedToolCallPlan(
                "jira_my_open_issues",
                buildMap {
                    ctx.projectKey?.let { put("project", it) }
                    put("maxResults", 20)
                }
            )
        }
        val searchKeyword = extractSearchKeyword(prompt)
        if (n.contains("jira") && n.matchesAny(jiraSearchHints) &&
            (n.contains("키워드") || n.contains("keyword")) &&
            searchKeyword != null
        ) {
            return ForcedToolCallPlan(
                "jira_search_by_text",
                mapOf("keyword" to searchKeyword, "limit" to 10)
            )
        }
        return null
    }

    /** 프로젝트 키 기반 Jira 조회 계획 — 최근 이슈, 지연, 상태 변화, 릴리즈, 미할당. */
    private fun planJiraProjectScoped(
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val pk = ctx.projectKey ?: return null
        val n = ctx.normalized

        if (n.matchesAny(jiraRecentIssueHints) || n.matchesAny(jiraStatusChangeHints)) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to """project = "$pk" ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }
        if (n.matchesAny(jiraDelayedHints)) {
            return ForcedToolCallPlan(
                "work_morning_briefing", buildMorningBriefingArgs(pk)
            )
        }
        if (n.matchesAny(jiraReleaseHints) &&
            (n.matchesAny(jiraSearchHints) || n.contains("이슈"))
        ) {
            return ForcedToolCallPlan(
                "jira_search_by_text",
                mapOf("keyword" to "release", "project" to pk, "limit" to 10)
            )
        }
        if (n.matchesAny(jiraUnassignedHints)) {
            return ForcedToolCallPlan(
                "jira_search_issues",
                mapOf(
                    "jql" to """project = "$pk" AND assignee is EMPTY ORDER BY updated DESC""",
                    "maxResults" to 10
                )
            )
        }
        return null
    }

    /** 레포지토리 기반 Bitbucket 도구 계획. */
    private fun planBitbucketRepoScoped(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val repo = ctx.repository ?: return null
        val n = ctx.normalized
        val ws = repo.first
        val slug = repo.second

        if (n.matchesAny(bitbucketOpenPrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_prs",
                mapOf("workspace" to ws, "repo" to slug, "state" to "OPEN")
            )
        }
        if (n.matchesAny(bitbucketStalePrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_stale_prs",
                mapOf("workspace" to ws, "repo" to slug, "staleDays" to 7)
            )
        }
        if (n.matchesAny(bitbucketReviewQueueHints)) {
            return ForcedToolCallPlan(
                "bitbucket_review_queue",
                mapOf("workspace" to ws, "repo" to slug)
            )
        }
        if (n.matchesAny(bitbucketReviewSlaHints)) {
            return ForcedToolCallPlan(
                "bitbucket_review_sla_alerts",
                mapOf("workspace" to ws, "repo" to slug, "slaHours" to 24)
            )
        }
        if (n.matchesAny(bitbucketBranchListHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_branches",
                mapOf("workspace" to ws, "repo" to slug)
            )
        }
        return null
    }

    /** 개인화 Bitbucket 리뷰 큐 계획 (레포지토리 미지정). */
    private fun planBitbucketPersonal(ctx: ParsedPrompt): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(bitbucketMyReviewHints)) return null
        return ForcedToolCallPlan("bitbucket_review_queue", emptyMap())
    }

    /** Bitbucket 명시 + 리뷰 리스크 계획. */
    private fun planMiscBitbucket(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("bitbucket") && n.matchesAny(bitbucketReviewRiskHints)) {
            return ForcedToolCallPlan(
                "bitbucket_review_sla_alerts", mapOf("slaHours" to 24)
            )
        }
        return null
    }

    /** API 변경 빈도 및 서비스/API 소유자 기타 조회 계획. */
    private fun planApiAndOwnerMisc(ctx: ParsedPrompt): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("어떤 api가 지금 제일 많이 바뀌") ||
            (n.contains("which api") && n.contains("changed"))
        ) {
            return ForcedToolCallPlan(
                "jira_search_by_text",
                mapOf("keyword" to "api", "limit" to 10)
            )
        }
        if (n.contains("누가 어떤 서비스나 api를 맡") ||
            n.contains("owner 문서") || n.contains("owner가 누구")
        ) {
            return ForcedToolCallPlan(
                "confluence_search_by_text",
                mapOf("keyword" to "owner", "limit" to 10)
            )
        }
        return null
    }

    /** Swagger/OpenAPI 도구 계획 — 요약, 목록, 잘못된 엔드포인트. */
    private fun planSwagger(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val isSwaggerContext = n.contains("swagger") || n.contains("openapi") ||
            n.contains("spec") || n.contains("스펙")

        if (ctx.specUrl == null && ctx.swaggerSpecName != null &&
            isSwaggerContext && n.matchesAny(swaggerSummaryHints) &&
            !n.contains("목록") && !n.contains("list")
        ) {
            return ForcedToolCallPlan(
                "spec_summary",
                mapOf("specName" to ctx.swaggerSpecName, "scope" to "published")
            )
        }
        if ((n.contains("swagger") || n.contains("api")) &&
            (n.contains("consumer") || n.contains("schema를 어디서") ||
                n.contains("schema"))
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        if (isSwaggerContext && n.matchesAny(swaggerWrongEndpointHints)) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return null
    }

    /** 하이브리드 릴리즈 리스크 + 키워드 문서 검색 계획. */
    private fun planHybridRiskAndDiscovery(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(hybridPriorityHints) &&
            n.matchesAny(jiraBlockerHints) && n.matchesAny(reviewQueueHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                buildReleaseRiskArgs(prompt, ctx.projectKey, ctx.repository)
            )
        }
        if (n.contains("jira") && n.contains("bitbucket") &&
            n.matchesAny(hybridReleaseRiskHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                buildReleaseRiskArgs(prompt, ctx.projectKey, ctx.repository)
            )
        }

        val quotedKeyword = extractQuotedKeyword(prompt)
        if (quotedKeyword != null &&
            (n.matchesAny(confluenceDiscoveryHints) ||
                n.matchesAny(documentDiscoveryHints))
        ) {
            return ForcedToolCallPlan(
                "confluence_search_by_text",
                mapOf("keyword" to quotedKeyword, "limit" to 10)
            )
        }
        return null
    }

    /** 교차 소스(Confluence+Jira) 브리핑 및 스탠드업 계획. */
    private fun planCrossSourceAndStandup(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val pk = ctx.projectKey

        if (pk != null &&
            (n.contains("confluence") || n.contains("문서") || n.contains("지식")) &&
            (n.contains("이슈") || n.contains("jira") || n.contains("운영"))
        ) {
            return ForcedToolCallPlan(
                "work_morning_briefing", buildMorningBriefingArgs(pk)
            )
        }
        val hasStandup = n.contains("standup") || n.contains("스탠드업")
        if (pk != null && hasStandup &&
            n.contains("confluence") && n.contains("jira")
        ) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update", buildStandupArgs(pk)
            )
        }
        if (hasStandup && n.contains("confluence") && n.contains("jira")) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update", buildStandupArgs(null)
            )
        }
        if (ctx.inferredProjectKey != null && hasStandup &&
            (n.contains("바로 말해야") || n.contains("정리해줘"))
        ) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                buildStandupArgs(ctx.inferredProjectKey)
            )
        }
        return null
    }

    /** 배포 전 준비, 릴리즈 리스크, 스펙 로드, 블로커, 브리핑, 폴백 계획. */
    private fun planPreDeployAndFallback(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (n.matchesAny(preDeployReadinessHints) &&
            (n.contains("문서") || n.contains("이슈"))
        ) {
            return ForcedToolCallPlan(
                "work_release_readiness_pack",
                buildReadinessPackArgs(prompt, ctx.projectKey, ctx.repository)
            )
        }
        if (ctx.projectKey != null && ctx.repository != null &&
            n.matchesAny(hybridReleaseRiskHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                buildReleaseRiskArgs(prompt, ctx.projectKey, ctx.repository)
            )
        }
        return planSpecLoadAndBriefingFallback(prompt, ctx)
    }

    /** 스펙 로드, 블로커, 브리핑 폴백 계획. */
    private fun planSpecLoadAndBriefingFallback(
        prompt: String,
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (ctx.specUrl != null && (n.contains("swagger") ||
                n.contains("openapi") || n.contains("스펙"))
        ) {
            return ForcedToolCallPlan(
                "spec_load",
                mapOf(
                    "name" to inferSpecName(ctx.specUrl),
                    "url" to ctx.specUrl,
                    "content" to null
                )
            )
        }
        if ((n.contains("로드된 스펙") || n.contains("로컬에 로드된") ||
                n.contains("loaded spec")) && n.matchesAny(swaggerDiscoveryHints)
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return planBlockerAndBriefingFallback(ctx)
    }

    /** 블로커 다이제스트 및 최종 브리핑 폴백 계획. */
    private fun planBlockerAndBriefingFallback(
        ctx: ParsedPrompt
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val ipk = ctx.inferredProjectKey ?: return null

        if (n.matchesAny(jiraBlockerHints)) {
            return ForcedToolCallPlan(
                "jira_blocker_digest",
                mapOf("project" to ipk, "maxResults" to 25)
            )
        }
        if (n.matchesAny(jiraBriefingHints)) {
            if (n.contains("업무 브리핑") || n.contains("work briefing")) {
                return ForcedToolCallPlan(
                    "work_morning_briefing", buildMorningBriefingArgs(ipk)
                )
            }
            return ForcedToolCallPlan(
                "jira_daily_briefing",
                mapOf("project" to ipk, "dueSoonDays" to 3, "maxResults" to 30)
            )
        }
        // Jira 이슈 검색 의도가 있으면 morning briefing 폴백 스킵
        val jiraSearchIntent = n.contains("이슈") || n.contains("검색") ||
            n.contains("찾아") || n.contains("필터") ||
            n.contains("assignee") || n.contains("issue")
        if (jiraSearchIntent) return null

        if (n.contains("오늘") || n.contains("이번 주") || n.contains("현재") ||
            n.contains("상태") || n.contains("장애") || n.contains("위험") ||
            n.contains("우선순위") || n.matchesAny(workTeamStatusHints)
        ) {
            val keyword = if (n.contains("장애") || n.contains("위험")) {
                "risk"
            } else {
                "status"
            }
            return ForcedToolCallPlan(
                "work_morning_briefing", buildMorningBriefingArgs(ipk, keyword)
            )
        }
        return null
    }

    // ── 프롬프트에서 엔티티를 추출하는 헬퍼 메서드들 ──

    /** Jira 이슈 키(예: PROJ-123)를 추출한다. */
    private fun extractIssueKey(prompt: String): String? =
        issueKeyRegex.find(prompt.uppercase())?.value

    /** 프롬프트가 개인화된 요청("내 이슈", "내가 담당" 등)인지 판별한다. */
    private fun isPersonalPrompt(normalizedPrompt: String): Boolean =
        normalizedPrompt.matchesAny(personalIdentityPhrases) ||
            personalIdentityRegexes.any { it.containsMatchIn(normalizedPrompt) }

    /** 프롬프트에서 서비스명을 추출한다 (예: "payment-service 서비스"). */
    private fun extractServiceName(prompt: String): String? =
        extractFirstGroupMatch(prompt, serviceRegexes)

    /** 프롬프트에서 Jira 프로젝트 키를 추출한다 (예: "PROJ 프로젝트"). */
    private fun extractProjectKey(prompt: String): String? =
        extractFirstGroupMatch(prompt, projectRegexes)?.uppercase()

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
    private fun extractRepositorySlug(prompt: String): String? =
        repositorySlugRegex.find(prompt)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotBlank() }

    /** 프롬프트에서 HTTP/HTTPS URL을 추출한다. */
    private fun extractUrl(prompt: String): String? =
        urlRegex.find(prompt)?.value?.trim()

    /** 따옴표('...' 또는 "...")로 감싼 키워드를 추출한다. */
    private fun extractQuotedKeyword(prompt: String): String? =
        extractFirstGroupMatch(prompt, quotedKeywordRegexes)

    /** 검색 키워드를 추출한다. 따옴표 키워드를 우선하고, 없으면 "키워드" 패턴을 사용한다. */
    private fun extractSearchKeyword(prompt: String): String? =
        extractQuotedKeyword(prompt)
            ?: extractFirstGroupMatch(prompt, keywordRegexes)

    /** 정규식 목록에서 첫 번째 그룹 매칭 결과를 반환하는 공통 헬퍼. */
    private fun extractFirstGroupMatch(
        input: String,
        regexes: List<Regex>
    ): String? = regexes.asSequence()
        .mapNotNull { regex -> regex.find(input)?.groupValues?.getOrNull(1) }
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }

    /** 소유자 조회에 사용할 키워드를 추출한다. */
    private fun extractOwnershipKeyword(prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null
        extractRepository(prompt)?.second?.takeIf { it.isNotBlank() }
            ?.let { return it }
        extractRepositorySlug(prompt)?.let { return it }
        extractServiceName(prompt)?.let { return it }
        extractQuotedKeyword(prompt)?.let { return it }
        extractFirstGroupMatch(prompt, apiRegexes)?.let { return it }
        return inferOwnershipFallback(prompt.lowercase())
    }

    /** 소유권 키워드의 폴백 추론 (일반 키워드 매칭). */
    private fun inferOwnershipFallback(normalized: String): String? = when {
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

    /** 릴리즈 준비 팩에 사용할 릴리즈 이름을 추론한다. */
    private fun inferReleaseName(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): String = extractQuotedKeyword(prompt)
        ?: projectKey
        ?: repository?.second
        ?: "release-readiness"

    /** URL에서 스펙 이름을 추론한다 (파일명 기반, 소문자/특수문자 정리). */
    private fun inferSpecName(url: String): String {
        val sanitized = url.substringAfterLast('/')
            .substringBefore('?').substringBefore('#')
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

    /** 후보 문자열이 Swagger 스펙 이름처럼 보이는지 판별한다. */
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
