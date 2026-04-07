package com.arc.reactor.agent.impl

/**
 * 검색/탐색 도메인 강제 도구 호출 계획 — 목록 조회, Swagger, 하이브리드 리스크, 교차 소스를 담당한다.
 *
 * [WorkContextForcedToolPlanner]의 plan() 체인에서 검색/탐색 관련 분기를 처리한다.
 *
 * @see WorkContextForcedToolPlanner 오케스트레이터
 */
internal object WorkContextDiscoveryPlanner {

    // ── 힌트 키워드 셋 ──

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
        "confluence에서 문서", "컨플루언스에서 문서",
        "위키에서 찾아", "위키에서 검색", "위키 검색", "위키 찾아",
        "위키에서 문서", "위키 문서"
    )

    /**
     * Confluence 컨텍스트 힌트 — 쿼리가 Confluence/wiki 관련임을 나타내는 키워드.
     * 단독 "문서 검색" 같은 짧은 쿼리가 Confluence로 라우팅되려면
     * 이 힌트 중 하나와 [confluenceActionHints] 중 하나가 모두 매칭되어야 한다.
     */
    private val confluenceContextHints = setOf(
        "confluence", "컨플루언스", "wiki", "위키", "문서", "페이지", "page"
    )

    /** Confluence 액션 힌트 — 검색/조회 의도를 나타내는 키워드. */
    private val confluenceActionHints = setOf(
        "검색", "찾아", "조회", "search", "find", "보여줘", "보여"
    )
    private val bitbucketRepositoryListHints = setOf(
        "저장소 목록", "repository list", "list repositories", "repo 목록",
        "어떤 저장소", "사용 가능한 저장소", "접근 가능한 저장소",
        "저장소를 보여", "저장소가 있어", "repositories",
        "bitbucket 저장소", "bitbucket repo"
    )
    private val jiraProjectListHints = setOf(
        "jira 프로젝트 목록", "접근 가능한 jira 프로젝트 목록",
        "jira project list", "list jira projects"
    )
    private val swaggerDiscoveryHints = setOf(
        "endpoint", "엔드포인트", "schema", "스키마", "인증", "auth",
        "에러 응답", "error response", "파라미터", "parameter", "로드된 스펙",
        "load", "로드한 뒤"
    )

    /** Swagger 일반 액션 힌트 — 조회/확인 의도를 나타내는 짧은 쿼리용 폴백. */
    private val swaggerActionHints = setOf(
        "조회", "확인", "보여", "알려", "검색", "찾아",
        "show", "check", "search", "find", "look"
    )
    private val reviewQueueHints = setOf(
        "review queue", "리뷰 대기열", "review sla", "리뷰 sla",
        "code review"
    )
    private val confluenceDiscoveryHints = setOf(
        "confluence", "컨플루언스", "wiki", "위키", "search", "검색",
        "keyword", "키워드", "어떤 문서", "목록"
    )
    private val documentDiscoveryHints = setOf(
        "관련 문서", "문서가 있으면", "문서가 있는지", "관련 문서가 있으면",
        "없으면 없다고", "링크와 함께", "핵심만 요약", "키워드로 검색",
        "search and summarize", "document if exists"
    )

    // ── 목록 조회 및 검색 계획 ──

    /** Confluence 스페이스 목록, Bitbucket 저장소 목록, Jira 프로젝트 목록, 검색 계획. */
    fun planListAndSearch(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAnyHint(confluenceSpaceListHints)) {
            return ForcedToolCallPlan(
                "confluence_list_spaces", emptyMap()
            )
        }
        if (n.matchesAnyHint(confluenceSearchHints)) {
            return buildConfluenceSearchPlan(prompt)
        }
        // 짧은 쿼리 폴백: "컨플루언스 문서 검색", "위키에서 문서 찾아줘" 등
        // Confluence 컨텍스트 + 액션 힌트가 모두 있으면 검색으로 강제
        if (n.matchesAnyHint(confluenceContextHints) &&
            n.matchesAnyHint(confluenceActionHints)
        ) {
            return buildConfluenceSearchPlan(prompt)
        }
        if (n.matchesAnyHint(bitbucketRepositoryListHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_repositories", emptyMap()
            )
        }
        if (n.matchesAnyHint(jiraProjectListHints)) {
            return ForcedToolCallPlan("jira_list_projects", emptyMap())
        }
        return null
    }

    /** Confluence 검색 계획을 빌드한다 — 키워드 추출 포함. */
    private fun buildConfluenceSearchPlan(
        prompt: String
    ): ForcedToolCallPlan {
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

    // ── Swagger/OpenAPI 계획 ──

    /** Swagger/OpenAPI 도구 계획 — 요약, 검증, 목록, 잘못된 엔드포인트. */
    fun planSwagger(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        val isSwaggerContext = n.contains("swagger") ||
            n.contains("openapi") || n.contains("스웨거") ||
            n.contains("spec") || n.contains("스펙") ||
            n.contains("api 문서") || n.contains("api 스펙")

        if (isSwaggerContext &&
            n.matchesAnyHint(WorkContextPatterns.VALIDATE_HINTS)
        ) {
            return ForcedToolCallPlan(
                "spec_validate",
                buildMap {
                    ctx.swaggerSpecName?.let { put("specName", it) }
                    ctx.specUrl?.let { put("url", it) }
                }
            )
        }
        if (ctx.specUrl == null && ctx.swaggerSpecName != null &&
            isSwaggerContext &&
            n.matchesAnyHint(WorkContextPatterns.SUMMARY_HINTS) &&
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
            n.matchesAnyHint(WorkContextPatterns.WRONG_ENDPOINT_HINTS)
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        // 짧은 쿼리 폴백: "API 스펙 조회해줘", "스웨거 확인" 등
        // Swagger 컨텍스트 + 일반 액션 힌트가 있으면 spec_list → spec_summary 체인
        if (isSwaggerContext && n.matchesAnyHint(swaggerActionHints)) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return null
    }

    // ── 하이브리드 리스크 및 문서 검색 계획 ──

    /** 하이브리드 릴리즈 리스크 + 키워드 문서 검색 계획. */
    fun planHybridRiskAndDiscovery(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAnyHint(WorkContextPatterns.HYBRID_PRIORITY_HINTS) &&
            n.matchesAnyHint(WorkContextPatterns.BLOCKER_HINTS) &&
            n.matchesAnyHint(reviewQueueHints)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                WorkContextArgBuilder.buildReleaseRiskArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }
        if (n.contains("jira") && n.contains("bitbucket") &&
            n.matchesAnyHint(
                WorkContextPatterns.HYBRID_RELEASE_RISK_HINTS
            )
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
            (n.matchesAnyHint(confluenceDiscoveryHints) ||
                n.matchesAnyHint(documentDiscoveryHints))
        ) {
            return ForcedToolCallPlan(
                "confluence_search_by_text",
                mapOf("keyword" to quotedKeyword, "limit" to 10)
            )
        }
        return null
    }

    // ── 교차 소스 및 스탠드업 계획 ──

    /** 교차 소스(Confluence+Jira) 브리핑 및 스탠드업 계획. */
    fun planCrossSourceAndStandup(
        prompt: String,
        ctx: PlannerCtx
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

    // ── 스펙 로드 폴백 계획 ──

    /** 스펙 로드 및 로드된 스펙 목록 폴백 계획. */
    fun planSpecLoadAndBriefingFallback(
        prompt: String,
        ctx: PlannerCtx
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
            n.matchesAnyHint(swaggerDiscoveryHints)
        ) {
            return ForcedToolCallPlan("spec_list", emptyMap())
        }
        return null
    }
}
