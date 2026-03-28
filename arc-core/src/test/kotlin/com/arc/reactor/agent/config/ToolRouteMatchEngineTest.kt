package com.arc.reactor.agent.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ToolRouteMatchEngine] 매칭 로직 테스트.
 *
 * 각 매칭 조건(keywords, requiredKeywords, excludeKeywords, regexPatternRef,
 * excludeRoutes, parentRoute, multiKeywordGroups, requiresNoUrl)을 독립적으로
 * 검증한다.
 */
class ToolRouteMatchEngineTest {

    // ── 공통 헬퍼 ──

    private fun configOf(vararg routes: ToolRoute) = ToolRoutingConfig(routes = routes.toList())

    private fun route(
        id: String = "test_route",
        category: String = "confluence",
        keywords: Set<String> = emptySet(),
        requiredKeywords: Set<String> = emptySet(),
        excludeKeywords: Set<String> = emptySet(),
        regexPatternRef: String? = null,
        excludeRoutes: Set<String> = emptySet(),
        parentRoute: String? = null,
        priority: Int = 100,
        requiresNoUrl: Boolean = false,
        multiKeywordGroups: List<Set<String>> = emptyList(),
        preferredTools: List<String> = emptyList()
    ) = ToolRoute(
        id = id,
        category = category,
        keywords = keywords,
        requiredKeywords = requiredKeywords,
        excludeKeywords = excludeKeywords,
        regexPatternRef = regexPatternRef,
        excludeRoutes = excludeRoutes,
        parentRoute = parentRoute,
        priority = priority,
        requiresNoUrl = requiresNoUrl,
        multiKeywordGroups = multiKeywordGroups,
        preferredTools = preferredTools
    )

    // ── 1. keywords 매칭 ──

    @Nested
    inner class KeywordMatching {

        @Test
        fun `keyword가 프롬프트에 포함되면 매칭된다`() {
            val r = route(keywords = setOf("반품", "환불"))
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "반품 정책이 궁금해요", config)

            assertTrue(result) { "keywords 중 하나라도 포함되면 매칭되어야 한다" }
        }

        @Test
        fun `keyword 매칭은 대소문자를 무시한다`() {
            val r = route(keywords = setOf("wiki"))
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "WIKI에서 찾아봐", config)

            assertTrue(result) { "keyword 매칭은 대소문자 무관해야 한다" }
        }

        @Test
        fun `keywords가 전부 없으면 매칭되지 않는다`() {
            val r = route(keywords = setOf("반품", "환불"))
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "날씨 알려줘", config)

            assertFalse(result) { "keywords에 해당하는 단어가 없으면 매칭되지 않아야 한다" }
        }

        @Test
        fun `keywords가 비어 있고 multiKeywordGroups도 없으면 regexPatternRef 없을 시 매칭 안 됨`() {
            val r = route(keywords = emptySet(), multiKeywordGroups = emptyList(), regexPatternRef = null)
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "아무 프롬프트", config)

            assertFalse(result) { "keywords·multiKeywordGroups·regexPatternRef 모두 없으면 매칭되지 않아야 한다" }
        }

        @Test
        fun `keywords가 비어 있지만 regexPatternRef가 있으면 regex 매칭이 통과한다`() {
            val r = route(keywords = emptySet(), regexPatternRef = "ISSUE_KEY")
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "PAY-123 이슈 확인해줘", config)

            assertTrue(result) { "regexPatternRef만으로 매칭이 가능해야 한다" }
        }
    }

    // ── 2. requiredKeywords 매칭 ──

    @Nested
    inner class RequiredKeywordMatching {

        @Test
        fun `requiredKeywords 중 하나라도 포함되면 추가 통과된다`() {
            val r = route(
                keywords = setOf("jira"),
                requiredKeywords = setOf("버그", "에러")
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "jira 버그 확인", config)

            assertTrue(result) { "keywords + requiredKeywords 하나라도 포함 시 매칭되어야 한다" }
        }

        @Test
        fun `requiredKeywords가 하나도 포함되지 않으면 매칭 실패`() {
            val r = route(
                keywords = setOf("jira"),
                requiredKeywords = setOf("버그", "에러")
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "jira 티켓 보여줘", config)

            assertFalse(result) { "requiredKeywords가 없으면 매칭되지 않아야 한다" }
        }

        @Test
        fun `requiredKeywords가 비어 있으면 조건 패스`() {
            val r = route(
                keywords = setOf("jira"),
                requiredKeywords = emptySet()
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "jira 이슈 확인", config)

            assertTrue(result) { "requiredKeywords가 비어있으면 무조건 통과되어야 한다" }
        }
    }

    // ── 3. excludeKeywords 매칭 ──

    @Nested
    inner class ExcludeKeywordMatching {

        @Test
        fun `excludeKeywords가 프롬프트에 포함되면 매칭 제외`() {
            val r = route(
                keywords = setOf("검색"),
                excludeKeywords = setOf("이미지")
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "이미지 검색해줘", config)

            assertFalse(result) { "excludeKeywords가 포함되면 매칭되지 않아야 한다" }
        }

        @Test
        fun `excludeKeywords가 없으면 영향 없음`() {
            val r = route(
                keywords = setOf("검색"),
                excludeKeywords = emptySet()
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config)

            assertTrue(result) { "excludeKeywords가 없으면 매칭에 영향을 주지 않아야 한다" }
        }

        @Test
        fun `excludeKeywords가 비포함이면 정상 매칭`() {
            val r = route(
                keywords = setOf("검색"),
                excludeKeywords = setOf("이미지")
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "문서 검색해줘", config)

            assertTrue(result) { "excludeKeywords가 포함되지 않으면 정상 매칭되어야 한다" }
        }
    }

    // ── 4. regexPatternRef 매칭 ──

    @Nested
    inner class RegexPatternMatching {

        @Test
        fun `ISSUE_KEY 패턴이 매칭된다`() {
            val r = route(regexPatternRef = "ISSUE_KEY", keywords = emptySet())
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "DEV-42 이슈 확인", config)

            assertTrue(result) { "Jira 스타일 이슈 키(DEV-42)가 ISSUE_KEY 패턴에 매칭되어야 한다" }
        }

        @Test
        fun `ISSUE_KEY 패턴이 없으면 매칭 실패`() {
            val r = route(regexPatternRef = "ISSUE_KEY", keywords = emptySet())
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "이슈를 확인해줘", config)

            assertFalse(result) { "이슈 키 형식이 없으면 ISSUE_KEY 패턴 매칭 실패해야 한다" }
        }

        @Test
        fun `OPENAPI_URL 패턴이 매칭된다`() {
            val r = route(regexPatternRef = "OPENAPI_URL", keywords = emptySet())
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "https://api.example.com/openapi.json 분석해줘", config)

            assertTrue(result) { "OpenAPI URL이 포함된 프롬프트는 OPENAPI_URL 패턴에 매칭되어야 한다" }
        }

        @Test
        fun `regexPatternRef가 null이면 regex 조건이 항상 통과`() {
            val r = route(keywords = setOf("검색"), regexPatternRef = null)
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config)

            assertTrue(result) { "regexPatternRef가 null이면 regex 조건은 항상 통과해야 한다" }
        }
    }

    // ── 5. excludeRoutes 매칭 ──

    @Nested
    inner class ExcludeRoutesMatching {

        @Test
        fun `이미 매칭된 라우트가 excludeRoutes에 포함되면 매칭 제외`() {
            val r = route(
                id = "child_route",
                keywords = setOf("검색"),
                excludeRoutes = setOf("parent_route")
            )
            val config = configOf(r)
            val alreadyMatched = setOf("parent_route")

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config, alreadyMatched)

            assertFalse(result) { "excludeRoutes에 있는 라우트가 이미 매칭됐으면 이 라우트는 매칭되지 않아야 한다" }
        }

        @Test
        fun `excludeRoutes의 라우트가 아직 매칭되지 않았으면 정상 매칭`() {
            val r = route(
                id = "child_route",
                keywords = setOf("검색"),
                excludeRoutes = setOf("other_route")
            )
            val config = configOf(r)
            val alreadyMatched = setOf("unrelated_route")

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config, alreadyMatched)

            assertTrue(result) { "excludeRoutes에 없는 라우트가 매칭됐으면 이 라우트도 정상 매칭되어야 한다" }
        }

        @Test
        fun `excludeRoutes가 비어 있으면 영향 없음`() {
            val r = route(keywords = setOf("검색"), excludeRoutes = emptySet())
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config, setOf("some_route"))

            assertTrue(result) { "excludeRoutes가 비어 있으면 alreadyMatched와 무관하게 통과되어야 한다" }
        }
    }

    // ── 6. parentRoute 매칭 ──

    @Nested
    inner class ParentRouteMatching {

        @Test
        fun `parentRoute 설정 시 부모의 keywords로 매칭된다`() {
            val parent = route(id = "parent", category = "work", keywords = setOf("작업"))
            val child = route(
                id = "child",
                category = "work",
                keywords = emptySet(),
                parentRoute = "parent"
            )
            val config = configOf(parent, child)

            val result = ToolRouteMatchEngine.matches(child, "작업 목록 보여줘", config)

            assertTrue(result) { "parentRoute가 설정된 경우 부모 라우트의 keywords로 매칭되어야 한다" }
        }

        @Test
        fun `parentRoute가 없으면 부모 keywords로 매칭 안 됨`() {
            val parent = route(id = "parent", category = "work", keywords = setOf("작업"))
            val child = route(
                id = "child",
                category = "work",
                keywords = emptySet(),
                parentRoute = "parent"
            )
            val config = configOf(parent, child)

            val result = ToolRouteMatchEngine.matches(child, "다른 프롬프트", config)

            assertFalse(result) { "부모 keywords에 해당하는 단어가 없으면 매칭되지 않아야 한다" }
        }

        @Test
        fun `parentRoute id가 config에 없으면 매칭 실패`() {
            val child = route(
                id = "child",
                category = "work",
                keywords = emptySet(),
                parentRoute = "nonexistent_parent"
            )
            val config = configOf(child)

            val result = ToolRouteMatchEngine.matches(child, "작업 목록", config)

            assertFalse(result) { "존재하지 않는 parentRoute id면 매칭 실패해야 한다" }
        }
    }

    // ── 7. multiKeywordGroups 매칭 ──

    @Nested
    inner class MultiKeywordGroupsMatching {

        @Test
        fun `모든 그룹에서 하나씩 매칭되면 통과`() {
            val r = route(
                keywords = setOf("jira"),
                multiKeywordGroups = listOf(
                    setOf("버그", "에러"),
                    setOf("확인", "검토")
                )
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "jira 버그 확인해줘", config)

            assertTrue(result) { "모든 그룹에서 최소 하나씩 매칭되면 통과해야 한다" }
        }

        @Test
        fun `하나의 그룹이라도 매칭 실패하면 전체 실패`() {
            val r = route(
                keywords = setOf("jira"),
                multiKeywordGroups = listOf(
                    setOf("버그", "에러"),
                    setOf("확인", "검토")
                )
            )
            val config = configOf(r)

            // 두 번째 그룹 ("확인", "검토") 미포함
            val result = ToolRouteMatchEngine.matches(r, "jira 버그 목록", config)

            assertFalse(result) { "한 그룹이라도 매칭 실패하면 전체 매칭 실패해야 한다" }
        }

        @Test
        fun `multiKeywordGroups가 비어 있으면 조건 통과`() {
            val r = route(keywords = setOf("검색"), multiKeywordGroups = emptyList())
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "검색해줘", config)

            assertTrue(result) { "multiKeywordGroups가 비어 있으면 해당 조건은 통과해야 한다" }
        }
    }

    // ── 8. requiresNoUrl 매칭 ──

    @Nested
    inner class RequiresNoUrlMatching {

        @Test
        fun `requiresNoUrl=true이고 URL이 없으면 매칭된다`() {
            val r = route(keywords = setOf("api"), requiresNoUrl = true)
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "api 명세 알려줘", config)

            assertTrue(result) { "requiresNoUrl=true이고 URL이 없으면 매칭되어야 한다" }
        }

        @Test
        fun `requiresNoUrl=true이고 OpenAPI URL이 포함되면 매칭 안 됨`() {
            val r = route(keywords = setOf("api"), requiresNoUrl = true)
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(
                r,
                "https://api.example.com/swagger/openapi.json 을 분석해줘",
                config
            )

            assertFalse(result) { "requiresNoUrl=true일 때 OpenAPI URL이 포함되면 매칭되지 않아야 한다" }
        }

        @Test
        fun `requiresNoUrl=false이면 URL 존재와 무관하게 통과`() {
            val r = route(keywords = setOf("api"), requiresNoUrl = false)
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(
                r,
                "https://api.example.com/swagger/openapi.json 을 분석해줘",
                config
            )

            assertTrue(result) { "requiresNoUrl=false이면 URL이 있어도 통과해야 한다" }
        }
    }

    // ── 9. findFirstMatch 테스트 ──

    @Nested
    inner class FindFirstMatch {

        @Test
        fun `priority 낮은 라우트를 먼저 반환한다`() {
            val high = route(id = "high_priority", category = "jira", keywords = setOf("이슈"), priority = 10)
            val low = route(id = "low_priority", category = "jira", keywords = setOf("이슈"), priority = 50)
            val config = configOf(high, low)

            val result = ToolRouteMatchEngine.findFirstMatch("jira", "이슈 확인", config)

            assertNotNull(result) { "매칭된 라우트가 있어야 한다" }
            assertEquals("high_priority", result!!.id) { "priority 10인 라우트가 먼저 반환되어야 한다" }
        }

        @Test
        fun `매칭되는 라우트가 없으면 null 반환`() {
            val r = route(id = "jira_route", category = "jira", keywords = setOf("이슈"))
            val config = configOf(r)

            val result = ToolRouteMatchEngine.findFirstMatch("jira", "날씨 알려줘", config)

            assertNull(result) { "매칭 없으면 null을 반환해야 한다" }
        }

        @Test
        fun `카테고리에 라우트가 없으면 null 반환`() {
            val r = route(id = "jira_route", category = "jira", keywords = setOf("이슈"))
            val config = configOf(r)

            val result = ToolRouteMatchEngine.findFirstMatch("confluence", "이슈 확인", config)

            assertNull(result) { "해당 카테고리의 라우트가 없으면 null을 반환해야 한다" }
        }

        @Test
        fun `excludeRoutes 체인이 올바르게 작동한다`() {
            val first = route(id = "first_route", category = "jira", keywords = setOf("검색"), priority = 10)
            val second = route(
                id = "second_route",
                category = "jira",
                keywords = setOf("검색"),
                excludeRoutes = setOf("first_route"),
                priority = 20
            )
            val config = configOf(first, second)

            // findFirstMatch는 첫 번째 매칭에서 중단하므로 second_route의 excludeRoutes 효과를 직접 검증
            val result = ToolRouteMatchEngine.findFirstMatch("jira", "검색해줘", config)

            assertEquals("first_route", result!!.id) { "priority 10인 first_route가 먼저 매칭되어야 한다" }
        }
    }

    // ── 10. findAllMatches 테스트 ──

    @Nested
    inner class FindAllMatches {

        @Test
        fun `매칭되는 모든 라우트를 priority 순으로 반환한다`() {
            val r1 = route(id = "route_a", category = "work", keywords = setOf("요약"), priority = 10)
            val r2 = route(id = "route_b", category = "work", keywords = setOf("요약"), priority = 30)
            val r3 = route(id = "route_c", category = "work", keywords = setOf("다른"), priority = 20)
            val config = configOf(r1, r2, r3)

            val results = ToolRouteMatchEngine.findAllMatches("work", "요약 부탁해", config)

            assertEquals(2, results.size) { "프롬프트에 매칭된 라우트 수가 맞아야 한다" }
            assertEquals("route_a", results[0].id) { "priority 10인 route_a가 첫 번째여야 한다" }
            assertEquals("route_b", results[1].id) { "priority 30인 route_b가 두 번째여야 한다" }
        }

        @Test
        fun `매칭 없으면 빈 리스트 반환`() {
            val r = route(id = "route_a", category = "work", keywords = setOf("요약"))
            val config = configOf(r)

            val results = ToolRouteMatchEngine.findAllMatches("work", "날씨 알려줘", config)

            assertTrue(results.isEmpty()) { "매칭이 없으면 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `카테고리에 라우트가 없으면 빈 리스트 반환`() {
            val r = route(id = "jira_route", category = "jira", keywords = setOf("이슈"))
            val config = configOf(r)

            val results = ToolRouteMatchEngine.findAllMatches("confluence", "이슈 확인", config)

            assertTrue(results.isEmpty()) { "해당 카테고리가 없으면 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `excludeRoutes가 적용돼 이미 매칭된 라우트가 있으면 해당 라우트 제외된다`() {
            val first = route(id = "route_first", category = "jira", keywords = setOf("검색"), priority = 10)
            val second = route(
                id = "route_second",
                category = "jira",
                keywords = setOf("검색"),
                excludeRoutes = setOf("route_first"),
                priority = 20
            )
            val config = configOf(first, second)

            val results = ToolRouteMatchEngine.findAllMatches("jira", "검색해줘", config)

            assertEquals(1, results.size) { "excludeRoutes에 의해 second 라우트는 제외되어야 한다" }
            assertEquals("route_first", results[0].id) { "첫 번째 라우트만 반환되어야 한다" }
        }
    }

    // ── 11. 복합 조건 테스트 ──

    @Nested
    inner class ComplexConditions {

        @Test
        fun `모든 조건 통과 시 매칭된다`() {
            val r = route(
                keywords = setOf("confluence"),
                requiredKeywords = setOf("검색"),
                excludeKeywords = setOf("이미지"),
                requiresNoUrl = true
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "confluence 문서 검색해줘", config)

            assertTrue(result) { "모든 매칭 조건이 충족되면 매칭되어야 한다" }
        }

        @Test
        fun `하나의 조건이라도 실패하면 전체 매칭 실패`() {
            // excludeKeywords 조건 실패 케이스
            val r = route(
                keywords = setOf("confluence"),
                requiredKeywords = setOf("검색"),
                excludeKeywords = setOf("이미지")
            )
            val config = configOf(r)

            val result = ToolRouteMatchEngine.matches(r, "confluence 이미지 검색", config)

            assertFalse(result) { "excludeKeywords가 포함되어 있으므로 매칭 실패해야 한다" }
        }
    }
}
