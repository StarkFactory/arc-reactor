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
}
