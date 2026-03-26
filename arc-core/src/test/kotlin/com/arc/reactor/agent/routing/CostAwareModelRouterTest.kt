package com.arc.reactor.agent.routing

import com.arc.reactor.agent.config.ModelRoutingProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.ResponseFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CostAwareModelRouter 단위 테스트.
 *
 * 비용/품질 기반 모델 라우팅의 핵심 동작을 검증한다.
 */
class CostAwareModelRouterTest {

    private val defaultProps = ModelRoutingProperties(
        enabled = true,
        defaultModel = "gemini-2.5-flash",
        highComplexityModel = "gemini-2.5-pro",
        routingStrategy = "balanced",
        complexityThresholdChars = 500
    )

    private fun router(props: ModelRoutingProperties = defaultProps) = CostAwareModelRouter(props)

    // --- 사용자 지정 모델 우선 ---

    @Test
    fun `사용자가 모델을 지정하면 해당 모델을 반환해야 한다`() {
        val command = AgentCommand(
            systemPrompt = "You are helpful.",
            userPrompt = "Hello",
            model = "custom-model-v1"
        )

        val selection = router().route(command)

        assertEquals("custom-model-v1", selection.modelId, "사용자 지정 모델이 우선되어야 한다")
        assertTrue(selection.reason.contains("사용자 지정"), "사유에 '사용자 지정'이 포함되어야 한다")
    }

    // --- 복잡도 분석 ---

    @Test
    fun `짧은 단순 질문은 낮은 복잡도여야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hi",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val complexity = router().analyzeComplexity(command)

        assertTrue(complexity < 0.3, "단순 질문의 복잡도는 0.3 미만이어야 한다: $complexity")
    }

    @Test
    fun `긴 입력과 도구 사용은 높은 복잡도여야 한다`() {
        val longInput = "x".repeat(2000)
        val command = AgentCommand(
            systemPrompt = longInput,
            userPrompt = longInput,
            mode = AgentMode.REACT,
            maxToolCalls = 10,
            conversationHistory = (1..6).map {
                com.arc.reactor.agent.model.Message(
                    role = com.arc.reactor.agent.model.MessageRole.USER,
                    content = "turn $it"
                )
            },
            responseFormat = ResponseFormat.JSON
        )

        val complexity = router().analyzeComplexity(command)

        assertTrue(complexity >= 0.7, "복잡한 요청의 복잡도는 0.7 이상이어야 한다: $complexity")
    }

    // --- balanced 전략 ---

    @Test
    fun `balanced 전략에서 단순 요청은 기본 모델을 선택해야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "What is 1+1?",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val selection = router().route(command)

        assertEquals("gemini-2.5-flash", selection.modelId, "단순 요청은 기본 모델이어야 한다")
    }

    @Test
    fun `balanced 전략에서 복잡한 요청은 고급 모델을 선택해야 한다`() {
        val longInput = "x".repeat(2000)
        val command = AgentCommand(
            systemPrompt = longInput,
            userPrompt = longInput,
            mode = AgentMode.REACT,
            maxToolCalls = 10,
            conversationHistory = (1..6).map {
                com.arc.reactor.agent.model.Message(
                    role = com.arc.reactor.agent.model.MessageRole.USER,
                    content = "turn $it"
                )
            },
            responseFormat = ResponseFormat.JSON
        )

        val selection = router().route(command)

        assertEquals("gemini-2.5-pro", selection.modelId, "복잡한 요청은 고급 모델이어야 한다")
    }

    // --- cost-optimized 전략 ---

    @Test
    fun `cost-optimized 전략에서 중간 복잡도는 기본 모델을 유지해야 한다`() {
        val props = defaultProps.copy(routingStrategy = "cost-optimized")
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "x".repeat(600),
            mode = AgentMode.REACT,
            maxToolCalls = 5
        )

        val selection = router(props).route(command)

        assertEquals("gemini-2.5-flash", selection.modelId, "cost-optimized에서 중간 복잡도는 기본 모델이어야 한다")
    }

    // --- quality-first 전략 ---

    @Test
    fun `quality-first 전략에서 중간 복잡도는 고급 모델을 선택해야 한다`() {
        val props = defaultProps.copy(routingStrategy = "quality-first")
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "x".repeat(600),
            mode = AgentMode.REACT,
            maxToolCalls = 5
        )

        val selection = router(props).route(command)

        assertEquals("gemini-2.5-pro", selection.modelId, "quality-first에서 중간 복잡도는 고급 모델이어야 한다")
    }

    // --- 복잡도 범위 검증 ---

    @Test
    fun `복잡도 점수는 0과 1 사이여야 한다`() {
        val extremeCommand = AgentCommand(
            systemPrompt = "x".repeat(10000),
            userPrompt = "x".repeat(10000),
            mode = AgentMode.REACT,
            maxToolCalls = 100,
            conversationHistory = (1..20).map {
                com.arc.reactor.agent.model.Message(
                    role = com.arc.reactor.agent.model.MessageRole.USER,
                    content = "turn $it"
                )
            },
            responseFormat = ResponseFormat.JSON
        )

        val complexity = router().analyzeComplexity(extremeCommand)

        assertTrue(complexity in 0.0..1.0, "복잡도는 0.0~1.0 범위여야 한다: $complexity")
    }

    // --- 엣지 케이스: 빈/null 입력 ---

    @Test
    fun `빈 systemPrompt와 userPrompt는 낮은 복잡도여야 한다`() {
        val command = AgentCommand(
            systemPrompt = "",
            userPrompt = "",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val complexity = router().analyzeComplexity(command)

        assertTrue(complexity < 0.3, "빈 입력의 복잡도는 0.3 미만이어야 한다: $complexity")
        assertTrue(complexity in 0.0..1.0, "빈 입력도 복잡도는 0.0~1.0 범위여야 한다: $complexity")
    }

    @Test
    fun `빈 모델 문자열은 사용자 지정으로 처리되지 않아야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hi",
            model = ""
        )

        val selection = router().route(command)

        assertEquals("gemini-2.5-flash", selection.modelId, "빈 model은 무시되고 기본 모델이어야 한다")
    }

    @Test
    fun `공백만 있는 모델 문자열은 사용자 지정으로 처리되지 않아야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hi",
            model = "   "
        )

        val selection = router().route(command)

        assertEquals("gemini-2.5-flash", selection.modelId, "공백 model은 무시되고 기본 모델이어야 한다")
    }

    // --- 엣지 케이스: 극단적인 토큰 수 ---

    @Test
    fun `매우 긴 입력(100만 자)도 복잡도 상한 1_0을 초과하지 않아야 한다`() {
        val command = AgentCommand(
            systemPrompt = "s".repeat(500_000),
            userPrompt = "u".repeat(500_000),
            mode = AgentMode.REACT,
            maxToolCalls = 999
        )

        val complexity = router().analyzeComplexity(command)

        assertTrue(complexity <= 1.0, "극단적 입력의 복잡도는 1.0을 초과하면 안 된다: $complexity")
    }

    @Test
    fun `maxToolCalls=1이면 ReAct 모드여도 도구 복잡도가 추가되지 않아야 한다`() {
        val commandWith1Tool = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hi",
            mode = AgentMode.REACT,
            maxToolCalls = 1
        )
        val commandWithNoTool = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hi",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val complexityWith1Tool = router().analyzeComplexity(commandWith1Tool)
        val complexityWithNoTool = router().analyzeComplexity(commandWithNoTool)

        assertEquals(
            complexityWithNoTool,
            complexityWith1Tool,
            "maxToolCalls=1이면 도구 복잡도 가산 없이 동일해야 한다"
        )
    }

    // --- 엣지 케이스: 세 전략 동작 완전 검증 ---

    @Test
    fun `cost-optimized 전략에서 복잡도 0_7 이상이면 고급 모델을 선택해야 한다`() {
        val props = defaultProps.copy(routingStrategy = "cost-optimized")
        // 0.7 이상 복잡도: 긴 입력 + ReAct + 많은 도구
        val longInput = "x".repeat(2000)
        val command = AgentCommand(
            systemPrompt = longInput,
            userPrompt = longInput,
            mode = AgentMode.REACT,
            maxToolCalls = 10,
            responseFormat = ResponseFormat.JSON
        )

        val selection = router(props).route(command)

        assertEquals(
            "gemini-2.5-pro",
            selection.modelId,
            "cost-optimized에서 복잡도 0.7 이상이면 고급 모델이어야 한다"
        )
    }

    @Test
    fun `quality-first 전략에서 빈 입력은 기본 모델을 선택해야 한다`() {
        val props = defaultProps.copy(routingStrategy = "quality-first")
        val command = AgentCommand(
            systemPrompt = "",
            userPrompt = "",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val selection = router(props).route(command)

        assertEquals(
            "gemini-2.5-flash",
            selection.modelId,
            "quality-first에서 복잡도 0.2 이하(빈 입력)는 기본 모델이어야 한다"
        )
    }

    @Test
    fun `알 수 없는 전략은 balanced로 폴백해야 한다`() {
        val props = defaultProps.copy(routingStrategy = "unknown-strategy")
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "What is 1+1?",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val selection = router(props).route(command)

        // balanced 전략과 동일하게 단순 요청 → 기본 모델
        assertEquals("gemini-2.5-flash", selection.modelId, "알 수 없는 전략은 balanced로 폴백해야 한다")
    }

    @Test
    fun `라우팅 결과에 complexityScore가 포함되어야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "x".repeat(600),
            mode = AgentMode.REACT,
            maxToolCalls = 5
        )

        val selection = router().route(command)

        assertTrue(
            selection.complexityScore != null,
            "complexityScore가 null이 아니어야 한다"
        )
        assertTrue(
            selection.complexityScore!! in 0.0..1.0,
            "complexityScore는 0.0~1.0 범위여야 한다: " +
                "${selection.complexityScore}"
        )
    }

    @Test
    fun `사용자 지정 모델일 때 complexityScore는 null이어야 한다`() {
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Hello",
            model = "custom-v1"
        )

        val selection = router().route(command)

        assertEquals("custom-v1", selection.modelId)
        assertEquals(
            null, selection.complexityScore,
            "사용자 지정 모델은 복잡도 분석을 건너뛰므로 null이어야 한다"
        )
    }

    @Test
    fun `라우팅 전략 이름은 대소문자 구분 없이 동작해야 한다`() {
        val propsCostUpper = defaultProps.copy(routingStrategy = "COST-OPTIMIZED")
        val command = AgentCommand(
            systemPrompt = "Help",
            userPrompt = "Simple question",
            mode = AgentMode.STANDARD,
            maxToolCalls = 0
        )

        val selection = router(propsCostUpper).route(command)

        assertEquals(
            "gemini-2.5-flash",
            selection.modelId,
            "대문자 전략 이름도 동일하게 처리되어야 한다"
        )
    }
}
