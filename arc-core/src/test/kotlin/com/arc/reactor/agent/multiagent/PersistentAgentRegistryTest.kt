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
 * PersistentAgentRegistry 단위 테스트.
 *
 * 인메모리 AgentSpecStore를 사용하여 DB 의존 없이 레지스트리 동작을 검증한다.
 */
class PersistentAgentRegistryTest {

    private lateinit var store: InMemoryAgentSpecStore
    private lateinit var registry: PersistentAgentRegistry

    private val hrRecord = AgentSpecRecord(
        id = "hr-agent",
        name = "HR 전문가",
        description = "인사, 연차, 복지 담당",
        toolNames = listOf("jira_search"),
        keywords = listOf("인사", "연차", "채용"),
        mode = "REACT",
        enabled = true
    )

    private val devRecord = AgentSpecRecord(
        id = "dev-agent",
        name = "Dev 전문가",
        description = "코드 리뷰, 배포 담당",
        toolNames = listOf("bitbucket_search"),
        keywords = listOf("코드", "배포", "PR"),
        mode = "REACT",
        enabled = true
    )

    private val disabledRecord = AgentSpecRecord(
        id = "disabled-agent",
        name = "비활성 에이전트",
        description = "비활성화된 에이전트",
        toolNames = emptyList(),
        keywords = listOf("비활성"),
        mode = "REACT",
        enabled = false
    )

    @BeforeEach
    fun setUp() {
        store = InMemoryAgentSpecStore()
        registry = PersistentAgentRegistry(store)
    }

    @Nested
    inner class FindAll {
        @Test
        fun `활성화된 에이전트만 반환한다`() {
            store.save(hrRecord)
            store.save(devRecord)
            store.save(disabledRecord)

            val result = registry.findAll()
            assertEquals(2, result.size) { "활성 에이전트 2개만 반환되어야 한다" }
            assertTrue(result.any { it.id == "hr-agent" }) { "HR 에이전트가 포함되어야 한다" }
            assertTrue(result.any { it.id == "dev-agent" }) { "Dev 에이전트가 포함되어야 한다" }
        }

        @Test
        fun `빈 저장소에서 빈 리스트를 반환한다`() {
            val result = registry.findAll()
            assertTrue(result.isEmpty()) { "빈 저장소에서 빈 리스트 반환" }
        }
    }

    @Nested
    inner class FindById {
        @Test
        fun `ID로 활성 에이전트를 조회한다`() {
            store.save(hrRecord)
            val result = registry.findById("hr-agent")
            assertNotNull(result) { "HR 에이전트가 조회되어야 한다" }
            assertEquals("HR 전문가", result?.name) { "이름이 일치해야 한다" }
        }

        @Test
        fun `비활성 에이전트는 null을 반환한다`() {
            store.save(disabledRecord)
            val result = registry.findById("disabled-agent")
            assertNull(result) { "비활성 에이전트는 조회되지 않아야 한다" }
        }

        @Test
        fun `존재하지 않는 ID는 null을 반환한다`() {
            assertNull(registry.findById("nonexistent")) { "존재하지 않는 ID는 null" }
        }
    }

    @Nested
    inner class Register {
        @Test
        fun `AgentSpec을 DB에 등록한다`() {
            val spec = AgentSpec(
                id = "new-agent",
                name = "신규 에이전트",
                description = "테스트",
                toolNames = listOf("tool1"),
                keywords = listOf("키워드")
            )
            registry.register(spec)

            val saved = store.get("new-agent")
            assertNotNull(saved) { "등록된 에이전트가 저장소에 있어야 한다" }
            assertEquals("신규 에이전트", saved?.name) { "이름이 일치해야 한다" }
        }
    }

    @Nested
    inner class Unregister {
        @Test
        fun `등록된 에이전트를 해제한다`() {
            store.save(hrRecord)
            val result = registry.unregister("hr-agent")
            assertTrue(result) { "등록 해제 성공" }
            assertNull(store.get("hr-agent")) { "저장소에서 삭제되어야 한다" }
        }

        @Test
        fun `존재하지 않는 에이전트 해제는 false를 반환한다`() {
            assertFalse(registry.unregister("nonexistent")) { "존재하지 않는 에이전트 해제 실패" }
        }
    }

    @Nested
    inner class FindByCapability {
        @Test
        fun `키워드로 관련 에이전트를 찾는다`() {
            store.save(hrRecord)
            store.save(devRecord)

            val result = registry.findByCapability("연차 신청")
            assertTrue(result.isNotEmpty()) { "연차 키워드로 HR 에이전트가 매칭되어야 한다" }
            assertEquals("hr-agent", result.first().id) { "HR 에이전트가 첫 번째" }
        }

        @Test
        fun `빈 쿼리는 빈 리스트를 반환한다`() {
            store.save(hrRecord)
            val result = registry.findByCapability("")
            assertTrue(result.isEmpty()) { "빈 쿼리 → 빈 결과" }
        }

        @Test
        fun `비활성 에이전트는 매칭되지 않는다`() {
            store.save(disabledRecord)
            val result = registry.findByCapability("비활성")
            assertTrue(result.isEmpty()) { "비활성 에이전트는 매칭 대상 아님" }
        }
    }

    @Nested
    inner class AgentSpecRecordConversion {
        @Test
        fun `AgentSpecRecord를 AgentSpec으로 변환한다`() {
            val spec = hrRecord.toAgentSpec()
            assertEquals("hr-agent", spec.id) { "ID 유지" }
            assertEquals("HR 전문가", spec.name) { "이름 유지" }
            assertEquals(listOf("jira_search"), spec.toolNames) { "도구 목록 유지" }
            assertEquals(AgentMode.REACT, spec.mode) { "모드 변환 정상" }
        }
    }
}

/** 테스트용 인메모리 AgentSpecStore. */
private class InMemoryAgentSpecStore : AgentSpecStore {
    private val data = mutableMapOf<String, AgentSpecRecord>()

    override fun list(): List<AgentSpecRecord> = data.values.toList()
    override fun listEnabled(): List<AgentSpecRecord> = data.values.filter { it.enabled }
    override fun get(id: String): AgentSpecRecord? = data[id]
    override fun save(record: AgentSpecRecord): AgentSpecRecord {
        data[record.id] = record
        return record
    }
    override fun delete(id: String) { data.remove(id) }
}
