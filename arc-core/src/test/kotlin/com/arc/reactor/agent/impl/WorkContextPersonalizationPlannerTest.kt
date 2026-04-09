package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [WorkContextPersonalizationPlanner] 단위 테스트.
 *
 * 개인화 도구 강제 호출 계획 분기 — 포커스 플랜, 학습 다이제스트,
 * 인터럽트 가드, 마감 정리, Jira/Bitbucket 개인화, 문서 검색 등 —
 * 의 도구명과 인자 맵을 직접 호출하여 검증한다.
 */
@DisplayName("WorkContextPersonalizationPlanner")
class WorkContextPersonalizationPlannerTest {

    // ── PlannerCtx 헬퍼 ──

    /** 정규화 문자열과 isPersonal 플래그를 설정하는 최소 컨텍스트 */
    private fun ctx(
        normalized: String,
        isPersonal: Boolean = false,
        issueKey: String? = null,
        projectKey: String? = null,
        inferredProjectKey: String? = null,
        repository: Pair<String, String>? = null
    ): PlannerCtx = WorkContextEntityExtractor.ParsedPrompt(
        normalized = normalized,
        issueKey = issueKey,
        serviceName = null,
        projectKey = projectKey,
        inferredProjectKey = inferredProjectKey,
        repository = repository,
        repositorySlug = repository?.second,
        specUrl = null,
        swaggerSpecName = null,
        ownershipKeyword = null,
        isPersonal = isPersonal
    )

    // ────────────────────────────────────────
    // 최상위 엔트리 planPersonalTools
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 엔트리")
    inner class PlanPersonalToolsEntry {

        @Test
        fun `WORK_PERSONAL_FOCUS_HINTS 직접 매칭 — work_personal_focus_plan 을 반환해야 한다`() {
            // WorkContextPatterns.WORK_PERSONAL_FOCUS_HINTS 중 "focus plan" 사용
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                "오늘 나의 focus plan 알려줘",
                ctx("오늘 나의 focus plan 알려줘")
            )

            assertNotNull(plan, "WORK_PERSONAL_FOCUS_HINTS가 매칭되면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_focus_plan"
            plan.arguments["topN"] shouldBe 5
        }

        @Test
        fun `힌트가 없고 isPersonal 이 false 이면 null 을 반환해야 한다`() {
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                "오늘 날씨 알려줘",
                ctx("오늘 날씨 알려줘", isPersonal = false)
            )

            assertNull(plan, "매칭 힌트가 없으면 plan이 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // Jira 개인화 — release risk / blocker
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — Jira 개인화")
    inner class PersonalJira {

        @Test
        fun `release risk 힌트 + isPersonal — jira_blocker_digest 를 반환해야 한다`() {
            val prompt = "release risk 관련 내 jira 블로커 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "release risk + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_blocker_digest"
            plan.arguments["maxResults"] shouldBe 25
        }

        @Test
        fun `jira blocker 힌트 + isPersonal — jira_blocker_digest 를 반환해야 한다`() {
            val prompt = "제일 먼저 처리해야 할 jira blocker 목록"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "jira blocker + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_blocker_digest"
        }

        @Test
        fun `리뷰 대기열 + jira + isPersonal — work_personal_focus_plan 을 반환해야 한다`() {
            val prompt = "내 리뷰 대기열과 jira open issue 정리해줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "리뷰 대기열 + jira + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_focus_plan"
        }

        @Test
        fun `due soon 힌트 + isPersonal — jira_due_soon_issues 를 반환해야 한다`() {
            val prompt = "마감이 가까운 내 이슈 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "마감 임박 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_due_soon_issues"
            plan.arguments["days"] shouldBe 7
            plan.arguments["maxResults"] shouldBe 20
        }

        @Test
        fun `open issue + due soon 힌트 + isPersonal — jira_due_soon_issues 를 반환해야 한다`() {
            // planPersonalDueAndRelease 에서 "due soon" 가 먼저 매칭되어 jira_due_soon_issues 를 반환한다
            val prompt = "내 open issue와 due soon 이슈 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "open issue + due soon + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_due_soon_issues"
            plan.arguments["days"] shouldBe 7
        }
    }

    // ────────────────────────────────────────
    // Bitbucket 개인화
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — Bitbucket 개인화")
    inner class PersonalBitbucket {

        @Test
        fun `내 pr 힌트 + isPersonal — bitbucket_my_authored_prs 를 반환해야 한다`() {
            val prompt = "내 pr 목록 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "내 pr + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "bitbucket_my_authored_prs"
            plan.arguments["reviewPendingOnly"] shouldBe true
        }

        @Test
        fun `morning briefing 힌트 + isPersonal — work_personal_focus_plan 을 반환해야 한다`() {
            val prompt = "morning briefing 해줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "morning briefing + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_focus_plan"
        }

        @Test
        fun `isPersonal 이 false 이면 Bitbucket 개인화 계획을 반환하지 않아야 한다`() {
            val prompt = "내 pr 목록 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = false)
            )

            assertNull(plan, "isPersonal=false이면 Bitbucket 개인화 plan이 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // 일반 포커스 플랜
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 일반 포커스 플랜")
    inner class PersonalFocusGeneral {

        @Test
        fun `오늘 집중해야 힌트 + isPersonal — work_personal_focus_plan topN=5 을 반환해야 한다`() {
            val prompt = "오늘 집중해야 할 것 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "오늘 집중해야 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_focus_plan"
            plan.arguments["topN"] shouldBe 5
        }

        @Test
        fun `3개 힌트 포함 시 topN=3 을 반환해야 한다`() {
            val prompt = "오늘 집중해야 할 것 3개만 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "3개 힌트가 있으면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_focus_plan"
            plan.arguments["topN"] shouldBe 3
        }

        @Test
        fun `isPersonal 이 false 이면 일반 포커스 계획을 반환하지 않아야 한다`() {
            val prompt = "오늘 집중해야 할 것 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = false)
            )

            assertNull(plan, "isPersonal=false이면 일반 포커스 plan이 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // 학습 다이제스트
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 학습 다이제스트")
    inner class PersonalLearning {

        @Test
        fun `WORK_PERSONAL_LEARNING_HINTS 직접 매칭 — work_personal_learning_digest 를 반환해야 한다`() {
            // WorkContextPatterns.WORK_PERSONAL_LEARNING_HINTS 중 "학습 다이제스트"
            val prompt = "학습 다이제스트 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase())
            )

            assertNotNull(plan, "WORK_PERSONAL_LEARNING_HINTS가 매칭되면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_learning_digest"
            plan.arguments["lookbackDays"] shouldBe 14
            plan.arguments["topTopics"] shouldBe 4
            plan.arguments["docsPerTopic"] shouldBe 2
        }

        @Test
        fun `일반 학습 힌트 + isPersonal — work_personal_learning_digest 를 반환해야 한다`() {
            val prompt = "최근에 관여한 이슈와 문서 정리해줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "일반 학습 힌트 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_learning_digest"
        }

        @Test
        fun `학습 힌트가 없으면 null 을 반환해야 한다`() {
            val prompt = "리뷰 요청 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            // 위 프롬프트는 bitbucketMyReviewLateHints에 매칭될 수 있으므로 null만 확인
            // 단: "리뷰 요청"은 "review queue"가 아니라 hitbucketMyReviewLateHints에도 없음
            assertNull(plan, "매칭 힌트 없으면 plan이 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // 인터럽트 가드 / 하루 마감 정리
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 인터럽트 가드 / 하루 마감 정리")
    inner class PersonalInterruptAndWrapup {

        @Test
        fun `인터럽트 가드 힌트 — work_personal_interrupt_guard 를 반환해야 한다`() {
            // WorkContextPatterns.WORK_PERSONAL_INTERRUPT_HINTS 중 "인터럽트 가드"
            val prompt = "인터럽트 가드 설정해줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase())
            )

            assertNotNull(plan, "인터럽트 가드 힌트가 매칭되면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_interrupt_guard"
            plan.arguments["maxInterrupts"] shouldBe 5
            plan.arguments["focusBlockMinutes"] shouldBe 90
        }

        @Test
        fun `마감 정리 힌트 — work_personal_end_of_day_wrapup 을 반환해야 한다`() {
            // WorkContextPatterns.WORK_PERSONAL_WRAPUP_HINTS 중 "하루 마감"
            val prompt = "하루 마감 정리해줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase())
            )

            assertNotNull(plan, "마감 정리 힌트가 매칭되면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_end_of_day_wrapup"
            plan.arguments["lookbackDays"] shouldBe 1
            plan.arguments["tomorrowTopN"] shouldBe 3
        }
    }

    // ────────────────────────────────────────
    // 늦은 리뷰 / SLA
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 늦은 리뷰 / SLA")
    inner class PersonalLateReview {

        @Test
        fun `리뷰 대기열 힌트 + isPersonal — bitbucket_review_queue 를 반환해야 한다`() {
            val prompt = "내 review queue 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "review queue + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "bitbucket_review_queue"
        }

        @Test
        fun `sla 포함 리뷰 힌트 + isPersonal — bitbucket_review_sla_alerts 를 반환해야 한다`() {
            val prompt = "내 리뷰 sla 경고 알려줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "리뷰 sla + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "bitbucket_review_sla_alerts"
            plan.arguments["slaHours"] shouldBe 24
        }

        @Test
        fun `isPersonal 이 false 이면 늦은 리뷰 계획을 반환하지 않아야 한다`() {
            val prompt = "review queue 보여줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = false)
            )

            assertNull(plan, "isPersonal=false이면 review queue plan이 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // 개인화 문서 검색
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planPersonalTools — 개인화 문서 검색")
    inner class PersonalDocument {

        @Test
        fun `휴가 힌트 + isPersonal — work_personal_document_search(keyword=휴가) 를 반환해야 한다`() {
            val prompt = "내 휴가 규정 문서 찾아줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "휴가 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_document_search"
            plan.arguments["keyword"] shouldBe "휴가"
            plan.arguments["limit"] shouldBe 5
        }

        @Test
        fun `runbook 힌트 + isPersonal — work_personal_document_search(keyword=runbook) 를 반환해야 한다`() {
            val prompt = "관련 runbook 찾아줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "runbook + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_document_search"
            plan.arguments["keyword"] shouldBe "runbook"
        }

        @Test
        fun `회의록 힌트 + isPersonal — work_personal_document_search(keyword=회의록) 를 반환해야 한다`() {
            // personalDocumentHints 중 "회의록" 키워드는 workPersonalLearningGeneralHints와 겹치지 않음
            val prompt = "지난 회의록 찾아줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "회의록 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_document_search"
            plan.arguments["keyword"] shouldBe "회의록"
            plan.arguments["limit"] shouldBe 5
        }

        @Test
        fun `keyword 힌트 없는 문서 요청 + isPersonal — work_personal_document_search 를 반환해야 한다`() {
            val prompt = "owner로 적혀 있는 문서 찾아줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = true)
            )

            assertNotNull(plan, "owner 문서 힌트 + isPersonal이면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_personal_document_search"
            plan.arguments["limit"] shouldBe 5
        }

        @Test
        fun `isPersonal 이 false 이면 문서 검색 계획을 반환하지 않아야 한다`() {
            val prompt = "runbook 찾아줘"
            val plan = WorkContextPersonalizationPlanner.planPersonalTools(
                prompt,
                ctx(prompt.lowercase(), isPersonal = false)
            )

            assertNull(plan, "isPersonal=false이면 문서 검색 plan이 null이어야 한다")
        }
    }
}
