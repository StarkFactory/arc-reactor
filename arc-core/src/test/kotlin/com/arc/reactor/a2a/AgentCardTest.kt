package com.arc.reactor.a2a

import com.arc.reactor.persona.InMemoryPersonaStore
import com.arc.reactor.persona.Persona
import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultAgentCardProvider에 대한 테스트.
 *
 * 도구와 페르소나를 기반으로 AgentCard가 올바르게 생성되는지 검증한다.
 */
class AgentCardTest {

    private fun tool(name: String, description: String = "desc", schema: String = """{"type":"object"}"""): ToolCallback {
        return object : ToolCallback {
            override val name = name
            override val description = description
            override val inputSchema = schema
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
    }

    private val defaultProperties = A2aProperties(
        enabled = true,
        agentName = "test-agent",
        agentVersion = "2.0.0",
        agentDescription = "Test Agent"
    )

    @Nested
    inner class BasicCardGenerationTest {

        @Test
        fun `설정된 이름과 버전이 카드에 반영되어야 한다`() {
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            assertEquals("test-agent", card.name) { "에이전트 이름이 일치해야 한다" }
            assertEquals("2.0.0", card.version) { "에이전트 버전이 일치해야 한다" }
            assertEquals("Test Agent", card.description) { "에이전트 설명이 일치해야 한다" }
        }

        @Test
        fun `기본 입출력 형식이 포함되어야 한다`() {
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            assertTrue(card.supportedInputFormats.contains("text")) {
                "입력 형식에 text가 포함되어야 한다"
            }
            assertTrue(card.supportedInputFormats.contains("json")) {
                "입력 형식에 json이 포함되어야 한다"
            }
            assertTrue(card.supportedOutputFormats.contains("yaml")) {
                "출력 형식에 yaml이 포함되어야 한다"
            }
        }
    }

    @Nested
    inner class ToolCapabilitiesTest {

        @Test
        fun `등록된 도구가 능력 목록에 포함되어야 한다`() {
            val tools = listOf(
                tool("search_order", "주문 검색", """{"type":"object","properties":{"orderId":{"type":"string"}}}"""),
                tool("check_status", "상태 확인")
            )
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = tools,
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            val toolCapabilities = card.capabilities.filter { !it.name.startsWith("persona:") }
            assertEquals(2, toolCapabilities.size) { "2개 도구가 능력 목록에 포함되어야 한다" }

            val searchCap = toolCapabilities.first { it.name == "search_order" }
            assertEquals("주문 검색", searchCap.description) { "도구 설명이 일치해야 한다" }
            assertTrue(searchCap.inputSchema!!.contains("orderId")) {
                "도구 inputSchema가 포함되어야 한다"
            }
        }

        @Test
        fun `도구가 없으면 도구 능력이 비어야 한다`() {
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            val toolCapabilities = card.capabilities.filter { !it.name.startsWith("persona:") }
            assertEquals(0, toolCapabilities.size) { "도구가 없으면 도구 능력이 비어야 한다" }
        }
    }

    @Nested
    inner class PersonaCapabilitiesTest {

        @Test
        fun `활성 페르소나가 능력 목록에 포함되어야 한다`() {
            val store = InMemoryPersonaStore()
            store.save(
                Persona(
                    id = "cs-agent",
                    name = "Customer Support",
                    systemPrompt = "You are a CS agent",
                    description = "고객 지원 에이전트",
                    isActive = true
                )
            )

            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = store
            )

            val card = provider.generate()

            val personaCaps = card.capabilities.filter { it.name.startsWith("persona:") }
            val csCap = personaCaps.firstOrNull { it.name == "persona:Customer Support" }
            assertTrue(csCap != null) {
                "활성 페르소나가 능력 목록에 포함되어야 한다, 실제: ${personaCaps.map { it.name }}"
            }
            assertEquals("고객 지원 에이전트", csCap!!.description) {
                "페르소나 설명이 반영되어야 한다"
            }
        }

        @Test
        fun `비활성 페르소나는 능력 목록에서 제외되어야 한다`() {
            val store = InMemoryPersonaStore()
            store.save(
                Persona(
                    id = "inactive",
                    name = "Inactive Agent",
                    systemPrompt = "inactive",
                    isActive = false
                )
            )

            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = store
            )

            val card = provider.generate()

            val personaCaps = card.capabilities.filter { it.name.startsWith("persona:") }
            // InMemoryPersonaStore에는 기본 페르소나("Default Assistant")가 있고,
            // 비활성 페르소나는 제외되어야 한다
            assertTrue(personaCaps.none { it.name == "persona:Inactive Agent" }) {
                "비활성 페르소나가 포함되면 안 된다, 실제: ${personaCaps.map { it.name }}"
            }
        }
    }

    @Nested
    inner class CombinedCapabilitiesTest {

        @Test
        fun `도구와 페르소나가 모두 능력 목록에 포함되어야 한다`() {
            val store = InMemoryPersonaStore()
            store.save(
                Persona(
                    id = "assistant",
                    name = "My Assistant",
                    systemPrompt = "assistant prompt",
                    description = "범용 어시스턴트",
                    isActive = true
                )
            )

            val tools = listOf(tool("search_tool", "검색 도구"))
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = tools,
                personaStore = store
            )

            val card = provider.generate()

            val toolCaps = card.capabilities.filter { !it.name.startsWith("persona:") }
            val personaCaps = card.capabilities.filter { it.name.startsWith("persona:") }

            assertEquals(1, toolCaps.size) { "도구 능력이 1개여야 한다" }
            assertTrue(personaCaps.isNotEmpty()) { "페르소나 능력이 1개 이상이어야 한다" }
            assertTrue(card.capabilities.size >= 2) {
                "도구와 페르소나 합산 능력이 2개 이상이어야 한다, 실제: ${card.capabilities.size}"
            }
        }
    }

    /**
     * 빈 페르소나 저장소 — list()가 빈 목록을 반환하는 최소 구현.
     * InMemoryPersonaStore는 기본 페르소나를 자동으로 등록하므로
     * 페르소나가 전혀 없는 시나리오를 테스트할 때 사용한다.
     */
    private fun emptyPersonaStore(): com.arc.reactor.persona.PersonaStore =
        object : com.arc.reactor.persona.PersonaStore {
            override fun list(): List<Persona> = emptyList()
            override fun get(personaId: String): Persona? = null
            override fun getDefault(): Persona? = null
            override fun save(persona: Persona): Persona = persona
            override fun update(
                personaId: String,
                name: String?,
                systemPrompt: String?,
                isDefault: Boolean?,
                description: String?,
                responseGuideline: String?,
                welcomeMessage: String?,
                icon: String?,
                promptTemplateId: String?,
                isActive: Boolean?
            ): Persona? = null
            override fun delete(personaId: String) {}
        }

    @Nested
    inner class NoToolsTest {

        @Test
        fun `도구가 없고 페르소나도 없으면 능력 목록이 비어야 한다`() {
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = emptyPersonaStore()
            )

            val card = provider.generate()

            assertEquals(0, card.capabilities.size) {
                "도구와 페르소나가 모두 없으면 능력 목록이 비어야 한다, 실제: ${card.capabilities.size}"
            }
        }

        @Test
        fun `도구가 없어도 카드의 기본 필드는 정상이어야 한다`() {
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            assertEquals("test-agent", card.name) { "도구가 없어도 에이전트 이름이 일치해야 한다" }
            assertEquals("2.0.0", card.version) { "도구가 없어도 에이전트 버전이 일치해야 한다" }
            assertTrue(card.supportedInputFormats.isNotEmpty()) {
                "도구가 없어도 지원 입력 형식이 있어야 한다"
            }
        }
    }

    @Nested
    inner class NoPersonasTest {

        @Test
        fun `저장된 페르소나가 없으면 도구 능력만 포함되어야 한다`() {
            val tools = listOf(tool("my_tool", "내 도구"))

            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = tools,
                personaStore = emptyPersonaStore()
            )

            val card = provider.generate()

            val toolCaps = card.capabilities.filter { !it.name.startsWith("persona:") }
            val personaCaps = card.capabilities.filter { it.name.startsWith("persona:") }
            assertEquals(1, toolCaps.size) { "도구 능력이 1개여야 한다" }
            assertEquals(0, personaCaps.size) { "페르소나가 없으면 페르소나 능력이 0개여야 한다" }
        }
    }

    @Nested
    inner class ToolDescriptionEdgeCasesTest {

        @Test
        fun `description이 빈 문자열인 도구도 능력에 포함되어야 한다`() {
            val tools = listOf(tool("no_desc_tool", description = ""))
            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = tools,
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            val toolCaps = card.capabilities.filter { !it.name.startsWith("persona:") }
            assertEquals(1, toolCaps.size) { "빈 설명을 가진 도구도 능력에 포함되어야 한다" }
            assertEquals("no_desc_tool", toolCaps[0].name) { "도구 이름이 일치해야 한다" }
            assertEquals("", toolCaps[0].description) { "빈 설명이 그대로 저장되어야 한다" }
        }

        @Test
        fun `inputSchema를 가진 도구는 능력에 스키마가 포함되어야 한다`() {
            val schema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
            val tools = listOf(tool("schema_tool", description = "스키마 도구", schema = schema))

            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = tools,
                personaStore = InMemoryPersonaStore()
            )

            val card = provider.generate()

            val toolCaps = card.capabilities.filter { !it.name.startsWith("persona:") }
            assertEquals(1, toolCaps.size) { "도구가 1개여야 한다" }
            assertEquals(schema, toolCaps[0].inputSchema) { "inputSchema가 그대로 저장되어야 한다" }
        }

        @Test
        fun `description이 없는 페르소나는 이름을 대신 설명으로 사용해야 한다`() {
            val store = InMemoryPersonaStore()
            store.save(
                Persona(
                    id = "no-desc",
                    name = "NoDescPersona",
                    systemPrompt = "system",
                    description = null, // description 없음
                    isActive = true
                )
            )

            val provider = DefaultAgentCardProvider(
                properties = defaultProperties,
                tools = emptyList(),
                personaStore = store
            )

            val card = provider.generate()

            val personaCap = card.capabilities.firstOrNull { it.name == "persona:NoDescPersona" }
            assertTrue(personaCap != null) { "description 없는 페르소나도 능력에 포함되어야 한다" }
            assertEquals("NoDescPersona", personaCap!!.description) {
                "description이 null이면 페르소나 이름이 설명으로 사용되어야 한다"
            }
        }
    }
}
