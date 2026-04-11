package com.arc.reactor.agent.multiagent

import com.arc.reactor.agent.model.AgentMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultAgentRegistry 단위 테스트.
 *
 * 에이전트 등록, 해제, 조회, 키워드 매칭의 전체 동작을 검증한다.
 */
class AgentRegistryTest {

    private lateinit var registry: DefaultAgentRegistry

    private val jiraSpec = AgentSpec(
        id = "jira-agent",
        name = "Jira 전문 에이전트",
        description = "Jira 이슈 조회, 생성, 업데이트를 담당한다",
        toolNames = listOf("jira_search", "jira_create_issue"),
        keywords = listOf("jira", "이슈", "티켓", "스프린트")
    )

    private val confluenceSpec = AgentSpec(
        id = "confluence-agent",
        name = "Confluence 전문 에이전트",
        description = "Confluence 문서 검색 및 작성을 담당한다",
        toolNames = listOf("confluence_search", "confluence_create_page"),
        keywords = listOf("confluence", "문서", "위키", "페이지")
    )

    private val analysisSpec = AgentSpec(
        id = "analysis-agent",
        name = "분석 전문 에이전트",
        description = "데이터 분석 및 리포트 생성을 담당한다",
        toolNames = listOf("run_query", "generate_chart"),
        keywords = listOf("분석", "리포트", "차트", "통계"),
        mode = AgentMode.PLAN_EXECUTE
    )

    @BeforeEach
    fun setUp() {
        registry = DefaultAgentRegistry()
    }

    @Nested
    inner class RegisterAndUnregister {

        @Test
        fun `에이전트를 등록하면 findById로 조회할 수 있어야 한다`() {
            registry.register(jiraSpec)

            val found = registry.findById("jira-agent")
            assertNotNull(found, "등록된 에이전트는 조회 가능해야 한다")
            assertEquals("jira-agent", found?.id, "ID가 일치해야 한다")
            assertEquals("Jira 전문 에이전트", found?.name, "이름이 일치해야 한다")
        }

        @Test
        fun `동일 ID로 재등록하면 기존 에이전트를 덮어써야 한다`() {
            registry.register(jiraSpec)
            val updated = jiraSpec.copy(name = "업데이트된 Jira 에이전트")
            registry.register(updated)

            val found = registry.findById("jira-agent")
            assertEquals(
                "업데이트된 Jira 에이전트", found?.name,
                "재등록 시 기존 에이전트를 덮어써야 한다"
            )
        }

        @Test
        fun `에이전트를 해제하면 조회 불가능해야 한다`() {
            registry.register(jiraSpec)
            val removed = registry.unregister("jira-agent")

            assertTrue(removed, "등록된 에이전트 해제는 true를 반환해야 한다")
            assertNull(
                registry.findById("jira-agent"),
                "해제 후 조회 시 null이어야 한다"
            )
        }

        @Test
        fun `미등록 에이전트 해제 시 false를 반환해야 한다`() {
            val removed = registry.unregister("nonexistent")

            assertFalse(removed, "미등록 에이전트 해제는 false를 반환해야 한다")
        }
    }

    @Nested
    inner class FindAll {

        @Test
        fun `등록된 모든 에이전트를 반환해야 한다`() {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)
            registry.register(analysisSpec)

            val all = registry.findAll()
            assertEquals(3, all.size, "등록된 에이전트 수가 일치해야 한다")
            assertTrue(
                all.map { it.id }.containsAll(
                    listOf("jira-agent", "confluence-agent", "analysis-agent")
                ),
                "모든 에이전트 ID가 포함되어야 한다"
            )
        }

        @Test
        fun `에이전트가 없으면 빈 목록을 반환해야 한다`() {
            val all = registry.findAll()
            assertTrue(all.isEmpty(), "에이전트 미등록 시 빈 목록이어야 한다")
        }
    }

    @Nested
    inner class FindByCapability {

        @BeforeEach
        fun registerAll() {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)
            registry.register(analysisSpec)
        }

        @Test
        fun `키워드 매칭으로 올바른 에이전트를 찾아야 한다`() {
            val results = registry.findByCapability("Jira 이슈 목록 보여줘")

            assertTrue(results.isNotEmpty(), "매칭 결과가 있어야 한다")
            assertEquals(
                "jira-agent", results.first().id,
                "Jira 키워드에 jira-agent가 매칭되어야 한다"
            )
        }

        @Test
        fun `도구 이름으로도 매칭되어야 한다`() {
            val results = registry.findByCapability("confluence_search로 문서 찾아줘")

            assertTrue(results.isNotEmpty(), "도구 이름으로도 매칭되어야 한다")
            assertEquals(
                "confluence-agent", results.first().id,
                "confluence_search 도구에 confluence-agent가 매칭되어야 한다"
            )
        }

        @Test
        fun `대소문자 무시로 매칭되어야 한다`() {
            val results = registry.findByCapability("JIRA 티켓 확인해줘")

            assertTrue(results.isNotEmpty(), "대소문자 무시로 매칭되어야 한다")
            assertEquals(
                "jira-agent", results.first().id,
                "JIRA(대문자)도 jira-agent에 매칭되어야 한다"
            )
        }

        @Test
        fun `매칭 점수 순으로 정렬되어야 한다`() {
            val results = registry.findByCapability("jira 이슈 티켓 스프린트 조회")

            assertTrue(results.isNotEmpty(), "매칭 결과가 있어야 한다")
            assertEquals(
                "jira-agent", results.first().id,
                "여러 키워드 매칭 시 jira-agent가 최상위여야 한다"
            )
        }

        @Test
        fun `빈 쿼리는 빈 목록을 반환해야 한다`() {
            val results = registry.findByCapability("")

            assertTrue(
                results.isEmpty(),
                "빈 쿼리는 매칭 결과가 없어야 한다"
            )
        }

        @Test
        fun `매칭되지 않는 쿼리는 빈 목록을 반환해야 한다`() {
            val results = registry.findByCapability("날씨 알려줘")

            assertTrue(
                results.isEmpty(),
                "무관한 쿼리는 매칭 결과가 없어야 한다"
            )
        }

        @Test
        fun `설명 텍스트로도 매칭되어야 한다`() {
            val results = registry.findByCapability("데이터 리포트 만들어줘")

            assertTrue(results.isNotEmpty(), "설명 내 키워드로도 매칭되어야 한다")
            assertEquals(
                "analysis-agent", results.first().id,
                "리포트 키워드에 analysis-agent가 매칭되어야 한다"
            )
        }

        @Test
        fun `복수 에이전트가 매칭될 수 있어야 한다`() {
            val results = registry.findByCapability(
                "jira 이슈를 confluence 문서로 정리해줘"
            )

            assertTrue(
                results.size >= 2,
                "복수 에이전트 관련 쿼리는 여러 결과를 반환해야 한다"
            )
            val ids = results.map { it.id }
            assertTrue(
                ids.contains("jira-agent") && ids.contains("confluence-agent"),
                "jira-agent와 confluence-agent 모두 매칭되어야 한다"
            )
        }
    }

    @Nested
    inner class MatchScoreCalculation {

        @Test
        fun `키워드 매칭이 가장 높은 가중치를 가져야 한다`() {
            val score = DefaultAgentRegistry.calculateMatchScore(
                jiraSpec, "jira 관련 작업"
            )
            assertTrue(
                score >= DefaultAgentRegistry.KEYWORD_WEIGHT,
                "키워드 매칭 시 최소 KEYWORD_WEIGHT 점수여야 한다"
            )
        }

        @Test
        fun `도구 이름 매칭이 중간 가중치를 가져야 한다`() {
            val score = DefaultAgentRegistry.calculateMatchScore(
                jiraSpec, "jira_search 실행"
            )
            assertTrue(
                score >= DefaultAgentRegistry.TOOL_NAME_WEIGHT,
                "도구 이름 매칭 시 최소 TOOL_NAME_WEIGHT 점수여야 한다"
            )
        }

        @Test
        fun `매칭 없으면 점수가 0이어야 한다`() {
            val score = DefaultAgentRegistry.calculateMatchScore(
                jiraSpec, "날씨 정보 알려줘"
            )
            assertEquals(0, score, "매칭 없는 쿼리는 점수 0이어야 한다")
        }
    }

    @Nested
    inner class R311BoundedCache {

        /**
         * R311 회귀: ConcurrentHashMap → Caffeine bounded cache 마이그레이션.
         *
         * 기존 구현은 `register()`가 반복되면 무제한 성장 가능성이 있었다.
         * maxAgents 상한을 넘으면 W-TinyLFU 정책으로 evict되어야 한다.
         */
        @Test
        fun `maxAgents 초과 시 Caffeine이 evict해야 한다`() {
            val bounded = DefaultAgentRegistry(maxAgents = 5)
            repeat(100) { i ->
                bounded.register(
                    AgentSpec(
                        id = "agent-$i",
                        name = "Agent $i",
                        description = "desc $i",
                        toolNames = listOf("tool-$i"),
                        keywords = listOf("kw-$i")
                    )
                )
            }
            bounded.forceCleanUp()
            val all = bounded.findAll()
            assertTrue(all.size < 100) {
                "Expected eviction to reduce size below 100, got ${all.size}"
            }
            assertTrue(all.size <= 20) {
                "Expected Caffeine bounded cache to converge near maxAgents=5, got ${all.size}"
            }
        }

        @Test
        fun `DEFAULT_MAX_AGENTS는 1000이다`() {
            assertEquals(
                1_000L,
                DefaultAgentRegistry.DEFAULT_MAX_AGENTS,
                "Expected default max agents to be 1000"
            )
        }
    }
}
