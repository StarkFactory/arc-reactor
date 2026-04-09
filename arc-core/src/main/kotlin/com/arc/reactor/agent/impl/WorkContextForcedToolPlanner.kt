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
 * 도메인별 계획 수립은 하위 플래너에 위임한다:
 * - [WorkContextJiraPlanner] — Jira 검색, 프로젝트 스코프, 블로커/브리핑 폴백
 * - [WorkContextBitbucketPlanner] — 레포지토리 스코프, 개인 리뷰, 리뷰 리스크
 * - [WorkContextPersonalizationPlanner] — 포커스 플랜, 학습, 인터럽트, 마감 정리
 * - [WorkContextDiscoveryPlanner] — 목록 조회, Swagger, 하이브리드 리스크, 교차 소스
 *
 * @see SystemPromptBuilder 시스템 프롬프트에서 도구 호출 강제 지시를 추가하는 대응 역할
 * @see SpringAiAgentExecutor ReAct 루프에서 강제 도구 호출 계획을 실행
 * @see WorkContextEntityExtractor 프롬프트에서 엔티티를 추출하는 책임
 * @see WorkContextArgBuilder 도구 인자 맵을 생성하는 책임
 */
internal object WorkContextForcedToolPlanner {

    // ── 힌트 키워드 셋 (오케스트레이터 전용) ──

    private val ownershipDiscoveryHints = setOf(
        "누가 관리", "누가 쓰는지", "누가 개발", "주로 관리", "owner 문서",
        "owner를 확인", "담당 팀이 적힌"
    )
    private val workTeamStatusHints = setOf(
        "팀 상태", "team status", "주간 상태", "weekly status", "이번 주",
        "this week"
    )

    /** 하위 핸들러(blocker, briefing, release risk, 교차 소스 등)가 처리할 힌트가 있으면 true. */
    private val downstreamCrossSourceKeywords = setOf(
        "문서", "지식", "confluence",
        "장애", "위험", "standup", "스탠드업", "swagger", "openapi"
    )

    private fun hasDownstreamProjectHints(n: String): Boolean =
        n.matchesAnyHint(WorkContextPatterns.BLOCKER_HINTS) ||
            n.matchesAnyHint(WorkContextPatterns.JIRA_BRIEFING_HINTS) ||
            n.matchesAnyHint(WorkContextPatterns.HYBRID_RELEASE_RISK_HINTS) ||
            n.matchesAnyHint(WorkContextPatterns.EXPLICIT_BRIEFING_FALLBACK_HINTS) ||
            n.matchesAnyHint(WorkContextPatterns.WORK_RELEASE_READINESS_HINTS) ||
            n.matchesAnyHint(WorkContextPatterns.PRE_DEPLOY_READINESS_HINTS) ||
            n.matchesAnyHint(downstreamCrossSourceKeywords)

    /** 미할당 힌트가 없고, 소유자/소유권 탐색 힌트가 있으면 true를 반환한다. */
    private fun hasOwnershipIntent(normalized: String): Boolean =
        !normalized.matchesAnyHint(WorkContextPatterns.MISSING_ASSIGNEE_HINTS) &&
            (normalized.matchesAnyHint(WorkContextPatterns.WORK_OWNER_HINTS) ||
                normalized.matchesAnyHint(ownershipDiscoveryHints))

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
            ?: WorkContextPersonalizationPlanner
                .planPersonalTools(clean, ctx)
            ?: WorkContextDiscoveryPlanner.planListAndSearch(clean, ctx)
            ?: WorkContextJiraPlanner.planJiraSearch(clean, ctx)
            ?: WorkContextJiraPlanner.planJiraProjectScoped(
                ctx, ::hasDownstreamProjectHints
            )
            ?: WorkContextBitbucketPlanner.planBitbucketRepoScoped(ctx)
            ?: WorkContextBitbucketPlanner.planBitbucketPersonal(ctx)
            ?: WorkContextBitbucketPlanner.planMiscBitbucket(ctx)
            ?: planApiAndOwnerMisc(ctx)
            ?: WorkContextDiscoveryPlanner.planSwagger(clean, ctx)
            ?: WorkContextDiscoveryPlanner
                .planHybridRiskAndDiscovery(clean, ctx)
            ?: WorkContextDiscoveryPlanner
                .planCrossSourceAndStandup(clean, ctx)
            ?: planPreDeployAndFallback(clean, ctx)
    }

    // ── 오케스트레이터 전용 계획 수립 메서드 ──

    /** 소유자 조회 관련 계획 — 서비스 컨텍스트 + 소유자 조회 우선. */
    private fun planOwnership(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (ctx.serviceName != null &&
            n.matchesAnyHint(WorkContextPatterns.WORK_OWNER_HINTS) &&
            (n.matchesAnyHint(WorkContextPatterns.WORK_ITEM_CONTEXT_HINTS) ||
                n.matchesAnyHint(WorkContextPatterns.WORK_SERVICE_CONTEXT_HINTS) ||
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
        val hasOwnership =
            !n.matchesAnyHint(WorkContextPatterns.MISSING_ASSIGNEE_HINTS) &&
                n.matchesAnyHint(WorkContextPatterns.WORK_OWNER_HINTS)
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
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!hasOwnershipIntent(n)) return null

        val repoSlug = ctx.repositorySlug
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
    private fun planWorkContext(ctx: PlannerCtx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (ctx.issueKey != null &&
            n.matchesAnyHint(WorkContextPatterns.WORK_ITEM_CONTEXT_HINTS)
        ) {
            return ForcedToolCallPlan(
                "work_item_context",
                mapOf("issueKey" to ctx.issueKey)
            )
        }
        if (ctx.serviceName != null &&
            n.matchesAnyHint(WorkContextPatterns.WORK_SERVICE_CONTEXT_HINTS)
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
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("jira") && n.contains("confluence") &&
            n.matchesAnyHint(workTeamStatusHints)
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
        val hasStandupHint = n.contains("standup") || n.contains("스탠드업") ||
            n.contains("데일리 스크럼") || n.contains("스크럼 준비") ||
            n.contains("일일 업무 보고")
        if (ctx.inferredProjectKey != null && hasStandupHint) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                WorkContextArgBuilder
                    .buildStandupArgs(ctx.inferredProjectKey)
            )
        }
        // 프로젝트 키 없이 스탠드업 단독 요청 — 기본 프로파일로 처리
        if (hasStandupHint) {
            return ForcedToolCallPlan(
                "work_prepare_standup_update",
                WorkContextArgBuilder.buildStandupArgs(null)
            )
        }
        return null
    }

    /** 릴리즈 준비팩 및 릴리즈 리스크 계획. */
    private fun planReadinessAndRisk(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        if (!ctx.normalized.matchesAnyHint(
                WorkContextPatterns.WORK_RELEASE_READINESS_HINTS
            )
        ) {
            return null
        }
        return ForcedToolCallPlan(
            "work_release_readiness_pack",
            WorkContextArgBuilder.buildReadinessPackArgs(
                prompt, ctx.projectKey, ctx.repository
            )
        )
    }

    /** API 변경 빈도 및 서비스/API 소유자 기타 조회 계획. */
    private fun planApiAndOwnerMisc(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
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

    /** 배포 전 준비, 릴리즈 리스크, 스펙 로드, 블로커, 브리핑, 폴백 계획. */
    private fun planPreDeployAndFallback(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized

        if (n.matchesAnyHint(WorkContextPatterns.PRE_DEPLOY_READINESS_HINTS) &&
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
            n.matchesAnyHint(WorkContextPatterns.HYBRID_RELEASE_RISK_HINTS)
        ) {
            return ForcedToolCallPlan(
                "work_release_risk_digest",
                WorkContextArgBuilder.buildReleaseRiskArgs(
                    prompt, ctx.projectKey, ctx.repository
                )
            )
        }
        return WorkContextDiscoveryPlanner
            .planSpecLoadAndBriefingFallback(prompt, ctx)
            ?: WorkContextJiraPlanner.planBlockerAndBriefingFallback(ctx)
    }
}
