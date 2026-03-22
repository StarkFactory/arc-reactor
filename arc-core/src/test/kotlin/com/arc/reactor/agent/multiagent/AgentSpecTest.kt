package com.arc.reactor.agent.multiagent

import com.arc.reactor.agent.model.AgentMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * AgentSpec 데이터 클래스 테스트.
 *
 * 유효성 검증과 기본값을 확인한다.
 */
class AgentSpecTest {

    @Test
    fun `정상적인 AgentSpec 생성을 확인해야 한다`() {
        val spec = AgentSpec(
            id = "test-agent",
            name = "테스트 에이전트",
            description = "테스트용 에이전트",
            toolNames = listOf("tool1", "tool2"),
            keywords = listOf("키워드1"),
            mode = AgentMode.PLAN_EXECUTE
        )

        assertEquals("test-agent", spec.id, "ID가 일치해야 한다")
        assertEquals("테스트 에이전트", spec.name, "이름이 일치해야 한다")
        assertEquals(AgentMode.PLAN_EXECUTE, spec.mode, "모드가 일치해야 한다")
        assertEquals(2, spec.toolNames.size, "도구 수가 일치해야 한다")
    }

    @Test
    fun `기본값이 올바르게 설정되어야 한다`() {
        val spec = AgentSpec(
            id = "minimal",
            name = "최소 에이전트",
            description = "최소 설정",
            toolNames = emptyList()
        )

        assertEquals(AgentMode.REACT, spec.mode, "기본 모드는 REACT여야 한다")
        assertTrue(spec.keywords.isEmpty(), "기본 키워드는 비어있어야 한다")
        assertEquals(null, spec.systemPromptOverride, "기본 시스템 프롬프트 오버라이드는 null이어야 한다")
    }

    @Test
    fun `빈 ID로 생성 시 예외를 발생시켜야 한다`() {
        assertThrows<IllegalArgumentException>("빈 ID는 예외를 발생시켜야 한다") {
            AgentSpec(
                id = "",
                name = "테스트",
                description = "설명",
                toolNames = emptyList()
            )
        }
    }

    @Test
    fun `공백 ID로 생성 시 예외를 발생시켜야 한다`() {
        assertThrows<IllegalArgumentException>("공백 ID는 예외를 발생시켜야 한다") {
            AgentSpec(
                id = "   ",
                name = "테스트",
                description = "설명",
                toolNames = emptyList()
            )
        }
    }

    @Test
    fun `빈 이름으로 생성 시 예외를 발생시켜야 한다`() {
        assertThrows<IllegalArgumentException>("빈 이름은 예외를 발생시켜야 한다") {
            AgentSpec(
                id = "valid-id",
                name = "",
                description = "설명",
                toolNames = emptyList()
            )
        }
    }

    private fun assertTrue(condition: Boolean, message: String) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message)
    }
}
