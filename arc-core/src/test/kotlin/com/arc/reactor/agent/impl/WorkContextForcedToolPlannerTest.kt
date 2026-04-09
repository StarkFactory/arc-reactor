package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * WorkContextForcedToolPlanner에 대한 테스트.
 *
 * 작업 컨텍스트에서 강제 도구 계획 수립을 검증합니다.
 */
class WorkContextForcedToolPlannerTest {

    @Test
    fun `plan owner lookup from issue ownership prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 기준으로 담당 서비스와 owner, 팀을 찾아줘.")

        requireNotNull(plan)
        assertEquals("work_owner_lookup", plan.toolName)
        assertEquals("PAY-123", plan.arguments["query"])
    }

    @Test
    fun `plan item context from issue context prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("PAY-123 이슈 전체 맥락을 정리해줘. 관련 문서와 다음 액션까지 포함해줘.")

        requireNotNull(plan)
        assertEquals("work_item_context", plan.toolName)
        assertEquals("PAY-123", plan.arguments["issueKey"])
    }

    @Test
    fun `plan service context from service digest prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘.")

        requireNotNull(plan)
        assertEquals("work_service_context", plan.toolName)
        assertEquals("payments", plan.arguments["service"])
    }

    @Test
    fun `ignore unrelated prompts해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("이번 주 운영 리포트를 보여줘.")

        assertNull(plan)
    }

    @Test
    fun `plan release readiness pack from project and repo prompt해야 한다`() {
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
    fun `plan confluence discovery from quoted keyword prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "Confluence에서 'weekly' 키워드로 검색하고 어떤 문서가 있는지 링크와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("weekly", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `plan quoted document discovery without explicit confluence word해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "'api' 관련 문서가 있으면 핵심만 요약하고, 없으면 없다고 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("api", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `plan jira blocker digest from project prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("BACKEND 프로젝트의 blocker 이슈를 소스와 함께 정리해줘.")

        requireNotNull(plan)
        assertEquals("jira_blocker_digest", plan.toolName)
        assertEquals("BACKEND", plan.arguments["project"])
        assertEquals(25, plan.arguments["maxResults"])
    }

    @Test
    fun `plan jira daily briefing from project prompt해야 한다`() {
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
    fun `plan morning briefing from explicit briefing shorthand prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 DEV 현황 요약")

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("status", plan.arguments["confluenceKeyword"])
    }

    @Test
    fun `generic status prompt without briefing keyword는 morning briefing으로 폴백하지 않아야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 DEV 상태")

        assertNull(plan, "범용 '상태' 키워드만으로는 morning briefing을 선택하면 안 된다")
    }

    @Test
    fun `plan standup from project shorthand standup prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("OPS 팀 standup 핵심만 정리해줘")

        requireNotNull(plan)
        assertEquals("work_prepare_standup_update", plan.toolName)
        assertEquals("OPS", plan.arguments["jiraProject"])
    }

    @Test
    fun `plan blocker digest from project shorthand blocker prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("OPS 이번 주 blocker")

        requireNotNull(plan)
        assertEquals("jira_blocker_digest", plan.toolName)
        assertEquals("OPS", plan.arguments["project"])
    }

    @Test
    fun `plan jira daily briefing from work briefing phrasing해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트 기준으로 오늘 아침 업무 브리핑을 출처와 함께 만들어줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `plan jira project list prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 접근 가능한 Jira 프로젝트 목록을 보여줘. 출처를 붙여줘.")

        requireNotNull(plan)
        assertEquals("jira_list_projects", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `plan jira recent issue summary prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("BACKEND 프로젝트에서 최근 Jira 이슈를 소스와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "BACKEND" ORDER BY updated DESC""", plan.arguments["jql"])
        assertEquals(10, plan.arguments["maxResults"])
    }

    @Test
    fun `plan jira unassigned issue prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트에서 unassigned 이슈를 찾아 소스와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "DEV" AND assignee is EMPTY ORDER BY updated DESC""", plan.arguments["jql"])
        assertEquals(10, plan.arguments["maxResults"])
    }

    @Test
    fun `plan jira release search prompt해야 한다`() {
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
    fun `plan jira generic keyword search prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("Jira에서 API 키워드로 검색하고 소스와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_by_text", plan.toolName)
        assertEquals("API", plan.arguments["keyword"])
        assertEquals(10, plan.arguments["limit"])
    }

    @Test
    fun `assignee is missing일 때 prefer jira unassigned search over owner lookup해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트에서 담당자가 없는 이슈를 출처와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("jira_search_issues", plan.toolName)
        assertEquals("""project = "DEV" AND assignee is EMPTY ORDER BY updated DESC""", plan.arguments["jql"])
    }

    @Test
    fun `plan jira my open issues prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 담당한 Jira 오픈 이슈 목록을 출처와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("jira_my_open_issues", plan.toolName)
        assertEquals(20, plan.arguments["maxResults"])
    }

    @Test
    fun `plan hybrid blocker and review prompt via release risk digest해야 한다`() {
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
    fun `plan cross source doc and issue prompt via morning briefing해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "DEV 프로젝트의 지식 문서와 운영 이슈를 같이 보고 오늘 핵심만 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
        assertEquals("status", plan.arguments["confluenceKeyword"])
    }

    @Test
    fun `plan standup prompt from jira and confluence signals해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "오늘 standup용으로 Jira 진행 상황과 Confluence 문서 변경을 같이 요약해줘."
        )

        requireNotNull(plan)
        assertEquals("work_prepare_standup_update", plan.toolName)
        assertEquals(7, plan.arguments["daysLookback"])
        assertEquals(20, plan.arguments["jiraMaxResults"])
    }

    @Test
    fun `not let owner phrasing bypass confluence discovery해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "Confluence에서 'owner' 키워드로 검색하고 관련 문서를 링크와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("owner", plan.arguments["keyword"])
    }

    @Test
    fun `plan confluence space list prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("접근 가능한 Confluence 스페이스 목록을 출처와 함께 보여줘.")

        requireNotNull(plan)
        assertEquals("confluence_list_spaces", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `plan delayed team status prompt via morning briefing해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 팀에서 오늘 늦어지고 있는 작업이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `plan personalized focus phrasing without explicit tool words해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 오늘 집중해야 할 작업 3개만 뽑아줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(3, plan.arguments["topN"])
    }

    @Test
    fun `plan personalized document phrasing해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내 이름으로 검색되는 회의록이 있으면 알려줘.")

        requireNotNull(plan)
        assertEquals("work_personal_document_search", plan.toolName)
        assertEquals("회의록", plan.arguments["keyword"])
    }

    @Test
    fun `not treat sa-nae policy prompt as personalized해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "'회의록' 관련 사내 규정이 있으면 Confluence 기준으로 요약해줘. 없으면 없다고 알려줘."
        )

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("회의록", plan.arguments["keyword"])
    }

    @Test
    fun `plan personalized blocker prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 이번 주에 제일 먼저 처리해야 할 Jira blocker를 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("jira_blocker_digest", plan.toolName)
        assertEquals(25, plan.arguments["maxResults"])
    }

    @Test
    fun `plan personalized overdue prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 맡은 이슈 중 overdue가 있으면 알려줘.")

        requireNotNull(plan)
        assertEquals("jira_due_soon_issues", plan.toolName)
        assertEquals(7, plan.arguments["days"])
        assertEquals(20, plan.arguments["maxResults"])
    }

    @Test
    fun `plan personal authored pull request prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 리뷰를 기다리게 만든 PR이 있으면 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_my_authored_prs", plan.toolName)
        assertEquals(true, plan.arguments["reviewPendingOnly"])
    }

    @Test
    fun `plan personalized release related jira prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내 Jira 작업 중 release 관련 것만 추려줘.")

        requireNotNull(plan)
        assertEquals("jira_search_my_issues_by_text", plan.toolName)
        assertEquals("release", plan.arguments["keyword"])
    }

    @Test
    fun `plan personalized api related jira prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 오늘 집중해야 할 API 관련 작업만 출처와 함께 정리해줘.")

        requireNotNull(plan)
        assertEquals("jira_search_my_issues_by_text", plan.toolName)
        assertEquals("api", plan.arguments["keyword"])
    }

    @Test
    fun `plan personalized morning briefing prompt to focus plan해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내 기준으로 오늘 morning briefing을 개인화해서 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `plan ownership discovery prompt via confluence keyword search해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("auth API를 누가 관리하는지 Confluence나 Jira 기준으로 알려줘.")

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("auth", plan.arguments["keyword"])
    }

    @Test
    fun `plan ambiguous service ownership prompt via owner discovery search해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("이 서비스 누가 개발했는지 알 수 있는 문서나 이슈가 있으면 찾아줘.")

        requireNotNull(plan)
        assertEquals("confluence_search_by_text", plan.toolName)
        assertEquals("owner", plan.arguments["keyword"])
    }

    @Test
    fun `plan repository ownership prompt using repository slug해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("어떤 팀이 dev 저장소를 주로 관리하는지 PR과 문서 기준으로 알려줘.")

        requireNotNull(plan)
        assertEquals("work_owner_lookup", plan.toolName)
        assertEquals("dev", plan.arguments["query"])
        assertEquals("repository", plan.arguments["entityType"])
    }

    @Test
    fun `plan personal review queue and due soon prompt to focus plan해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내 기준으로 리뷰 대기열과 Jira due soon을 같이 정리해줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `plan project and repo release risk digest without explicit jira bitbucket words해야 한다`() {
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
    fun `hybrid prompts에 대해 extract project key from issue phrasing해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "개발팀 Home 문서와 최근 DEV 이슈를 같이 보고 신규 입사자가 알아야 할 핵심을 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("work_morning_briefing", plan.toolName)
        assertEquals("DEV", plan.arguments["jiraProject"])
    }

    @Test
    fun `defaults로 plan personal focus prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 focus plan을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_focus_plan", plan.toolName)
        assertEquals(5, plan.arguments["topN"])
    }

    @Test
    fun `defaults로 plan personal learning prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 learning digest를 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_learning_digest", plan.toolName)
        assertEquals(14, plan.arguments["lookbackDays"])
        assertEquals(4, plan.arguments["topTopics"])
        assertEquals(2, plan.arguments["docsPerTopic"])
    }

    @Test
    fun `defaults로 plan personal interrupt prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 interrupt guard plan을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_interrupt_guard", plan.toolName)
        assertEquals(5, plan.arguments["maxInterrupts"])
        assertEquals(90, plan.arguments["focusBlockMinutes"])
    }

    @Test
    fun `defaults로 plan personal wrapup prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("오늘 개인 end of day wrapup 초안을 근거 정보와 함께 만들어줘.")

        requireNotNull(plan)
        assertEquals("work_personal_end_of_day_wrapup", plan.toolName)
        assertEquals(1, plan.arguments["lookbackDays"])
        assertEquals(3, plan.arguments["tomorrowTopN"])
    }

    @Test
    fun `plan bitbucket review risk prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("Bitbucket에서 최근 코드 리뷰 리스크를 출처와 함께 요약해줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_sla_alerts", plan.toolName)
        assertEquals(24, plan.arguments["slaHours"])
    }

    @Test
    fun `plan bitbucket my review prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("Bitbucket에서 내가 검토해야 할 PR이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `plan personal review prompt without explicit bitbucket word해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("내가 검토해야 할 PR이 있는지 출처와 함께 알려줘.")

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `plan repository qualified bitbucket open prs without explicit bitbucket word해야 한다`() {
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
    fun `plan repository qualified bitbucket stale prs without explicit bitbucket word해야 한다`() {
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
    fun `plan repository qualified bitbucket review queue without explicit bitbucket word해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소의 리뷰 대기열을 출처와 함께 정리해줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
    }

    @Test
    fun `plan repository qualified bitbucket review-needed prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소에서 지금 리뷰가 필요한 변경을 출처와 함께 알려줘."
        )

        requireNotNull(plan)
        assertEquals("bitbucket_review_queue", plan.toolName)
        assertEquals("jarvis-project", plan.arguments["workspace"])
        assertEquals("dev", plan.arguments["repo"])
    }

    @Test
    fun `plan repository qualified bitbucket review sla alerts without explicit bitbucket word해야 한다`() {
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
    fun `plan swagger url prompt by loading the spec first해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "https://petstore3.swagger.io/api/v3/openapi.json 스펙을 로드한 뒤 프론트엔드가 자주 쓸 만한 endpoint를 추려줘."
        )

        requireNotNull(plan)
        assertEquals("spec_load", plan.toolName)
        assertEquals("openapi", plan.arguments["name"])
        assertEquals("https://petstore3.swagger.io/api/v3/openapi.json", plan.arguments["url"])
    }

    @Test
    fun `plan loaded swagger exploration prompt via spec list해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "로컬에 로드된 OpenAPI 스펙에서 order endpoint를 찾아 출처와 함께 설명해줘."
        )

        requireNotNull(plan)
        assertEquals("spec_list", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    @Test
    fun `plan named swagger summary prompt via spec summary해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "petstore-public Swagger spec summary. Tell me total endpoints, schema count, security schemes, and top tags."
        )

        requireNotNull(plan)
        assertEquals("spec_summary", plan.toolName)
        assertEquals("petstore-public", plan.arguments["specName"])
        assertEquals("published", plan.arguments["scope"])
    }

    @Test
    fun `plan jira and confluence weekly team status prompt해야 한다`() {
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
    fun `plan jira and bitbucket release risk prompt해야 한다`() {
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

    // ── 이모지 스트리핑 테스트 ──

    @Test
    fun `emoji가 포함된 프로젝트 프롬프트에서 정상 파싱해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("🚀 BACKEND 프로젝트의 blocker 이슈를 소스와 함께 정리해줘.")

        requireNotNull(plan) { "이모지가 포함된 프롬프트에서 plan이 null이면 안 된다" }
        assertEquals("jira_blocker_digest", plan.toolName, "이모지 제거 후 도구가 정상 매칭되어야 한다")
        assertEquals("BACKEND", plan.arguments["project"], "이모지 제거 후 프로젝트 키를 추출해야 한다")
    }

    @Test
    fun `emoji가 포함된 이슈 키 프롬프트에서 정상 파싱해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("📋 PAY-456 이슈 전체 맥락을 정리해줘")

        requireNotNull(plan) { "이모지가 포함된 이슈 키 프롬프트에서 plan이 null이면 안 된다" }
        assertEquals("work_item_context", plan.toolName, "이모지 제거 후 work_item_context로 매칭해야 한다")
        assertEquals("PAY-456", plan.arguments["issueKey"], "이모지 제거 후 이슈 키를 추출해야 한다")
    }

    @Test
    fun `emoji만 있는 프롬프트에서 null을 반환해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("🚀🔥📋")

        assertNull(plan, "이모지만 있는 프롬프트는 null을 반환해야 한다")
    }

    @Test
    fun `이모지 없는 일반 텍스트는 변경 없이 정상 처리해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("BACKEND 프로젝트의 blocker 이슈를 소스와 함께 정리해줘.")

        requireNotNull(plan) { "일반 텍스트 프롬프트에서 plan이 null이면 안 된다" }
        assertEquals("jira_blocker_digest", plan.toolName, "이모지 없는 프롬프트는 기존과 동일하게 동작해야 한다")
        assertEquals("BACKEND", plan.arguments["project"], "프로젝트 키가 정상 추출되어야 한다")
    }

    @Test
    fun `plan swagger wrong endpoint prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "로드된 Petstore 스펙에서 잘못된 endpoint를 찾으려 하면 어떻게 보이는지 보여줘."
        )

        requireNotNull(plan)
        assertEquals("spec_list", plan.toolName)
        assertEquals(emptyMap<String, Any?>(), plan.arguments)
    }

    // ── Fix 1: 비존재 프로젝트 검색 시 도구 미호출 수정 ──

    @Test
    fun `plan jira search for nonexistent project해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("FAKE 프로젝트 이슈 보여줘")

        requireNotNull(plan) { "비존재 프로젝트라도 Jira API에 위임해야 한다" }
        assertEquals("jira_search_issues", plan.toolName, "범용 jira_search_issues 폴백이어야 한다")
        val jql = plan.arguments["jql"] as String
        assert(jql.contains("FAKE")) { "JQL에 프로젝트 키 FAKE가 포함되어야 한다" }
    }

    @Test
    fun `plan jira search fallback for project with generic query해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("ABC 프로젝트 상황 알려줘")

        requireNotNull(plan) { "프로젝트 키가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_search_issues", plan.toolName, "범용 프로젝트 질문은 jira_search_issues여야 한다")
    }

    // ── Fix 3: morning briefing 과선택 축소 ──

    @Test
    fun `priority query는 morning briefing이 아닌 jira search로 라우팅해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("JAR 프로젝트 이슈 중 우선순위 높은 것 보여줘")

        requireNotNull(plan) { "프로젝트+이슈 질문에 도구가 호출되어야 한다" }
        assertEquals("jira_search_issues", plan.toolName, "우선순위 필터 질문은 jira_search_issues여야 한다")
    }

    @Test
    fun `explicit briefing request는 morning briefing으로 라우팅해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트 오늘 업무 브리핑 해줘")

        requireNotNull(plan) { "명시적 브리핑 요청에 도구가 호출되어야 한다" }
        assertEquals("work_morning_briefing", plan.toolName, "명시적 브리핑 키워드가 있으면 morning briefing이어야 한다")
    }

    @Test
    fun `completed issues query는 morning briefing이 아닌 jira search로 라우팅해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트 이번 주 완료된 이슈 정리")

        requireNotNull(plan) { "프로젝트+이슈 질문에 도구가 호출되어야 한다" }
        assertEquals("jira_search_issues", plan.toolName,
            "이슈 정리 질문은 jira_search_issues여야 한다 (morning briefing이 아님)")
    }

    @Test
    fun `project priority without issue keyword는 jira search로 라우팅해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("JAR 프로젝트 우선순위 보여줘")

        requireNotNull(plan) { "프로젝트 키가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_search_issues", plan.toolName,
            "우선순위 질문은 morning briefing이 아닌 jira_search_issues여야 한다")
    }

    @Test
    fun `explicit briefing with project key는 morning briefing으로 라우팅해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트 현황 정리해줘")

        requireNotNull(plan) { "프로젝트+현황 정리 요청에 도구가 호출되어야 한다" }
        assertEquals("work_morning_briefing", plan.toolName,
            "현황 정리는 명시적 브리핑 키워드이므로 morning briefing이어야 한다")
    }

    // ── Fix 2: spec_validate 도구 라우팅 ──

    @Test
    fun `plan spec validate from korean prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("Swagger 스펙 검증해줘")

        requireNotNull(plan) { "검증 키워드가 있으면 spec_validate를 호출해야 한다" }
        assertEquals("spec_validate", plan.toolName)
    }

    @Test
    fun `plan spec validate from english prompt해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("validate the OpenAPI spec")

        requireNotNull(plan) { "validate 키워드가 있으면 spec_validate를 호출해야 한다" }
        assertEquals("spec_validate", plan.toolName)
    }

    @Test
    fun `plan spec validate with spec name해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("pet-store 스펙 유효성 검증해줘")

        requireNotNull(plan) { "유효성 키워드가 있으면 spec_validate를 호출해야 한다" }
        assertEquals("spec_validate", plan.toolName)
        assertEquals("pet-store", plan.arguments["specName"], "스펙 이름이 추출되어야 한다")
    }

    // ── Fix: 마감/기한 관련 비개인화 질문 도구 호출 ──

    @Test
    fun `plan due soon issues for non-personal deadline query해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("이번 주 마감 Jira 이슈 알려줘")

        requireNotNull(plan) { "마감 키워드 + Jira/이슈 키워드가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_due_soon_issues", plan.toolName, "마감 질문은 jira_due_soon_issues여야 한다")
        assertEquals(7, plan.arguments["days"]) { "기본 조회 기간은 7일이어야 한다" }
        assertEquals(20, plan.arguments["maxResults"]) { "기본 최대 결과는 20이어야 한다" }
    }

    @Test
    fun `plan due soon issues for deadline keyword with issue hint해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("마감일이 이번 주인 이슈 보여줘")

        requireNotNull(plan) { "마감일 + 이슈 키워드가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_due_soon_issues", plan.toolName, "마감일 질문은 jira_due_soon_issues여야 한다")
    }

    @Test
    fun `plan due soon issues for due date keyword해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("due date가 이번 주인 Jira 티켓 정리해줘")

        requireNotNull(plan) { "due date 키워드가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_due_soon_issues", plan.toolName, "due date 질문은 jira_due_soon_issues여야 한다")
    }

    @Test
    fun `plan due soon issues for project scoped deadline query해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트 기한 임박 이슈 알려줘")

        requireNotNull(plan) { "프로젝트+기한 키워드가 있으면 도구를 호출해야 한다" }
        assertEquals("jira_due_soon_issues", plan.toolName, "프로젝트 스코프 기한 질문은 jira_due_soon_issues여야 한다")
        assertEquals("DEV", plan.arguments["project"]) { "프로젝트 키가 인자에 포함되어야 한다" }
    }

    // ── Fix: S6 스탠드업 한국어 단독 트리거 ──

    @Test
    fun `plan standup from korean standup keyword without project key해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("스탠드업 준비해줘")

        requireNotNull(plan) { "한국어 '스탠드업' 키워드만 있어도 work_prepare_standup_update를 호출해야 한다" }
        assertEquals(
            "work_prepare_standup_update", plan.toolName,
            "한국어 스탠드업 단독 요청은 work_prepare_standup_update여야 한다"
        )
        assertEquals(7, plan.arguments["daysLookback"]) { "기본 lookback은 7일이어야 한다" }
        assertEquals(20, plan.arguments["jiraMaxResults"]) { "기본 maxResults는 20이어야 한다" }
    }

    @Test
    fun `plan standup from korean daily scrum keyword해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("데일리 스크럼 자료 만들어줘")

        requireNotNull(plan) { "'데일리 스크럼' 키워드로 work_prepare_standup_update를 호출해야 한다" }
        assertEquals(
            "work_prepare_standup_update", plan.toolName,
            "데일리 스크럼 요청은 work_prepare_standup_update여야 한다"
        )
    }

    @Test
    fun `plan standup from korean standup keyword with project key해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("DEV 프로젝트 스탠드업 준비해줘")

        requireNotNull(plan) { "프로젝트 키 + 스탠드업 키워드로 work_prepare_standup_update를 호출해야 한다" }
        assertEquals(
            "work_prepare_standup_update", plan.toolName,
            "프로젝트 키가 있으면 jiraProject 인자와 함께 호출되어야 한다"
        )
        assertEquals("DEV", plan.arguments["jiraProject"]) { "jiraProject에 DEV가 포함되어야 한다" }
    }

    // ── Fix: 레포 미지정 PR 요청 시 저장소 목록 폴백 ──

    @Test
    fun `plan repository list fallback for open pr without repo해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("열린 PR 목록 보여줘")

        requireNotNull(plan) { "레포 미지정 열린 PR 요청에도 도구를 호출해야 한다" }
        assertEquals(
            "bitbucket_list_repositories", plan.toolName,
            "레포 미지정 PR 요청은 bitbucket_list_repositories 폴백이어야 한다"
        )
    }

    @Test
    fun `plan repository list fallback for pr list without repo해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan("PR 목록 보여줘")

        requireNotNull(plan) { "레포 미지정 PR 목록 요청에도 도구를 호출해야 한다" }
        assertEquals(
            "bitbucket_list_repositories", plan.toolName,
            "레포 미지정 PR 목록 요청은 bitbucket_list_repositories 폴백이어야 한다"
        )
    }

    @Test
    fun `plan direct pr list when repo is specified해야 한다`() {
        val plan = WorkContextForcedToolPlanner.plan(
            "jarvis-project/dev 저장소의 열린 PR 목록을 보여줘"
        )

        requireNotNull(plan) { "레포 지정 열린 PR 요청에 도구를 호출해야 한다" }
        assertEquals(
            "bitbucket_list_prs", plan.toolName,
            "레포가 지정되면 bitbucket_list_prs를 직접 호출해야 한다"
        )
    }
}
