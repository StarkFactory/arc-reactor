package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WorkContext 하위 플래너 단위 테스트.
 *
 * [WorkContextBitbucketPlanner], [WorkContextJiraPlanner], [WorkContextDiscoveryPlanner]의
 * 분기 로직을 직접 호출하여 도구명과 인자 맵을 검증한다.
 * (이 파일들은 internal object로 같은 패키지에서 직접 접근 가능)
 */
@DisplayName("WorkContext 하위 플래너")
class WorkContextSubPlannerTest {

    // ── PlannerCtx(ParsedPrompt) 헬퍼 ──

    /** 정규화 문자열만 지정하는 최소 컨텍스트 */
    private fun ctx(
        normalized: String,
        issueKey: String? = null,
        serviceName: String? = null,
        projectKey: String? = null,
        inferredProjectKey: String? = null,
        repository: Pair<String, String>? = null,
        specUrl: String? = null,
        swaggerSpecName: String? = null,
        ownershipKeyword: String? = null,
        isPersonal: Boolean = false
    ): PlannerCtx = WorkContextEntityExtractor.ParsedPrompt(
        normalized = normalized,
        issueKey = issueKey,
        serviceName = serviceName,
        projectKey = projectKey,
        inferredProjectKey = inferredProjectKey,
        repository = repository,
        specUrl = specUrl,
        swaggerSpecName = swaggerSpecName,
        ownershipKeyword = ownershipKeyword,
        isPersonal = isPersonal
    )

    // ────────────────────────────────────────
    // WorkContextBitbucketPlanner
    // ────────────────────────────────────────

    @Nested
    @DisplayName("WorkContextBitbucketPlanner")
    inner class BitbucketPlannerTests {

        @Nested
        @DisplayName("planBitbucketRepoScoped — 레포지토리 스코프")
        inner class RepoScoped {

            @Test
            fun `열린 PR 힌트 — bitbucket_list_prs 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("열린 pr 목록 보여줘", repository = "acme" to "payments")
                )

                assertNotNull(plan, "열린 PR 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_list_prs"
                plan.arguments["workspace"] shouldBe "acme"
                plan.arguments["repo"] shouldBe "payments"
                plan.arguments["state"] shouldBe "OPEN"
            }

            @Test
            fun `stale PR 힌트 — bitbucket_stale_prs 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("stale pr 정리해줘", repository = "acme" to "payments")
                )

                assertNotNull(plan, "stale PR 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_stale_prs"
                plan.arguments["workspace"] shouldBe "acme"
                plan.arguments["repo"] shouldBe "payments"
                plan.arguments["staleDays"] shouldBe 7
            }

            @Test
            fun `리뷰 큐 힌트 — bitbucket_review_queue 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("review queue 현황", repository = "acme" to "payments")
                )

                assertNotNull(plan, "리뷰 큐 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_review_queue"
                plan.arguments["workspace"] shouldBe "acme"
                plan.arguments["repo"] shouldBe "payments"
            }

            @Test
            fun `리뷰 SLA 힌트 — bitbucket_review_sla_alerts 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("review sla 경고", repository = "acme" to "payments")
                )

                assertNotNull(plan, "리뷰 SLA 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_review_sla_alerts"
                plan.arguments["slaHours"] shouldBe 24
            }

            @Test
            fun `브랜치 목록 힌트 — bitbucket_list_branches 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("branch 목록 보여줘", repository = "acme" to "payments")
                )

                assertNotNull(plan, "브랜치 목록 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_list_branches"
                plan.arguments["workspace"] shouldBe "acme"
                plan.arguments["repo"] shouldBe "payments"
            }

            @Test
            fun `레포지토리가 없으면 null 을 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("열린 pr 목록", repository = null)
                )

                assertNull(plan, "repository가 null이면 plan이 null이어야 한다")
            }

            @Test
            fun `매칭 힌트가 없으면 null 을 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketRepoScoped(
                    ctx("오늘 날씨 알려줘", repository = "acme" to "payments")
                )

                assertNull(plan, "매칭 힌트가 없으면 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planBitbucketPersonal — 개인화")
        inner class Personal {

            @Test
            fun `개인화 + 내 리뷰 힌트 — bitbucket_review_queue 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketPersonal(
                    ctx("내가 검토해야 할 pr", isPersonal = true)
                )

                assertNotNull(plan, "개인화 + 리뷰 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_review_queue"
            }

            @Test
            fun `isPersonal 이 false 이면 null 을 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketPersonal(
                    ctx("내가 검토해야 할 pr", isPersonal = false)
                )

                assertNull(plan, "isPersonal=false이면 plan이 null이어야 한다")
            }

            @Test
            fun `매칭 힌트가 없으면 null 을 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planBitbucketPersonal(
                    ctx("jira 이슈 상태", isPersonal = true)
                )

                assertNull(plan, "매칭 힌트가 없으면 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planMiscBitbucket — 기타")
        inner class Misc {

            @Test
            fun `bitbucket 명시 + 리뷰 리스크 힌트 — bitbucket_review_sla_alerts 를 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planMiscBitbucket(
                    ctx("bitbucket review risk 확인해줘")
                )

                assertNotNull(plan, "bitbucket + 리뷰 리스크 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_review_sla_alerts"
                plan.arguments["slaHours"] shouldBe 24
            }

            @Test
            fun `bitbucket 없이 리뷰 리스크만 있으면 null 을 반환해야 한다`() {
                val plan = WorkContextBitbucketPlanner.planMiscBitbucket(
                    ctx("review risk 현황")
                )

                assertNull(plan, "bitbucket 명시가 없으면 plan이 null이어야 한다")
            }
        }
    }

    // ────────────────────────────────────────
    // WorkContextJiraPlanner
    // ────────────────────────────────────────

    @Nested
    @DisplayName("WorkContextJiraPlanner")
    inner class JiraPlannerTests {

        @Nested
        @DisplayName("planJiraSearch — Jira 검색")
        inner class JiraSearch {

            @Test
            fun `jira + 내 이슈 힌트 — jira_my_open_issues 를 반환해야 한다`() {
                val prompt = "jira 내 이슈 보여줘"
                val plan = WorkContextJiraPlanner.planJiraSearch(
                    prompt,
                    ctx(prompt.lowercase())
                )

                assertNotNull(plan, "jira + 내 이슈 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_my_open_issues"
                plan.arguments["maxResults"] shouldBe 20
            }

            @Test
            fun `프로젝트 키 + 내 오픈 이슈 — jira_my_open_issues 에 project 포함해야 한다`() {
                val prompt = "PAY 프로젝트 내 오픈 이슈 확인해줘"
                val plan = WorkContextJiraPlanner.planJiraSearch(
                    prompt,
                    ctx(prompt.lowercase(), projectKey = "PAY")
                )

                assertNotNull(plan, "projectKey와 내 이슈 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_my_open_issues"
                assertTrue(plan.arguments.containsKey("project"), "jira_my_open_issues 인자에 project 키가 있어야 한다")
            }
        }

        @Nested
        @DisplayName("planJiraProjectScoped — 프로젝트 스코프")
        inner class JiraProjectScoped {

            @Test
            fun `최근 이슈 힌트 — jira_search_issues 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("최근 jira 이슈 정리해줘", projectKey = "DEV")
                ) { false }

                assertNotNull(plan, "최근 이슈 힌트 + projectKey가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_search_issues"
                (plan.arguments["jql"] as String).let {
                    it.contains("DEV") shouldBe true
                }
            }

            @Test
            fun `지연 힌트 — work_morning_briefing 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("PAY 프로젝트 지연 이슈", projectKey = "PAY")
                ) { false }

                assertNotNull(plan, "지연 힌트 + projectKey가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "work_morning_briefing"
            }

            @Test
            fun `릴리즈 + 이슈 힌트 — jira_search_by_text 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("release 이슈 검색해줘", projectKey = "DEV")
                ) { false }

                assertNotNull(plan, "릴리즈 + 이슈 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_search_by_text"
                plan.arguments["keyword"] shouldBe "release"
            }

            @Test
            fun `미할당 힌트 — jira_search_issues(unassigned JQL) 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("담당자 없는 이슈 보여줘", projectKey = "BACK")
                ) { false }

                assertNotNull(plan, "미할당 힌트 + projectKey가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_search_issues"
                (plan.arguments["jql"] as String).let {
                    it.contains("EMPTY") shouldBe true
                }
            }

            @Test
            fun `projectKey 가 없으면 null 을 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("최근 jira 이슈", projectKey = null)
                ) { false }

                assertNull(plan, "projectKey가 없으면 plan이 null이어야 한다")
            }

            @Test
            fun `hasDownstreamProjectHints 가 true 이면 기본 폴백을 반환하지 않아야 한다`() {
                // 하위 핸들러에 위임해야 하므로 기본 폴백(jira_search_issues) 반환 없음
                val plan = WorkContextJiraPlanner.planJiraProjectScoped(
                    ctx("브리핑 요청", projectKey = "DEV")
                ) { true }

                assertNull(plan, "hasDownstreamProjectHints=true이면 기본 폴백 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planBlockerAndBriefingFallback — 블로커/브리핑 폴백")
        inner class BlockerAndBriefing {

            @Test
            fun `블로커 힌트 + inferredProjectKey — jira_blocker_digest 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                    ctx("blocker 이슈 정리해줘", inferredProjectKey = "PAY")
                )

                assertNotNull(plan, "blocker 힌트 + inferredProjectKey가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_blocker_digest"
                plan.arguments["project"] shouldBe "PAY"
                plan.arguments["maxResults"] shouldBe 25
            }

            @Test
            fun `업무 브리핑 힌트 — work_morning_briefing 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                    ctx("업무 브리핑 해줘", inferredProjectKey = "DEV")
                )

                assertNotNull(plan, "업무 브리핑 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "work_morning_briefing"
            }

            @Test
            fun `daily briefing 힌트 — jira_daily_briefing 를 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                    ctx("오늘의 jira 브리핑 해줘", inferredProjectKey = "DEV")
                )

                assertNotNull(plan, "daily briefing 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_daily_briefing"
            }

            @Test
            fun `inferredProjectKey 가 없으면 null 을 반환해야 한다`() {
                val plan = WorkContextJiraPlanner.planBlockerAndBriefingFallback(
                    ctx("blocker 이슈", inferredProjectKey = null)
                )

                assertNull(plan, "inferredProjectKey가 없으면 plan이 null이어야 한다")
            }
        }
    }

    // ────────────────────────────────────────
    // WorkContextDiscoveryPlanner
    // ────────────────────────────────────────

    @Nested
    @DisplayName("WorkContextDiscoveryPlanner")
    inner class DiscoveryPlannerTests {

        @Nested
        @DisplayName("planListAndSearch — 목록 조회 및 검색")
        inner class ListAndSearch {

            @Test
            fun `confluence 스페이스 목록 힌트 — confluence_list_spaces 를 반환해야 한다`() {
                val prompt = "confluence 스페이스 목록 보여줘"
                val plan = WorkContextDiscoveryPlanner.planListAndSearch(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "confluence 스페이스 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "confluence_list_spaces"
            }

            @Test
            fun `confluence 검색 힌트 — confluence_search_by_text 를 반환해야 한다`() {
                val prompt = "confluence에서 검색해줘"
                val plan = WorkContextDiscoveryPlanner.planListAndSearch(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "confluence 검색 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "confluence_search_by_text"
                plan.arguments["limit"] shouldBe 10
            }

            @Test
            fun `bitbucket 저장소 목록 힌트 — bitbucket_list_repositories 를 반환해야 한다`() {
                val prompt = "저장소 목록 보여줘"
                val plan = WorkContextDiscoveryPlanner.planListAndSearch(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "저장소 목록 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "bitbucket_list_repositories"
            }

            @Test
            fun `jira 프로젝트 목록 힌트 — jira_list_projects 를 반환해야 한다`() {
                val prompt = "jira 프로젝트 목록 보여줘"
                val plan = WorkContextDiscoveryPlanner.planListAndSearch(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "jira 프로젝트 목록 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "jira_list_projects"
            }

            @Test
            fun `매칭 힌트가 없으면 null 을 반환해야 한다`() {
                val prompt = "오늘 날씨 어때?"
                val plan = WorkContextDiscoveryPlanner.planListAndSearch(
                    prompt, ctx(prompt.lowercase())
                )

                assertNull(plan, "매칭 힌트가 없으면 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planSwagger — Swagger/OpenAPI")
        inner class Swagger {

            @Test
            fun `swagger + 검증 힌트 — spec_validate 를 반환해야 한다`() {
                val prompt = "swagger spec 검증해줘"
                val plan = WorkContextDiscoveryPlanner.planSwagger(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "swagger 검증 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "spec_validate"
            }

            @Test
            fun `swagger + 요약 힌트(스펙명 있음) — spec_summary 를 반환해야 한다`() {
                val prompt = "payments swagger spec 요약해줘"
                val plan = WorkContextDiscoveryPlanner.planSwagger(
                    prompt,
                    ctx(prompt.lowercase(), swaggerSpecName = "payments")
                )

                assertNotNull(plan, "swagger 요약 힌트 + 스펙명이 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "spec_summary"
                plan.arguments["specName"] shouldBe "payments"
                plan.arguments["scope"] shouldBe "published"
            }

            @Test
            fun `swagger + 잘못된 endpoint 힌트 — spec_list 를 반환해야 한다`() {
                val prompt = "swagger에서 없는 endpoint 찾아줘"
                val plan = WorkContextDiscoveryPlanner.planSwagger(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "swagger + 잘못된 endpoint 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "spec_list"
            }

            @Test
            fun `swagger 컨텍스트 없으면 null 을 반환해야 한다`() {
                val prompt = "payments api 검증해줘"
                val plan = WorkContextDiscoveryPlanner.planSwagger(
                    prompt, ctx(prompt.lowercase())
                )

                assertNull(plan, "swagger/openapi/spec 키워드가 없으면 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planHybridRiskAndDiscovery — 하이브리드 리스크/문서 검색")
        inner class HybridRiskAndDiscovery {

            @Test
            fun `따옴표 키워드 + confluence 검색 힌트 — confluence_search_by_text 를 반환해야 한다`() {
                val prompt = "Confluence에서 'runbook' 관련 문서가 있으면 찾아줘"
                val plan = WorkContextDiscoveryPlanner.planHybridRiskAndDiscovery(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "따옴표 키워드 + 검색 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "confluence_search_by_text"
                plan.arguments["keyword"] shouldBe "runbook"
                plan.arguments["limit"] shouldBe 10
            }

            @Test
            fun `따옴표 키워드 없으면 null 을 반환해야 한다`() {
                val prompt = "confluence에서 문서 찾아줘"
                val plan = WorkContextDiscoveryPlanner.planHybridRiskAndDiscovery(
                    prompt, ctx(prompt.lowercase())
                )

                assertNull(plan, "따옴표 키워드가 없고 하이브리드 힌트도 없으면 plan이 null이어야 한다")
            }
        }

        @Nested
        @DisplayName("planSpecLoadAndBriefingFallback — 스펙 로드 폴백")
        inner class SpecLoadFallback {

            @Test
            fun `specUrl + swagger 키워드 — spec_load 를 반환해야 한다`() {
                val specUrl = "https://api.example.com/openapi.json"
                val prompt = "$specUrl swagger 스펙 로드해줘"
                val plan = WorkContextDiscoveryPlanner.planSpecLoadAndBriefingFallback(
                    prompt,
                    ctx(prompt.lowercase(), specUrl = specUrl)
                )

                assertNotNull(plan, "specUrl + swagger 키워드가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "spec_load"
                plan.arguments["url"] shouldBe specUrl
            }

            @Test
            fun `로드된 스펙 + 엔드포인트 힌트 — spec_list 를 반환해야 한다`() {
                val prompt = "로드된 스펙의 endpoint 목록 보여줘"
                val plan = WorkContextDiscoveryPlanner.planSpecLoadAndBriefingFallback(
                    prompt, ctx(prompt.lowercase())
                )

                assertNotNull(plan, "로드된 스펙 + 엔드포인트 힌트가 있으면 plan이 null이면 안 된다")
                plan!!.toolName shouldBe "spec_list"
            }

            @Test
            fun `specUrl 없고 매칭 힌트 없으면 null 을 반환해야 한다`() {
                val prompt = "오늘 날씨 어때?"
                val plan = WorkContextDiscoveryPlanner.planSpecLoadAndBriefingFallback(
                    prompt, ctx(prompt.lowercase())
                )

                assertNull(plan, "매칭 힌트가 없으면 plan이 null이어야 한다")
            }
        }
    }
}
