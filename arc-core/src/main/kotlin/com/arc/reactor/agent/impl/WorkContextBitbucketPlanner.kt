package com.arc.reactor.agent.impl

/**
 * Bitbucket 도메인 강제 도구 호출 계획 — 레포지토리 스코프, 개인 리뷰, 기타 Bitbucket 도구를 담당한다.
 *
 * [WorkContextForcedToolPlanner]의 plan() 체인에서 Bitbucket 관련 분기를 처리한다.
 *
 * @see WorkContextForcedToolPlanner 오케스트레이터
 */
internal object WorkContextBitbucketPlanner {

    // ── 힌트 키워드 셋 ──

    private val bitbucketOpenPrHints = setOf(
        "열린 pr", "오픈 pr", "open pr", "open prs",
        "pull request 목록", "pr 목록"
    )
    private val bitbucketStalePrHints = setOf(
        "stale pr", "오래된 pr", "방치된 pr", "stale pull request"
    )
    private val bitbucketBranchListHints = setOf(
        "branch 목록", "브랜치 목록", "list branches", "branches",
        "어떤 브랜치", "사용 가능한 브랜치", "접근 가능한 브랜치"
    )
    private val bitbucketMyReviewHints = setOf(
        "내가 검토", "검토해야", "review for me", "needs review"
    )

    // ── 레포지토리 스코프 Bitbucket 계획 ──

    /** 레포지토리 기반 Bitbucket 도구 계획. */
    fun planBitbucketRepoScoped(ctx: PlannerCtx): ForcedToolCallPlan? {
        val repo = ctx.repository ?: return null
        val n = ctx.normalized
        val ws = repo.first
        val slug = repo.second

        if (n.matchesAnyHint(bitbucketOpenPrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_prs",
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "state" to "OPEN"
                )
            )
        }
        if (n.matchesAnyHint(bitbucketStalePrHints)) {
            return ForcedToolCallPlan(
                "bitbucket_stale_prs",
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "staleDays" to 7
                )
            )
        }
        if (n.matchesAnyHint(WorkContextPatterns.REVIEW_QUEUE_HINTS)) {
            return ForcedToolCallPlan(
                "bitbucket_review_queue",
                mapOf("workspace" to ws, "repo" to slug)
            )
        }
        if (n.matchesAnyHint(WorkContextPatterns.REVIEW_SLA_HINTS)) {
            return ForcedToolCallPlan(
                "bitbucket_review_sla_alerts",
                mapOf(
                    "workspace" to ws,
                    "repo" to slug,
                    "slaHours" to 24
                )
            )
        }
        if (n.matchesAnyHint(bitbucketBranchListHints)) {
            return ForcedToolCallPlan(
                "bitbucket_list_branches",
                mapOf("workspace" to ws, "repo" to slug)
            )
        }
        return null
    }

    // ── 개인화 Bitbucket 리뷰 계획 ──

    /** 개인화 Bitbucket 리뷰 큐 계획 (레포지토리 미지정). */
    fun planBitbucketPersonal(ctx: PlannerCtx): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAnyHint(bitbucketMyReviewHints)) {
            return null
        }
        return ForcedToolCallPlan("bitbucket_review_queue", emptyMap())
    }

    // ── 기타 Bitbucket 계획 ──

    /** Bitbucket 명시 + 리뷰 리스크 계획. */
    fun planMiscBitbucket(ctx: PlannerCtx): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.contains("bitbucket") &&
            n.matchesAnyHint(WorkContextPatterns.REVIEW_RISK_HINTS)
        ) {
            return ForcedToolCallPlan(
                "bitbucket_review_sla_alerts",
                mapOf("slaHours" to 24)
            )
        }
        return null
    }
}
