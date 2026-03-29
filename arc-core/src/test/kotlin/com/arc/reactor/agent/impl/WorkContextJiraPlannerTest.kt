package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [WorkContextJiraPlanner] 단위 테스트.
 *
 * Jira 도메인 강제 호출 계획 분기 — 내 오픈 이슈, 키워드 검색, 프로젝트 스코프,
 * 블로커/브리핑 폴백 — 의 도구명과 인자 맵을 직접 호출하여 검증한다.
 */
@DisplayName("WorkContextJiraPlanner")
class WorkContextJiraPlannerTest {

    // ── PlannerCtx 헬퍼 ──

    private fun ctx(
        normalized: String,
        projectKey: String? = null,
        inferredProjectKey: String? = null
    ): PlannerCtx = WorkContextEntityExtractor.ParsedPrompt(
        normalized = normalized,
        issueKey = null,
        serviceName = null,
        projectKey = projectKey,
        inferredProjectKey = inferredProjectKey,
        repository = null,
        specUrl = null,
        swaggerSpecName = null,
        ownershipKeyword = null,
        isPersonal = false
    )

    // ────────────────────────────────────────
    // planJiraSearch
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planJiraSearch — 내 오픈 이슈 / 키워드 검색")
    inner class PlanJiraSearch {

        @Test
        fun `내 오픈 이슈 힌트 — jira_my_open_issues를 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraSearch(
                "jira에서 내 오픈 이슈 보여줘",
                ctx("jira에서 내 오픈 이슈 보여줘")
            )

            assertNotNull(plan, "내 오픈 이슈 힌트가 매칭되면 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_my_open_issues"
        }

        @Test
        fun `projectKey가 있으면 인자에 project가 포함되어야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraSearch(
                "jira 내 오픈 이슈",
                ctx("jira 내 오픈 이슈", projectKey = "JAR")
            )

            assertNotNull(plan, "projectKey가 있을 때 plan이 null이면 안 된다")
            plan!!.arguments["project"] shouldBe "JAR"
        }

        @Test
        fun `jira 없고 힌트 없음 — null을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraSearch(
                "오늘 날씨 어때",
                ctx("오늘 날씨 어때")
            )

            assertNull(plan, "jira 관련 힌트가 없으면 null이어야 한다")
        }
    }

    // ────────────────────────────────────────
    // planJiraProjectScoped
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planJiraProjectScoped — 프로젝트 스코프 Jira 조회")
    inner class PlanJiraProjectScoped {

        @Test
        fun `projectKey 없으면 null을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("최근 jira 이슈 보여줘")
            ) { false }

            assertNull(plan, "projectKey가 없으면 null이어야 한다")
        }

        @Test
        fun `최근 이슈 힌트 — jira_search_issues를 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("최근 jira 이슈 알려줘", projectKey = "MFS")
            ) { false }

            assertNotNull(plan, "최근 이슈 힌트 매칭 시 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_search_issues"
            val jql = plan.arguments["jql"] as String
            jql.contains("MFS") shouldBe true
        }

        @Test
        fun `지연 힌트 — work_morning_briefing을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("일정이 늦어지고 있는 이슈", projectKey = "JAR")
            ) { false }

            assertNotNull(plan, "지연 힌트 매칭 시 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_morning_briefing"
        }

        @Test
        fun `미할당 힌트 — unassigned JQL 조건으로 jira_search_issues를 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("담당자 없는 이슈 찾아줘", projectKey = "JAR")
            ) { false }

            assertNotNull(plan, "미할당 힌트 매칭 시 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_search_issues"
            val jql = plan.arguments["jql"] as String
            jql.contains("EMPTY") shouldBe true
        }

        @Test
        fun `하위 도메인 힌트 존재 시 null을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("JAR 프로젝트 조회", projectKey = "JAR")
            ) { true }

            assertNull(plan, "hasDownstreamProjectHints=true 이면 null이어야 한다")
        }

        @Test
        fun `기본 폴백 — jira_search_issues를 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                ctx("JAR 프로젝트 이슈", projectKey = "JAR")
            ) { false }

            assertNotNull(plan, "기본 폴백에서 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "jira_search_issues"
        }
    }

    // ────────────────────────────────────────
    // planBlockerAndBriefingFallback
    // ────────────────────────────────────────

    @Nested
    @DisplayName("planBlockerAndBriefingFallback — 블로커/브리핑 폴백")
    inner class PlanBlockerAndBriefingFallback {

        @Test
        fun `inferredProjectKey 없으면 null을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                ctx("오늘 업무 브리핑")
            )

            assertNull(plan, "inferredProjectKey가 없으면 null이어야 한다")
        }

        @Test
        fun `업무 브리핑 힌트 — work_morning_briefing을 반환해야 한다`() {
            val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                ctx("오늘 업무 브리핑 알려줘", inferredProjectKey = "JAR")
            )

            assertNotNull(plan, "업무 브리핑 힌트 매칭 시 plan이 null이면 안 된다")
            plan!!.toolName shouldBe "work_morning_briefing"
        }
    }
}
