package com.arc.reactor.agent.impl

/**
 * 개인화 도구 강제 호출 계획 — 포커스 플랜, 학습 다이제스트, 인터럽트 가드, 마감 정리 등을 담당한다.
 *
 * [WorkContextForcedToolPlanner]의 plan() 체인에서 개인화 관련 분기를 처리한다.
 *
 * @see WorkContextForcedToolPlanner 오케스트레이터
 */
internal object WorkContextPersonalizationPlanner {

    // ── 힌트 키워드 셋 ──

    private val workPersonalFocusGeneralHints = setOf(
        "내가 지금 해야 할 작업", "지금 해야 할 작업", "오늘 집중해야",
        "오늘 해야 할 일", "내가 오늘 집중해야", "내가 오늘 해야 할",
        "마감 전에 끝내", "끝내면 좋은 일", "미뤄도 되는 일",
        "우선순위 순", "open issue와 due soon", "due soon",
        "리스크가 큰 것", "집중해야 할 api 관련", "review queue를",
        "carry-over", "내일 아침 바로 봐야", "내 open issue",
        "오늘 브리핑", "morning briefing", "해야 할 일과 미뤄도 되는 일"
    )
    private val workPersonalLearningGeneralHints = setOf(
        "최근에 관여한 이슈와 문서", "최근 참여한 작업", "읽어야 할 runbook",
        "incident 문서", "최근에 본 문서", "이번 주 팀 변화",
        "봐야 할 pr과 문서",
        "jira와 bitbucket 기준으로 묶어", "알아야 할 이번 주 팀 변화",
        "최근 참여한 작업을 jira와 bitbucket 기준으로"
    )
    private val bitbucketMyAuthoredPrHints = setOf(
        "리뷰를 기다리게 만든 pr", "내가 만든 pr", "내 pr",
        "내 pull request", "내가 올린 pr"
    )
    private val bitbucketMyReviewLateHints = setOf(
        "늦게 보고 있는 리뷰", "review queue", "내 review queue",
        "리뷰 대기열", "리뷰 sla 경고"
    )
    private val personalDocumentHints = setOf(
        "휴가 규정", "남은 휴가", "내 이름 기준", "owner로 적혀", "회의록",
        "runbook", "incident 문서", "owner 문서", "서비스 owner", "api 문서"
    )

    // ── 개인화 도구 계획 (엔트리) ──

    /** 개인화 도구 계획 — 포커스, 학습, 인터럽트, 마감 정리 등. */
    fun planPersonalTools(
        prompt: String,
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAnyHint(
                WorkContextPatterns.WORK_PERSONAL_FOCUS_HINTS
            )
        ) {
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

    // ── 개인화 Jira 관련 ──

    /** 개인화 Jira 관련 계획. */
    private fun planPersonalJira(
        prompt: String,
        ctx: PlannerCtx
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
        ctx: PlannerCtx
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

    // ── 개인화 Bitbucket 관련 ──

    /** 개인화 Bitbucket 관련 계획. */
    private fun planPersonalBitbucket(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (!ctx.isPersonal) return null
        if (n.matchesAnyHint(bitbucketMyAuthoredPrHints)) {
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

    // ── 개인화 포커스/학습/인터럽트/마감 ──

    /** 개인화 일반 포커스 플랜 계획. */
    private fun planPersonalFocusGeneral(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAnyHint(workPersonalFocusGeneralHints)) {
            return null
        }
        val topN = if (ctx.normalized.contains("3개")) 3 else 5
        return ForcedToolCallPlan(
            "work_personal_focus_plan", mapOf("topN" to topN)
        )
    }

    /** 학습 다이제스트 계획 (직접 힌트 또는 개인화 일반 힌트). */
    private fun planPersonalLearning(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAnyHint(
                WorkContextPatterns.WORK_PERSONAL_LEARNING_HINTS
            )
        ) {
            return ForcedToolCallPlan(
                "work_personal_learning_digest",
                WorkContextArgBuilder.buildLearningDigestArgs()
            )
        }
        if (ctx.isPersonal &&
            n.matchesAnyHint(workPersonalLearningGeneralHints)
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
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        val n = ctx.normalized
        if (n.matchesAnyHint(
                WorkContextPatterns.WORK_PERSONAL_INTERRUPT_HINTS
            )
        ) {
            return ForcedToolCallPlan(
                "work_personal_interrupt_guard",
                mapOf("maxInterrupts" to 5, "focusBlockMinutes" to 90)
            )
        }
        if (n.matchesAnyHint(
                WorkContextPatterns.WORK_PERSONAL_WRAPUP_HINTS
            )
        ) {
            return ForcedToolCallPlan(
                "work_personal_end_of_day_wrapup",
                mapOf("lookbackDays" to 1, "tomorrowTopN" to 3)
            )
        }
        return null
    }

    /** 개인화 늦은 리뷰/SLA 계획. */
    private fun planPersonalLateReview(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAnyHint(bitbucketMyReviewLateHints)) {
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
    private fun planPersonalDocument(
        ctx: PlannerCtx
    ): ForcedToolCallPlan? {
        if (!ctx.isPersonal) return null
        if (!ctx.normalized.matchesAnyHint(personalDocumentHints)) {
            return null
        }
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
}
