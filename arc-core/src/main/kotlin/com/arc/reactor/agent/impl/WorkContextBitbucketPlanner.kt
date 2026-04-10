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
        "pull request 목록", "pr 목록",
        // R172: 단순 PR 패턴도 OPEN PR로 가정 — repo가 명시되면 더 강하게 매칭
        "최근 pr", "최신 pr", "pr 보여줘", "pr 알려줘",
        "pr 현황", "pr 상황", "pr 리스트",
        "recent pr", "latest pr", "show pr"
    )
    private val bitbucketMergedPrHints = setOf(
        "머지된 pr", "merge된 pr", "merged pr", "merged prs",
        "머지 pr", "머지한 pr", "병합된 pr"
    )
    private val bitbucketStalePrHints = setOf(
        "stale pr", "오래된 pr", "방치된 pr", "stale pull request"
    )
    private val bitbucketBranchListHints = setOf(
        "branch 목록", "브랜치 목록", "list branches", "branches",
        "어떤 브랜치", "사용 가능한 브랜치", "접근 가능한 브랜치"
    )
    private val bitbucketMyReviewHints = setOf(
        "내가 검토", "검토해야", "review for me", "needs review",
        "리뷰 대기", "리뷰대기", "리뷰 필요", "리뷰필요",
        "review pending", "waiting for review", "리뷰 중", "리뷰중"
    )

    /** 본인 작성 PR 패턴 — bitbucket_my_authored_prs로 라우팅 */
    private val bitbucketMyAuthoredHints = setOf(
        "내 pr", "내가 작성한 pr", "내가 올린 pr", "내가 쓴 pr",
        "my pr", "my prs", "my pull request", "my pull requests",
        "내 풀 리퀘스트", "내 풀리퀘스트", "작성한 pr", "올린 pr"
    )

    // ── 레포지토리 스코프 Bitbucket 계획 ──

    /** 레포지토리 기반 Bitbucket 도구 계획. workspace/repo 또는 slug 단독 모두 지원. */
    fun planBitbucketRepoScoped(ctx: PlannerCtx): ForcedToolCallPlan? {
        val ws = ctx.repository?.first
        val slug = ctx.repository?.second ?: ctx.repositorySlug ?: return null
        val n = ctx.normalized

        if (n.matchesAnyHint(bitbucketMergedPrHints)) {
            return buildRepoPlan("bitbucket_list_prs", ws, slug,
                "state" to "MERGED")
        }
        if (n.matchesAnyHint(bitbucketOpenPrHints)) {
            return buildRepoPlan("bitbucket_list_prs", ws, slug,
                "state" to "OPEN")
        }
        if (n.matchesAnyHint(bitbucketStalePrHints)) {
            return buildRepoPlan("bitbucket_stale_prs", ws, slug,
                "staleDays" to 7)
        }
        if (n.matchesAnyHint(WorkContextPatterns.REVIEW_QUEUE_HINTS)) {
            return buildRepoPlan("bitbucket_review_queue", ws, slug)
        }
        if (n.matchesAnyHint(WorkContextPatterns.REVIEW_SLA_HINTS)) {
            return buildRepoPlan("bitbucket_review_sla_alerts", ws, slug,
                "slaHours" to 24)
        }
        if (n.matchesAnyHint(bitbucketBranchListHints)) {
            return buildRepoPlan("bitbucket_list_branches", ws, slug)
        }
        return null
    }

    /** 레포지토리 도구 계획의 인자 맵을 조립한다. workspace가 null이면 생략. */
    private fun buildRepoPlan(
        toolName: String,
        workspace: String?,
        slug: String,
        vararg extras: Pair<String, Any>
    ): ForcedToolCallPlan = ForcedToolCallPlan(
        toolName,
        buildMap {
            workspace?.let { put("workspace", it) }
            put("repo", slug)
            for ((k, v) in extras) { put(k, v) }
        }
    )

    // ── 개인화 Bitbucket 리뷰 계획 ──

    /**
     * 개인화 Bitbucket 계획 (레포지토리 미지정).
     * "내가 작성한 PR" → bitbucket_my_authored_prs 우선 라우팅 (개인화 키워드 자체로 personal)
     * "리뷰 대기/필요" → bitbucket_review_queue (isPersonal 체크 없이도 작동)
     *
     * `리뷰 대기 중인 PR`처럼 "내" 같은 명시적 인칭 대명사가 없어도 reviewer
     * 자동 매핑(requesterEmail → reviewer)을 통해 개인 큐로 해석한다.
     *
     * **주의**: blocker/hybrid/release risk 컨텍스트가 함께 있으면 다른 통합 도구
     * (예: work_release_risk_digest)가 더 적합하므로 이 plan을 건너뛴다.
     */
    fun planBitbucketPersonal(ctx: PlannerCtx): ForcedToolCallPlan? {
        val n = ctx.normalized
        // hybrid 컨텍스트가 있으면 더 적합한 통합 도구로 양보
        if (hasHybridReleaseContext(n)) return null

        // 본인 작성 PR — isPersonal 여부 무관
        if (n.matchesAnyHint(bitbucketMyAuthoredHints)) {
            return ForcedToolCallPlan("bitbucket_my_authored_prs", emptyMap())
        }
        // 리뷰 대기 — isPersonal 여부 무관 (reviewer는 requesterEmail로 자동 주입)
        if (n.matchesAnyHint(bitbucketMyReviewHints)) {
            return ForcedToolCallPlan("bitbucket_review_queue", emptyMap())
        }
        return null
    }

    /** blocker/hybrid/release 컨텍스트가 있으면 release_risk_digest 같은 통합 도구가 우선이다. */
    private fun hasHybridReleaseContext(normalized: String): Boolean {
        return normalized.matchesAnyHint(WorkContextPatterns.BLOCKER_HINTS) ||
            normalized.matchesAnyHint(WorkContextPatterns.HYBRID_RELEASE_RISK_HINTS) ||
            normalized.matchesAnyHint(WorkContextPatterns.WORK_RELEASE_READINESS_HINTS)
    }

    // ── 레포 미지정 PR 요청 폴백 ──

    /**
     * 레포지토리 미지정 상태에서 PR 목록 요청 시 저장소 목록을 먼저 조회하는 폴백.
     *
     * "열린 PR 보여줘", "PR 목록" 등 레포 없이 PR을 요청하면
     * 역질문 대신 bitbucket_list_repositories를 호출하여
     * 사용자에게 선택지를 제공한다.
     */
    fun planBitbucketPrWithoutRepo(ctx: PlannerCtx): ForcedToolCallPlan? {
        if (ctx.repository != null || ctx.repositorySlug != null) return null
        val n = ctx.normalized
        val isPrRequest = n.matchesAnyHint(bitbucketOpenPrHints) ||
            n.matchesAnyHint(bitbucketMergedPrHints) ||
            n.matchesAnyHint(bitbucketStalePrHints)
        if (!isPrRequest) return null
        return ForcedToolCallPlan("bitbucket_list_repositories", emptyMap())
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
