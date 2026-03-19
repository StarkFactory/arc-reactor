package com.arc.reactor.agent.impl

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
 * 엔티티 추출은 [WorkContextEntityExtractor]에, 인자 맵 빌드는 [WorkContextArgBuilder]에 위임한다.
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
 * @see WorkContextEntityExtractor 프롬프트에서 엔티티를 추출하는 책임
 * @see WorkContextArgBuilder 도구 인자 맵을 생성하는 책임
 */
internal object WorkContextForcedToolPlanner {

    // ── 타입 별칭 (가독성) ──

    private typealias Ctx = WorkContextEntityExtractor.ParsedPrompt

    // ── 힌트 키워드 셋 ──

    private val workOwnerHints = WorkContextPatterns.WORK_OWNER_HINTS
    private val ownershipDiscoveryHints = setOf(
        "누가 관리", "누가 쓰는지", "누가 개발", "주로 관리", "owner 문서",
        "owner를 확인", "담당 팀이 적힌"
    )
    private val missingAssigneeHints = WorkContextPatterns.MISSING_ASSIGNEE_HINTS
    private val workItemContextHints = WorkContextPatterns.WORK_ITEM_CONTEXT_HINTS
    private val workServiceContextHints = WorkContextPatterns.WORK_SERVICE_CONTEXT_HINTS
    private val workTeamStatusHints = setOf(
        "팀 상태", "team status", "주간 상태", "weekly status", "이번 주",
        "this week"
    )
    private val confluenceDiscoveryHints = setOf(
        "confluence", "컨플루언스", "wiki", "위키", "search", "검색",
        "keyword", "키워드", "어떤 문서", "목록"
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
        "관련 문서", "문서가 있으면", "문서가 있는지", "관련 문서가 있으면",
        "없으면 없다고", "링크와 함께", "핵심만 요약", "키워드로 검색",
        "search and summarize", "document if exists"
    )
    private val jiraBlockerHints = WorkContextPatterns.BLOCKER_HINTS
    private val jiraRecentIssueHints = setOf(
        "최근 jira 이슈", "최근 이슈", "최근 운영 이슈",
        "recent jira issue", "recent issues"
    )
    private val jiraUnassignedHints = setOf(
        "unassigned", "미할당", "담당자가 없는", "담당자 없는",
        "assignee 없는"
    )
    private val jiraDelayedHints = setOf(
        "늦어지고", "지연", "밀리고", "delay", "delayed", "overdue"
    )
    private val jiraStatusChangeHints = setOf(
        "상태가 많이 바뀐", "상태 변화", "status changed", "status changes"
    )
    private val jiraBriefingHints = setOf(
        "daily briefing", "아침 브리핑", "업무 브리핑", "데일리 브리핑",
        "daily digest", "오늘의 jira 브리핑", "오늘 jira 브리핑",
        "jira 브리핑", "jira briefing", "오늘의 jira briefing"
    )
    private val jiraProjectListHints = setOf(
        "jira 프로젝트 목록", "접근 가능한 jira 프로젝트 목록",
        "jira project list", "list jira projects"
    )
    private val jiraSearchHints = setOf("검색", "search")
    private val jiraReleaseHints = setOf(
        "release 관련", "release issues", "release related", "release"
    )
    private val jiraMyWorkHints = setOf(
        "my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈"
    )
    private val hybridReleaseRiskHints = setOf(
        "위험 신호", "risk signal", "release risk", "릴리즈 리스크",
        "risk digest"
    )
    private val hybridPriorityHints = WorkContextPatterns.HYBRID_PRIORITY_HINTS
    /** 명시적 브리핑 요청으로만 morning briefing 폴백을 트리거하는 키워드. */
    private val explicitBriefingFallbackHints = setOf(
        "브리핑", "briefing", "아침 요약", "오늘 현황",
        "현황 요약", "현황 정리", "상황 정리", "상황 요약"
    )
    private val reviewQueueHints = setOf(
        "review queue", "리뷰 대기열", "review sla", "리뷰 sla",
        "code review"
    )
    private val workReleaseReadinessHints = WorkContextPatterns.WORK_RELEASE_READINESS_HINTS
    private val workPersonalFocusHints = WorkContextPatterns.WORK_PERSONAL_FOCUS_HINTS
    private val workPersonalFocusGeneralHints = setOf(
        "내가 지금 해야 할 작업", "지금 해야 할 작업", "오늘 집중해야",
        "오늘 해야 할 일", "내가 오늘 집중해야", "내가 오늘 해야 할",
        "마감 전에 끝내", "끝내면 좋은 일", "미뤄도 되는 일",
        "우선순위 순", "open issue와 due soon", "due soon",
        "리스크가 큰 것", "집중해야 할 api 관련", "review queue를",
        "carry-over", "내일 아침 바로 봐야", "내 open issue",
        "오늘 브리핑", "morning briefing", "해야 할 일과 미뤄도 되는 일"
    )
    private val workPersonalLearningHints = WorkContextPatterns.WORK_PERSONAL_LEARNING_HINTS
    private val workPersonalLearningGeneralHints = setOf(
        "최근에 관여한 이슈와 문서", "최근 참여한 작업", "읽어야 할 runbook",
        "incident 문서", "최근에 본 문서", "이번 주 팀 변화",
        "봐야 할 pr과 문서",
        "jira와 bitbucket 기준으로 묶어", "알아야 할 이번 주 팀 변화",
        "최근 참여한 작업을 jira와 bitbucket 기준으로"
    )
    private val workPersonalInterruptHints = WorkContextPatterns.WORK_PERSONAL_INTERRUPT_HINTS
    private val workPersonalWrapupHints = WorkContextPatterns.WORK_PERSONAL_WRAPUP_HINTS
    private val bitbucketReviewRiskHints = WorkContextPatterns.REVIEW_RISK_HINTS
    private val bitbucketMyReviewHints = setOf(
        "내가 검토", "검토해야", "review for me", "needs review"
    )
    private val bitbucketMyReviewLateHints = setOf(
        "늦게 보고 있는 리뷰", "review queue", "내 review queue",
        "리뷰 대기열", "리뷰 sla 경고"
    )
    private val bitbucketMyAuthoredPrHints = setOf(
        "리뷰를 기다리게 만든 pr", "내가 만든 pr", "내 pr",
        "내 pull request", "내가 올린 pr"
    )
    private val bitbucketOpenPrHints = setOf(
        "열린 pr", "오픈 pr", "open pr", "open prs",
        "pull request 목록", "pr 목록"
    )
    private val bitbucketStalePrHints = setOf(
        "stale pr", "오래된 pr", "방치된 pr", "stale pull request"
    )
    private val bitbucketReviewQueueHints = WorkContextPatterns.REVIEW_QUEUE_HINTS
    private val bitbucketReviewSlaHints = WorkContextPatterns.REVIEW_SLA_HINTS
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
    private val swaggerWrongEndpointHints = WorkContextPatterns.WRONG_ENDPOINT_HINTS
    private val swaggerSummaryHints = WorkContextPatterns.SUMMARY_HINTS
    private val swaggerValidateHints = WorkContextPatterns.VALIDATE_HINTS
    private val swaggerDiscoveryHints = setOf(
        "endpoint", "엔드포인트", "schema", "스키마", "인증", "auth",
        "에러 응답", "error response", "파라미터", "parameter", "로드된 스펙",
        "load", "로드한 뒤"
    )
    private val personalDocumentHints = setOf(
        "휴가 규정", "남은 휴가", "내 이름 기준", "owner로 적혀", "회의록",
        "runbook", "incident 문서", "owner 문서", "서비스 owner", "api 문서"
    )
    private val preDeployReadinessHints = setOf(
        "배포 전에", "출시 전에", "release 전에", "pre-release"
    )

    // ── 힌트 매칭 헬퍼 ──

    /** 정규화된 문자열이 힌트 셋 중 하나라도 포함하면 true를 반환한다. */
    private fun String.matchesAny(hints: Set<String>): Boolean =
        hints.any { this.contains(it) }

    /** 하위 핸들러(blocker, briefing, release risk, 교차 소스 등)가 처리할 힌트가 있으면 true. */
    private val downstreamCrossSourceKeywords = setOf(
        "문서", "지식", "confluence",
        "장애", "위험", "standup", "스탠드업", "swagger", "openapi"
    )

    private fun hasDownstreamProjectHints(n: String): Boolean =
        n.matchesAny(jiraBlockerHints) ||
            n.matchesAny(jiraBriefingHints) ||
            n.matchesAny(hybridReleaseRiskHints) ||
            n.matchesAny(explicitBriefingFallbackHints) ||
            n.matchesAny(workReleaseReadinessHints) ||
            n.matchesAny(preDeployReadinessHints) ||
            n.matchesAny(downstreamCrossSourceKeywords)

    /** 미할당 힌트가 없고, 소유자/소유권 탐색 힌트가 있으면 true를 반환한다. */
    private fun hasOwnershipIntent(normalized: String): Boolean =
        !normalized.matchesAny(missingAssigneeHints) &&
            (normalized.matchesAny(workOwnerHints) ||
                normalized.matchesAny(ownershipDiscoveryHints))

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
        val clean = WorkContextEntityExtractor.stripEmoji(prompt)
        if (clean.isBlank()) return null
        val ctx = WorkContextEntityExtractor.parsePrompt(clean)

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
    private fun planOwnership(prompt: String, ctx: Ctx): ForcedToolCallPlan? {
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

        val jiraIssueContext = ctx.issueKey == null && (
            n.contains("이슈") || n.contains("jira") ||
                n.contains("프로젝트"))
        val hasOwnership = !n.matchesAny(missingAssigneeHints) &&
            n.matchesAny(workOwnerHints)
        if (jiraIssueContext && hasOwnership) return null

        if (hasOwnership) {
            val query = ctx.issueKey
                ?: WorkContextEntityExtractor.extractServiceName(prompt)
            if (query != null) {
                return ForcedToolCallPlan(
                    "work_owner_lookup", mapOf("query" to query)
                )
            }
        }

        return planOwnershipByEntity(prompt, ctx)
    }

    /** 저장소/서비스/키워드 기반 소유권 조회. */
    private fun planOwnershipByEntity(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!hasOwnershipIntent(n)) return null

        val repoSlug = ctx.repository?.second
            ?: WorkContextEntityExtractor.extractRepositorySlug(prompt)
        if (repoSlug != null) {
            return ForcedToolCallPlan(
                "work_owner_lookup",
                mapOf("query" to repoSlug, "entityType" to "repository")
            )
        }
        if (ctx.serviceName != null) {
            return ForcedToolCallPlan(
                "work_owner_lookup",
                mapOf(
                    "query" to ctx.serviceName,
                    "entityType" to "service"
                )
            )
        }
        val keyword = ctx.ownershipKeyword ?: "owner"
        return ForcedToolCallPlan(
            "confluence_search_by_text",
            mapOf("keyword" to keyword, "limit" to 10)
        )
    }

    /** 이슈/서비스 컨텍스트 조회 계획. */
    private fun planWorkContext(ctx: Ctx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (ctx.issueKey != null && n.matchesAny(workItemContextHints)) {
            return ForcedToolCallPlan(
                "work_item_context",
                mapOf("issueKey" to ctx.issueKey)
            )
        }
        if (ctx.serviceName != null &&
            n.matchesAny(workServiceContextHints)
        ) {
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
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("jira") && n.contains("confluence") &&
            n.matchesAny(workTeamStatusHints)
        ) {
            val keyword = if (n.contains("이번 주") ||
                n.contains("this week")
            ) "weekly" else "status"
            return ForcedToolCallPlan(
                "work_morning_briefing",
                buildMap {
                    ctx.inferredProjectKey?.let {
                        put("jiraProject", it)
                    }
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
                WorkContextArgBuilder
                    .buildStandupArgs(ctx.inferredProjectKey)
            )
        }
        return null
    }

    /** 릴리즈 준비팩 및 릴리즈 리스크 계획. */
    private fun planReadinessAndRisk(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        if (!ctx.normalized.matchesAny(workReleaseReadinessHints)) {
            return null
        }
        return ForcedToolCallPlan(
            "work_release_readiness_pack",
            WorkContextArgBuilder.buildReadinessPackArgs(
                prompt, ctx.projectKey, ctx.repository
            )
        )
    }

    /** 개인화 도구 계획 — 포커스, 학습, 인터럽트, 마감 정리 등. */
    private fun planPersonalTools(
        prompt: String,
        ctx: Ctx
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
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!ctx.isPersonal) return null

        if (n.contains("release risk") ||
            n.contains("릴리즈 위험") ||
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
            (n.contains("jira") || n.contains("due soon") ||
                n.contains("open issue"))
        ) {
            return ForcedToolCallPlan(
                "work_personal_focus_plan", mapOf("topN" to 5)
            )
        }
        return planPersonalDueAndRelease(prompt, ctx)
    }

    /** 개인화 마감/릴리즈/API Jira 검색 계획. */
    private fun planPersonalDueAndRelease(
        prompt: String,
        ctx: Ctx
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
    private fun planPersonalBitbucket(ctx: Ctx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!ctx.isPersonal) return null
        if (n.matchesAny(bitbucketMyAuthoredPrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_my_authored_prs",
                mapOf("reviewPendingOnly" to true)
            )
        }
        if (n.contains("morning briefing") ||
            n.contains("아침 브리핑") ||
            n.contains("개인화해서")
        ) {
            return ForcedToolCallPlan(
                "work_personal_focus_plan", mapOf("topN" to 5)
            )
        }
        return null
    }

    /** 개인화 일반 포커스 플랜 계획. */
    private fun planPersonalFocusGeneral(ctx: Ctx): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(workPersonalFocusGeneralHints)) {
            return null
        }
        val topN = if (ctx.normalized.contains("3개")) 3 else 5
        return ForcedToolCallPlan(
            "work_personal_focus_plan", mapOf("topN" to topN)
        )
    }

    /** 학습 다이제스트 계획 (직접 힌트 또는 개인화 일반 힌트). */
    private fun planPersonalLearning(ctx: Ctx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(workPersonalLearningHints)) {
            return ForcedToolCallPlan(
                "work_personal_learning_digest",
                WorkContextArgBuilder.buildLearningDigestArgs()
            )
        }
        if (ctx.isPersonal &&
            n.matchesAny(workPersonalLearningGeneralHints)
        ) {
            return ForcedToolCallPlan(
                "work_personal_learning_digest",
                WorkContextArgBuilder.buildLearningDigestArgs()
            )
        }
        return null
    }

    /** 인터럽트 가드 및 하루 마감 정리 계획. */
    private fun planPersonalInterruptAndWrapup(
        ctx: Ctx
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
    private fun planPersonalLateReview(ctx: Ctx): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(bitbucketMyReviewLateHints)) {
            return null
        }
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
    private fun planPersonalDocument(ctx: Ctx): ForcedToolCallPlan? {
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
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(confluenceSpaceListHints)) {
            return ForcedToolCallPlan(
                "confluence_list_spaces", emptyMap()
            )
        }
        if (n.matchesAny(confluenceSearchHints)) {
            val keyword =
                WorkContextEntityExtractor.extractQuotedKeyword(prompt)
                    ?: WorkContextEntityExtractor
                        .extractSearchKeyword(prompt)
            return ForcedToolCallPlan(
                "confluence_search_by_text",
                buildMap {
                    keyword?.let { put("keyword", it) }
                    put("limit", 10)
                }
            )
        }
        if (n.matchesAny(bitbucketRepositoryListHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_repositories", emptyMap()
            )
        }
        if (n.matchesAny(jiraProjectListHints)) {
            return ForcedToolCallPlan("jira_list_projects", emptyMap())
        }
        return planJiraSearch(prompt, ctx)
    }

    /** Jira 내 오픈 이슈 및 키워드 검색 계획. */
    private fun planJiraSearch(
        prompt: String,
        ctx: Ctx
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
        val searchKeyword =
            WorkContextEntityExtractor.extractSearchKeyword(prompt)
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

    /** 프로젝트 키 기반 Jira 조회 계획. */
    private fun planJiraProjectScoped(ctx: Ctx): ForcedToolCallPlan? {
        val pk = ctx.projectKey ?: return null
        val n = ctx.normalized

        if (n.matchesAny(jiraRecentIssueHints) ||
            n.matchesAny(jiraStatusChangeHints)
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
        if (n.matchesAny(jiraDelayedHints)) {
            return ForcedToolCallPlan(
                "work_morning_briefing",
                WorkContextArgBuilder.buildMorningBriefingArgs(pk)
            )
        }
        if (n.matchesAny(jiraReleaseHints) &&
            (n.matchesAny(jiraSearchHints) || n.contains("이슈"))
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
        if (n.matchesAny(jiraUnassignedHints)) {
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

    /** 레포지토리 기반 Bitbucket 도구 계획. */
    private fun planBitbucketRepoScoped(ctx: Ctx): ForcedToolCallPlan? {
        val repo = ctx.repository ?: return null
        val n = ctx.normalized
        val ws = repo.first
        val slug = repo.second

        if (n.matchesAny(bitbucketOpenPrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_prs",
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "state" to "OPEN"
                )
            )
        }
        if (n.matchesAny(bitbucketStalePrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_stale_prs",
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "staleDays" to 7
                )
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
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "slaHours" to 24
                )
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
    private fun planBitbucketPersonal(ctx: Ctx): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAny(bitbucketMyReviewHints)) return null
        return ForcedToolCallPlan("bitbucket_review_queue", emptyMap())
    }

    /** Bitbucket 명시 + 리뷰 리스크 계획. */
    private fun planMiscBitbucket(ctx: Ctx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("bitbucket") &&
            n.matchesAny(bitbucketReviewRiskHints)
        ) {
            return ForcedToolCallPlan(
                "bitbucket_review_sla_alerts",
                mapOf("slaHours" to 24)
            )
        }
        return null
    }

    /** API 변경 빈도 및 서비스/API 소유자 기타 조회 계획. */
    private fun planApiAndOwnerMisc(ctx: Ctx): ForcedToolCallPlan? {
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

    /** Swagger/OpenAPI 도구 계획 — 요약, 검증, 목록, 잘못된 엔드포인트. */
    private fun planSwagger(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val isSwaggerContext = n.contains("swagger") ||
            n.contains("openapi") ||
            n.contains("spec") || n.contains("스펙")

        if (isSwaggerContext && n.matchesAny(swaggerValidateHints)) {
            return ForcedToolCallPlan(
                "spec_validate",
                buildMap {
                    ctx.swaggerSpecName?.let { put("specName", it) }
                    ctx.specUrl?.let { put("url", it) }
                }
            )
        }
        if (ctx.specUrl == null && ctx.swaggerSpecName != null &&
            isSwaggerContext && n.matchesAny(swaggerSummaryHints) &&
            !n.contains("목록") && !n.contains("list")
        ) {
            return ForcedToolCallPlan(
                "spec_summary",
                mapOf(
                    "specName" to ctx.swaggerSpecName,
                    "scope" to "published"
                )
            )
        }
        if ((n.contains("swagger") || n.contains("api")) &&
            (n.contains("consumer") ||
                n.contains("schema를 어디서") ||
                n.contains("schema"))
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        if (isSwaggerContext &&
            n.matchesAny(swaggerWrongEndpointHints)
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return null
    }

    /** 하이브리드 릴리즈 리스크 + 키워드 문서 검색 계획. */
    private fun planHybridRiskAndDiscovery(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAny(hybridPriorityHints) &&
            n.matchesAny(jiraBlockerHints) &&
            n.matchesAny(reviewQueueHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                WorkContextArgBuilder.buildReleaseRiskArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }
        if (n.contains("jira") && n.contains("bitbucket") &&
            n.matchesAny(hybridReleaseRiskHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                WorkContextArgBuilder.buildReleaseRiskArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }

        val quotedKeyword =
            WorkContextEntityExtractor.extractQuotedKeyword(prompt)
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
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val pk = ctx.projectKey

        if (pk != null &&
            (n.contains("confluence") || n.contains("문서") ||
                n.contains("지식")) &&
            (n.contains("이슈") || n.contains("jira") ||
                n.contains("운영"))
        ) {
            return ForcedToolCallPlan(
                "work_morning_briefing",
                WorkContextArgBuilder.buildMorningBriefingArgs(pk)
            )
        }
        val hasStandup =
            n.contains("standup") || n.contains("스탠드업")
        if (pk != null && hasStandup &&
            n.contains("confluence") && n.contains("jira")
        ) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                WorkContextArgBuilder.buildStandupArgs(pk)
            )
        }
        if (hasStandup &&
            n.contains("confluence") && n.contains("jira")
        ) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                WorkContextArgBuilder.buildStandupArgs(null)
            )
        }
        if (ctx.inferredProjectKey != null && hasStandup &&
            (n.contains("바로 말해야") || n.contains("정리해줘"))
        ) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                WorkContextArgBuilder
                    .buildStandupArgs(ctx.inferredProjectKey)
            )
        }
        return null
    }

    /** 배포 전 준비, 릴리즈 리스크, 스펙 로드, 블로커, 브리핑, 폴백 계획. */
    private fun planPreDeployAndFallback(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (n.matchesAny(preDeployReadinessHints) &&
            (n.contains("문서") || n.contains("이슈"))
        ) {
            return ForcedToolCallPlan(
                "work_release_readiness_pack",
                WorkContextArgBuilder.buildReadinessPackArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }
        if (ctx.projectKey != null && ctx.repository != null &&
            n.matchesAny(hybridReleaseRiskHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                WorkContextArgBuilder.buildReleaseRiskArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }
        return planSpecLoadAndBriefingFallback(prompt, ctx)
    }

    /** 스펙 로드, 블로커, 브리핑 폴백 계획. */
    private fun planSpecLoadAndBriefingFallback(
        prompt: String,
        ctx: Ctx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (ctx.specUrl != null && (n.contains("swagger") ||
                n.contains("openapi") || n.contains("스펙"))
        ) {
            return ForcedToolCallPlan(
                "spec_load",
                mapOf(
                    "name" to
                        WorkContextArgBuilder.inferSpecName(ctx.specUrl),
                    "url" to ctx.specUrl,
                    "content" to null
                )
            )
        }
        if ((n.contains("로드된 스펙") ||
                n.contains("로컬에 로드된") ||
                n.contains("loaded spec")) &&
            n.matchesAny(swaggerDiscoveryHints)
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return planBlockerAndBriefingFallback(ctx)
    }

    /** 블로커 다이제스트 및 최종 브리핑 폴백 계획. */
    private fun planBlockerAndBriefingFallback(
        ctx: Ctx
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
        if (n.matchesAny(explicitBriefingFallbackHints)) {
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
