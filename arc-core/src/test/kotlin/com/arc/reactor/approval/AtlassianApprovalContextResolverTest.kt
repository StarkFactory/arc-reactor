package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [AtlassianApprovalContextResolver] 단위 테스트.
 *
 * R225: 실제 atlassian-mcp-server 도구 이름과 인수 구조를 기반으로 카테고리 분류,
 * impactScope 추출, action 문자열 생성을 검증한다.
 */
class AtlassianApprovalContextResolverTest {

    private val resolver = AtlassianApprovalContextResolver()

    @Nested
    inner class Categorization {

        @Test
        fun `jira_ prefix는 JIRA 카테고리여야 한다`() {
            assertEquals(
                AtlassianCategory.JIRA,
                resolver.categorize("jira_get_issue"),
                "jira_get_issue는 JIRA 카테고리"
            )
            assertEquals(
                AtlassianCategory.JIRA,
                resolver.categorize("jira_search_by_text"),
                "jira_search_by_text도 JIRA"
            )
            assertEquals(
                AtlassianCategory.JIRA,
                resolver.categorize("jira_my_open_issues"),
                "jira_my_open_issues도 JIRA"
            )
        }

        @Test
        fun `confluence_ prefix는 CONFLUENCE 카테고리여야 한다`() {
            assertEquals(
                AtlassianCategory.CONFLUENCE,
                resolver.categorize("confluence_answer_question"),
                "confluence_answer_question은 CONFLUENCE"
            )
            assertEquals(
                AtlassianCategory.CONFLUENCE,
                resolver.categorize("confluence_search_by_text"),
                "confluence_search_by_text도 CONFLUENCE"
            )
        }

        @Test
        fun `bitbucket_ prefix는 BITBUCKET 카테고리여야 한다`() {
            assertEquals(
                AtlassianCategory.BITBUCKET,
                resolver.categorize("bitbucket_list_prs"),
                "bitbucket_list_prs는 BITBUCKET"
            )
            assertEquals(
                AtlassianCategory.BITBUCKET,
                resolver.categorize("bitbucket_my_authored_prs"),
                "bitbucket_my_authored_prs도 BITBUCKET"
            )
            assertEquals(
                AtlassianCategory.BITBUCKET,
                resolver.categorize("bitbucket_review_queue"),
                "bitbucket_review_queue도 BITBUCKET"
            )
        }

        @Test
        fun `알 수 없는 prefix는 null을 반환해야 한다`() {
            assertNull(resolver.categorize("jenkins_build"))
            assertNull(resolver.categorize("work_morning_briefing"))
            assertNull(resolver.categorize("spec_list"))
            assertNull(resolver.categorize(""))
        }
    }

    @Nested
    inner class JiraScopeExtraction {

        @Test
        fun `issueKey가 있으면 최우선으로 선택되어야 한다`() {
            val args = mapOf(
                "issueKey" to "HRFW-5695",
                "project" to "HRFW"
            )
            val scope = resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            assertEquals("HRFW-5695", scope) { "issueKey가 project보다 우선" }
        }

        @Test
        fun `project가 issueKey 없을 때 선택되어야 한다`() {
            val args = mapOf("project" to "HRFW")
            assertEquals(
                "HRFW",
                resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            ) { "project 단독" }
        }

        @Test
        fun `jql이 project 없을 때 선택되어야 한다`() {
            val args = mapOf(
                "jql" to "project = HRFW AND status = 'In Progress'"
            )
            val scope = resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            assertNotNull(scope) { "jql 값이 사용되어야 한다" }
            assertTrue(scope!!.contains("project = HRFW")) {
                "jql 원본이 포함되어야 한다"
            }
        }

        @Test
        fun `keyword fallback이 동작해야 한다`() {
            val args = mapOf("keyword" to "온보딩")
            assertEquals(
                "온보딩",
                resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            ) { "keyword fallback" }
        }

        @Test
        fun `매우 긴 jql은 IMPACT_SCOPE_MAX_LEN에서 잘려야 한다`() {
            val longJql = "project = HRFW AND " + "status = 'Open' OR ".repeat(20)
            val args = mapOf("jql" to longJql)
            val scope = resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            assertNotNull(scope) { "scope가 존재해야 한다" }
            assertTrue(scope!!.length <= AtlassianApprovalContextResolver.IMPACT_SCOPE_MAX_LEN) {
                "scope 길이는 ${AtlassianApprovalContextResolver.IMPACT_SCOPE_MAX_LEN}자 이하여야 한다: " +
                    "실제=${scope.length}"
            }
        }

        @Test
        fun `빈 맵은 null을 반환해야 한다`() {
            assertNull(resolver.extractImpactScope(AtlassianCategory.JIRA, emptyMap()))
        }

        @Test
        fun `매칭되지 않는 키는 무시되어야 한다`() {
            val args = mapOf("unrelatedKey" to "value")
            assertNull(resolver.extractImpactScope(AtlassianCategory.JIRA, args))
        }

        @Test
        fun `빈 문자열은 건너뛰고 다음 키를 확인해야 한다`() {
            val args = mapOf(
                "issueKey" to "",
                "project" to "HRFW"
            )
            assertEquals(
                "HRFW",
                resolver.extractImpactScope(AtlassianCategory.JIRA, args)
            ) { "빈 issueKey는 건너뛰고 project 사용" }
        }
    }

    @Nested
    inner class ConfluenceScopeExtraction {

        @Test
        fun `pageId가 최우선이어야 한다`() {
            val args = mapOf(
                "pageId" to "123456",
                "spaceKey" to "DEV"
            )
            assertEquals(
                "123456",
                resolver.extractImpactScope(AtlassianCategory.CONFLUENCE, args)
            )
        }

        @Test
        fun `spaceKey가 pageId 없을 때 선택되어야 한다`() {
            val args = mapOf("spaceKey" to "ONBOARDING")
            assertEquals(
                "ONBOARDING",
                resolver.extractImpactScope(AtlassianCategory.CONFLUENCE, args)
            )
        }

        @Test
        fun `query가 spaceKey 없을 때 선택되어야 한다`() {
            val args = mapOf("query" to "배포 가이드")
            assertEquals(
                "배포 가이드",
                resolver.extractImpactScope(AtlassianCategory.CONFLUENCE, args)
            )
        }

        @Test
        fun `question은 answer_question 도구의 인수로 사용되어야 한다`() {
            // confluence_answer_question의 주요 인수는 question
            val args = mapOf("question" to "개발 환경 세팅 방법")
            assertEquals(
                "개발 환경 세팅 방법",
                resolver.extractImpactScope(AtlassianCategory.CONFLUENCE, args)
            )
        }
    }

    @Nested
    inner class BitbucketScopeExtraction {

        @Test
        fun `pullRequestId가 최우선이어야 한다`() {
            val args = mapOf(
                "pullRequestId" to "42",
                "repoSlug" to "web-labs"
            )
            assertEquals(
                "42",
                resolver.extractImpactScope(AtlassianCategory.BITBUCKET, args)
            )
        }

        @Test
        fun `repoSlug가 pullRequestId 없을 때 선택되어야 한다`() {
            val args = mapOf("repoSlug" to "edubank_ios")
            assertEquals(
                "edubank_ios",
                resolver.extractImpactScope(AtlassianCategory.BITBUCKET, args)
            )
        }

        @Test
        fun `workspace fallback이 동작해야 한다`() {
            val args = mapOf("workspace" to "ihunet")
            assertEquals(
                "ihunet",
                resolver.extractImpactScope(AtlassianCategory.BITBUCKET, args)
            )
        }
    }

    @Nested
    inner class EndToEndResolve {

        @Test
        fun `jira_get_issue with issueKey는 완전한 컨텍스트를 반환해야 한다`() {
            val ctx = resolver.resolve(
                "jira_get_issue",
                mapOf("issueKey" to "HRFW-5695")
            )
            assertNotNull(ctx) { "컨텍스트가 반환되어야 한다" }
            assertTrue(ctx!!.reason?.contains("Jira") == true) {
                "reason에 Jira가 포함되어야 한다"
            }
            assertEquals("jira_get_issue(HRFW-5695)", ctx.action) {
                "action은 도구(이슈키) 형태여야 한다"
            }
            assertEquals("HRFW-5695", ctx.impactScope) { "impactScope는 이슈 키" }
            assertEquals(Reversibility.REVERSIBLE, ctx.reversibility) {
                "read-only tool은 REVERSIBLE"
            }
        }

        @Test
        fun `jira_search_issues with jql은 완전한 컨텍스트를 반환해야 한다`() {
            val ctx = resolver.resolve(
                "jira_search_issues",
                mapOf(
                    "jql" to "project = HRFW AND status = 'In Progress'",
                    "maxResults" to 20
                )
            )
            assertNotNull(ctx) { "컨텍스트가 반환되어야 한다" }
            assertTrue(ctx!!.reason?.contains("Jira") == true) {
                "reason에 Jira"
            }
            assertTrue(ctx.action!!.contains("jira_search_issues(")) {
                "action이 jira_search_issues 로 시작해야 한다"
            }
            assertTrue(ctx.impactScope!!.contains("HRFW")) {
                "impactScope에 프로젝트 키 포함"
            }
        }

        @Test
        fun `confluence_answer_question with question은 완전한 컨텍스트를 반환해야 한다`() {
            val ctx = resolver.resolve(
                "confluence_answer_question",
                mapOf("question" to "배포 가이드")
            )
            assertNotNull(ctx) { "컨텍스트가 반환되어야 한다" }
            assertTrue(ctx!!.reason?.contains("Confluence") == true) {
                "reason에 Confluence"
            }
            assertEquals("confluence_answer_question(배포 가이드)", ctx.action)
            assertEquals("배포 가이드", ctx.impactScope)
        }

        @Test
        fun `bitbucket_list_prs with workspace and repo는 우선순위대로 동작해야 한다`() {
            val ctx = resolver.resolve(
                "bitbucket_list_prs",
                mapOf(
                    "workspace" to "ihunet",
                    "repoSlug" to "web-labs",
                    "state" to "OPEN"
                )
            )
            assertNotNull(ctx) { "컨텍스트가 반환되어야 한다" }
            // repoSlug가 workspace보다 우선
            assertEquals("web-labs", ctx!!.impactScope) {
                "repoSlug가 workspace보다 우선"
            }
            assertEquals("bitbucket_list_prs(web-labs)", ctx.action)
        }

        @Test
        fun `bitbucket_get_pr with pullRequestId는 PR을 action에 노출해야 한다`() {
            val ctx = resolver.resolve(
                "bitbucket_get_pr",
                mapOf(
                    "workspace" to "ihunet",
                    "repoSlug" to "web-labs",
                    "pullRequestId" to "42"
                )
            )
            assertNotNull(ctx)
            assertEquals("42", ctx!!.impactScope) { "pullRequestId 최우선" }
            assertEquals("bitbucket_get_pr(42)", ctx.action)
        }

        @Test
        fun `인수가 없는 도구도 최소한의 컨텍스트를 반환해야 한다`() {
            val ctx = resolver.resolve("jira_list_projects", emptyMap())
            assertNotNull(ctx) { "인수 없어도 컨텍스트 반환" }
            assertEquals(Reversibility.REVERSIBLE, ctx!!.reversibility)
            assertEquals("jira_list_projects", ctx.action) { "action은 toolName만" }
            assertNull(ctx.impactScope) { "impactScope는 null" }
            assertNotNull(ctx.reason) { "reason은 항상 존재" }
        }

        @Test
        fun `지원하지 않는 도구는 null을 반환해야 한다`() {
            assertNull(
                resolver.resolve("jenkins_trigger_build", mapOf("job" to "deploy")),
                "jenkins_ prefix는 지원 안 함"
            )
            assertNull(
                resolver.resolve("work_morning_briefing", emptyMap()),
                "work_ prefix는 atlassian 아님"
            )
        }
    }

    @Nested
    inner class RealAtlassianToolNames {

        /**
         * 실제 atlassian-mcp-server가 노출하는 도구 이름들로 전수 검증.
         * 회귀 테스트로 기능하여, 리졸버가 모든 기존 도구를 커버하는지 확인한다.
         */
        @Test
        fun `실제 Jira 도구 이름 전체가 JIRA로 분류되어야 한다`() {
            val jiraTools = listOf(
                "jira_get_issue",
                "jira_get_comments",
                "jira_get_issue_changelog",
                "jira_search_issues",
                "jira_search_by_text",
                "jira_search_my_issues_by_text",
                "jira_my_open_issues",
                "jira_due_soon_issues",
                "jira_daily_briefing",
                "jira_blocker_digest",
                "jira_list_projects",
                "jira_search_users",
                "jira_get_transitions"
            )
            jiraTools.forEach { tool ->
                assertEquals(
                    AtlassianCategory.JIRA,
                    resolver.categorize(tool),
                    "$tool 은 JIRA 카테고리여야 한다"
                )
            }
        }

        @Test
        fun `실제 Confluence 도구 이름 전체가 CONFLUENCE로 분류되어야 한다`() {
            val confluenceTools = listOf(
                "confluence_search",
                "confluence_search_by_text",
                "confluence_answer_question",
                "confluence_get_page",
                "confluence_get_page_content",
                "confluence_get_page_comments",
                "confluence_get_children",
                "confluence_list_spaces",
                "confluence_generate_weekly_auto_summary_draft"
            )
            confluenceTools.forEach { tool ->
                assertEquals(
                    AtlassianCategory.CONFLUENCE,
                    resolver.categorize(tool),
                    "$tool 은 CONFLUENCE 카테고리여야 한다"
                )
            }
        }

        @Test
        fun `실제 Bitbucket 도구 이름 전체가 BITBUCKET으로 분류되어야 한다`() {
            val bitbucketTools = listOf(
                "bitbucket_list_prs",
                "bitbucket_get_pr",
                "bitbucket_get_pr_diff",
                "bitbucket_my_authored_prs",
                "bitbucket_review_queue",
                "bitbucket_review_sla_alerts",
                "bitbucket_stale_prs",
                "bitbucket_list_repositories",
                "bitbucket_list_branches",
                "bitbucket_list_commits"
            )
            bitbucketTools.forEach { tool ->
                assertEquals(
                    AtlassianCategory.BITBUCKET,
                    resolver.categorize(tool),
                    "$tool 은 BITBUCKET 카테고리여야 한다"
                )
            }
        }

        @Test
        fun `모든 실제 도구는 non-null 컨텍스트를 반환해야 한다`() {
            val allTools = listOf(
                // Jira
                "jira_get_issue", "jira_search_issues", "jira_my_open_issues",
                "jira_daily_briefing", "jira_list_projects",
                // Confluence
                "confluence_answer_question", "confluence_search_by_text", "confluence_list_spaces",
                // Bitbucket
                "bitbucket_list_prs", "bitbucket_review_queue", "bitbucket_list_repositories"
            )
            allTools.forEach { tool ->
                val ctx = resolver.resolve(tool, emptyMap())
                assertNotNull(ctx) { "$tool 은 non-null 컨텍스트를 반환해야 한다" }
                assertEquals(Reversibility.REVERSIBLE, ctx!!.reversibility) {
                    "$tool 은 REVERSIBLE이어야 한다 (read-only)"
                }
                assertNotNull(ctx.reason) { "$tool 의 reason은 존재해야 한다" }
                assertNotNull(ctx.action) { "$tool 의 action은 존재해야 한다" }
            }
        }
    }

    @Nested
    inner class ReasonTextSanity {

        @Test
        fun `Jira reason은 Jira 키워드를 포함해야 한다`() {
            val ctx = resolver.resolve("jira_get_issue", mapOf("issueKey" to "DEV-1"))
            assertTrue(ctx!!.reason!!.contains("Jira")) { "Jira 키워드 포함" }
        }

        @Test
        fun `Confluence reason은 Confluence 키워드를 포함해야 한다`() {
            val ctx = resolver.resolve("confluence_search", mapOf("query" to "abc"))
            assertTrue(ctx!!.reason!!.contains("Confluence")) { "Confluence 키워드 포함" }
        }

        @Test
        fun `Bitbucket reason은 Bitbucket 키워드를 포함해야 한다`() {
            val ctx = resolver.resolve("bitbucket_list_prs", emptyMap())
            assertTrue(ctx!!.reason!!.contains("Bitbucket")) { "Bitbucket 키워드 포함" }
        }

        @Test
        fun `reason에는 원본 도구 이름이 포함되어야 한다`() {
            val ctx = resolver.resolve("jira_my_open_issues", emptyMap())
            assertTrue(ctx!!.reason!!.contains("jira_my_open_issues")) {
                "reason에 원본 도구 이름 포함"
            }
        }
    }
}
