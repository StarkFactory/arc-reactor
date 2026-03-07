package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkContextForcedToolPlannerTest {

    @Test
    fun `should plan owner lookup from issue ownership prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 기준으로 담당 서비스와 owner, 팀을 찾아줘.")

        requireNotNull(plan)
        assertEquals("work_owner_lookup", plan.toolName)
        assertEquals("PAY-123", plan.arguments["query"])
    }

    @Test
    fun `should plan item context from issue context prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 전체 맥락을 정리해줘. 관련 문서와 다음 액션까지 포함해줘.")

        requireNotNull(plan)
        assertEquals("work_item_context", plan.toolName)
        assertEquals("PAY-123", plan.arguments["issueKey"])
    }

    @Test
    fun `should plan service context from service digest prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘.")

        requireNotNull(plan)
        assertEquals("work_service_context", plan.toolName)
        assertEquals("payments", plan.arguments["service"])
    }

    @Test
    fun `should ignore unrelated prompts`() {
        val plan = WorkContextForcedToolPlanner.plan("이번 주 운영 리포트를 보여줘.")

        assertNull(plan)
    }

    @Test
    fun `should plan release readiness pack from project and repo prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트와 jarvis-project/dev 기준으로 release readiness pack을 출처와 함께 만들어줘."
        )

        requireNotNull(plan)
        assertEquals("work_release_readiness_pack", plan.toolName)
        assertEquals("DEV", plan.arguments["releaseName"])
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("jarvis-project", plan.arguments["bitbucketWorkspace"])
        assertEquals("dev", plan.arguments["bitbucketRepo"])
        assertEquals(3, plan.arguments["stalePrDays"])
        assertEquals(24, plan.arguments["reviewSlaHours"])
        assertEquals(1, plan.arguments["daysLookback"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
        assertEquals(true, plan.arguments["dryRunActionItems"])
    }

    @Test
    fun `should plan confluence discovery from quoted keyword prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "Confluence에서 'weekly' 키워드로 검색하고 어떤 문서가 있는지 링크와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("weekly", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `should plan quoted document discovery without explicit confluence word`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "'api' 관련 문서가 있으면 핵심만 요약하고, 없으면 없다고 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("api", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `should plan jira blocker digest from project prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("BACKEND 프로젝트의 blocker 이슈를 소스와 함께 정리해줘.")

        requireNotNull(plan)
        assertEquals("jira_blocker_digest", plan.toolName)
        assertEquals("BACKEND", plan.arguments["project"])
        assertEquals(25, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan jira daily briefing from project prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "BACKEND 프로젝트 기준으로 오늘의 Jira 브리핑을 만들어줘. 반드시 출처를 붙여줘."
        )

        requireNotNull(plan)
        assertEquals("jira_daily_briefing", plan.toolName)
        assertEquals("BACKEND", plan.arguments["project"])
        assertEquals(3, plan.arguments["dueSoonDays"])
        assertEquals(30, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan jira daily briefing from work briefing phrasing`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트 기준으로 오늘 아침 업무 브리핑을 출처와 함께 만들어줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `should plan jira project list prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 접근 가능한 Jira 프로젝트 목록을 보여줘. 출처를 붙여줘.")

        requireNotNull(plan)
        assertEquals("jira_list_projects", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `should plan jira recent issue summary prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("BACKEND 프로젝트에서 최근 Jira 이슈를 소스와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "BACKEND" ORDER BY updated DESC""", plan.arguments["jql"])
        assertEquals(10, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan jira unassigned issue prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트에서 unassigned 이슈를 찾아 소스와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "DEV" AND assignee is EMPTY ORDER BY updated DESC""", plan.arguments["jql"])
        assertEquals(10, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan jira release search prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "BACKEND 프로젝트에서 release 관련 Jira 이슈를 검색해서 소스와 함께 보여줘."
        )

        requireNotNull(plan)
        assertEquals("jira_search_by_text", plan.toolName)
        assertEquals("release", plan.arguments["keyword"])
        assertEquals("BACKEND", plan.arguments["project"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `should plan jira generic keyword search prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("Jira에서 API 키워드로 검색하고 소스와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_by_text", plan.toolName)
        assertEquals("API", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `should prefer jira unassigned search over owner lookup when assignee is missing`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트에서 담당자가 없는 이슈를 출처와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "DEV" AND assignee is EMPTY ORDER BY updated DESC""", plan.arguments["jql"])
    }

    @Test
    fun `should plan jira my open issues prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 담당한 Jira 오픈 이슈 목록을 출처와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("jira_my_open_issues", plan.toolName)
        assertEquals(20, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan hybrid blocker and review prompt via release risk digest`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트의 blocker와 리뷰 대기열을 함께 보고 오늘 우선순위를 출처와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_release_risk_digest", plan.toolName)
        assertEquals("DEV", plan.arguments["releaseName"])
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals(3, plan.arguments["stalePrDays"])
        assertEquals(24, plan.arguments["reviewSlaHours"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
    }

    @Test
    fun `should plan cross source doc and issue prompt via morning briefing`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트의 지식 문서와 운영 이슈를 같이 보고 오늘 핵심만 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("status", plan.arguments["confluenceKeyword"])
    }

    @Test
    fun `should plan standup prompt from jira and confluence signals`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "오늘 standup용으로 Jira 진행 상황과 Confluence 문서 변경을 같이 요약해줘."
        )

        requireNotNull(plan)
        assertEquals("work_prepare_standup_update", plan.toolName)
        assertEquals(7, plan.arguments["daysLookback"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
    }

    @Test
    fun `should not let owner phrasing bypass confluence discovery`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "Confluence에서 'owner' 키워드로 검색하고 관련 문서를 링크와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("owner", plan.arguments["keyword"])
    }

    @Test
    fun `should plan confluence space list prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("접근 가능한 Confluence 스페이스 목록을 출처와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("confluence_list_spaces", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `should plan delayed team status prompt via morning briefing`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 팀에서 오늘 늦어지고 있는 작업이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `should plan personalized focus phrasing without explicit tool words`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 오늘 집중해야 할 작업 3개만 뽑아줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(3, plan.arguments["topN"])
    }

    @Test
    fun `should plan personalized document phrasing`() {
        val plan = WorkContextForcedToolPlanner.plan("내 이름으로 검색되는 회의록이 있으면 알려줘.")

        requireNotNull(plan)
        assertEquals("work_personal_document_search", plan.toolName)
        assertEquals("회의록", plan.arguments["keyword"])
    }

    @Test
    fun `should not treat sa-nae policy prompt as personalized`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "'회의록' 관련 사내 규정이 있으면 Confluence 기준으로 요약해줘. 없으면 없다고 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("회의록", plan.arguments["keyword"])
    }

    @Test
    fun `should plan personalized blocker prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 이번 주에 제일 먼저 처리해야 할 Jira blocker를 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("jira_blocker_digest", plan.toolName)
        assertEquals(25, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan personalized overdue prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 맡은 이슈 중 overdue가 있으면 알려줘.")

        requireNotNull(plan)
        assertEquals("jira_due_soon_issues", plan.toolName)
        assertEquals(7, plan.arguments["days"])
        assertEquals(20, plan.arguments["maxResults"])
    }

    @Test
    fun `should plan personal authored pull request prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 리뷰를 기다리게 만든 PR이 있으면 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_my_authored_prs", plan.toolName)
        assertEquals(true, plan.arguments["reviewPendingOnly"])
    }

    @Test
    fun `should plan personalized release related jira prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내 Jira 작업 중 release 관련 것만 추려줘.")

        requireNotNull(plan)
        assertEquals("jira_search_my_issues_by_text", plan.toolName)
        assertEquals("release", plan.arguments["keyword"])
    }

    @Test
    fun `should plan personalized api related jira prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 오늘 집중해야 할 API 관련 작업만 출처와 함께 정리해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_my_issues_by_text", plan.toolName)
        assertEquals("api", plan.arguments["keyword"])
    }

    @Test
    fun `should plan personalized morning briefing prompt to focus plan`() {
        val plan = WorkContextForcedToolPlanner.plan("내 기준으로 오늘 morning briefing을 개인화해서 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `should plan ownership discovery prompt via confluence keyword search`() {
        val plan = WorkContextForcedToolPlanner.plan("auth API를 누가 관리하는지 Confluence나 Jira 기준으로 알려줘.")

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("auth", plan.arguments["keyword"])
    }

    @Test
    fun `should plan ambiguous service ownership prompt via owner discovery search`() {
        val plan = WorkContextForcedToolPlanner.plan("이 서비스 누가 개발했는지 알 수 있는 문서나 이슈가 있으면 찾아줘.")

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("owner", plan.arguments["keyword"])
    }

    @Test
    fun `should plan repository ownership prompt using repository slug`() {
        val plan = WorkContextForcedToolPlanner.plan("어떤 팀이 dev 저장소를 주로 관리하는지 PR과 문서 기준으로 알려줘.")

        requireNotNull(plan)
        assertEquals("work_owner_lookup", plan.toolName)
        assertEquals("dev", plan.arguments["query"])
        assertEquals("repository", plan.arguments["entityType"])
    }

    @Test
    fun `should plan personal review queue and due soon prompt to focus plan`() {
        val plan = WorkContextForcedToolPlanner.plan("내 기준으로 리뷰 대기열과 Jira due soon을 같이 정리해줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `should plan project and repo release risk digest without explicit jira bitbucket words`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트와 jarvis-project/dev 기준으로 release risk digest를 출처와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_release_risk_digest", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("jarvis-project", plan.arguments["bitbucketWorkspace"])
        assertEquals("dev", plan.arguments["bitbucketRepo"])
    }

    @Test
    fun `should extract project key from issue phrasing for hybrid prompts`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "개발팀 Home 문서와 최근 DEV 이슈를 같이 보고 신규 입사자가 알아야 할 핵심을 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `should plan personal focus prompt with defaults`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 focus plan을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `should plan personal learning prompt with defaults`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 learning digest를 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_learning_digest", plan.toolName)
        assertEquals(14, plan.arguments["lookbackDays"])
        assertEquals(4, plan.arguments["topTopics"])
        assertEquals(2, plan.arguments["docsPerTopic"])
    }

    @Test
    fun `should plan personal interrupt prompt with defaults`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 interrupt guard plan을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_interrupt_guard", plan.toolName)
        assertEquals(5, plan.arguments["maxInterrupts"])
        assertEquals(90, plan.arguments["focusBlockMinutes"])
    }

    @Test
    fun `should plan personal wrapup prompt with defaults`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 end of day wrapup 초안을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_end_of_day_wrapup", plan.toolName)
        assertEquals(1, plan.arguments["lookbackDays"])
        assertEquals(3, plan.arguments["tomorrowTopN"])
    }

    @Test
    fun `should plan bitbucket review risk prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("Bitbucket에서 최근 코드 리뷰 리스크를 출처와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_sla_alerts", plan.toolName)
        assertEquals(24, plan.arguments["slaHours"])
    }

    @Test
    fun `should plan bitbucket my review prompt`() {
        val plan = WorkContextForcedToolPlanner.plan("Bitbucket에서 내가 검토해야 할 PR이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `should plan personal review prompt without explicit bitbucket word`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 검토해야 할 PR이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `should plan repository qualified bitbucket open prs without explicit bitbucket word`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소의 열린 PR 목록을 출처와 함께 보여줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_list_prs", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
        assertEquals("OPEN", plan.arguments["state"])
    }

    @Test
    fun `should plan repository qualified bitbucket stale prs without explicit bitbucket word`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소의 stale PR을 출처와 함께 점검해줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_stale_prs", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
        assertEquals(7, plan.arguments["staleDays"])
    }

    @Test
    fun `should plan repository qualified bitbucket review queue without explicit bitbucket word`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소의 리뷰 대기열을 출처와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
    }

    @Test
    fun `should plan repository qualified bitbucket review-needed prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소에서 지금 리뷰가 필요한 변경을 출처와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
    }

    @Test
    fun `should plan repository qualified bitbucket review sla alerts without explicit bitbucket word`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/jarvis 저장소의 리뷰 SLA 경고를 출처와 함께 보여줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_review_sla_alerts", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("jarvis", plan.arguments["repo"])
        assertEquals(24, plan.arguments["slaHours"])
    }

    @Test
    fun `should plan swagger url prompt by loading the spec first`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "https://petstore3.swagger.io/api/v3/openapi.json 스펙을 로드한 뒤 프론트엔드가 자주 쓸 만한 endpoint를 추려줘."
        )

        requireNotNull(plan)
        assertEquals("spec_load", plan.toolName)
        assertEquals("openapi", plan.arguments["name"])
        assertEquals("https://petstore3.swagger.io/api/v3/openapi.json", plan.arguments["url"])
    }

    @Test
    fun `should plan loaded swagger exploration prompt via spec list`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "로컬에 로드된 OpenAPI 스펙에서 order endpoint를 찾아 출처와 함께 설명해줘."
        )

        requireNotNull(plan)
        assertEquals("spec_list", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `should plan jira and confluence weekly team status prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "이번 주 DEV 팀 상태를 Jira와 Confluence 기준으로 출처와 함께 요약해줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("weekly", plan.arguments["confluenceKeyword"])
        assertEquals(24, plan.arguments["reviewSlaHours"])
        assertEquals(7, plan.arguments["dueSoonDays"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
    }

    @Test
    fun `should plan jira and bitbucket release risk prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "지금 DEV 릴리즈에 위험 신호가 있는지 Jira와 Bitbucket 기준으로 출처와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("work_release_risk_digest", plan.toolName)
        assertEquals("DEV", plan.arguments["releaseName"])
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals(3, plan.arguments["stalePrDays"])
        assertEquals(24, plan.arguments["reviewSlaHours"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
    }

    @Test
    fun `should plan swagger wrong endpoint prompt`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "로드된 Petstore 스펙에서 잘못된 endpoint를 찾으려 하면 어떻게 보이는지 보여줘."
        )

        requireNotNull(plan)
        assertEquals("spec_list", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }
}
