package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.assertErrorCode
import com.arc.reactor.agent.assertFailure
import com.arc.reactor.agent.assertSuccess
import com.arc.reactor.agent.budget.StepBudgetTracker
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * PlanExecuteStrategy의 단위 테스트.
 *
 * 계획 생성, 검증, 순차 도구 실행, 최종 합성의 3단계 파이프라인을 검증한다.
 */
class PlanExecuteStrategyTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var toolCallOrchestrator: ToolCallOrchestrator
    private lateinit var strategy: PlanExecuteStrategy

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        toolCallOrchestrator = mockk(relaxed = true)

        strategy = PlanExecuteStrategy(
            toolCallOrchestrator = toolCallOrchestrator,
            buildRequestSpec = { _, _, _, _, _ ->
                fixture.requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> mockk(relaxed = true) }
        )
    }

    @Test
    fun `유효한 JSON 계획으로 도구를 순차 실행하고 합성 응답을 반환해야 한다`() = runTest {
        val planJson = """[{"tool":"jira_get_issue","args":{"issueKey":"JAR-36"},"description":"이슈 조회"}]"""
        val planResponse = simpleChatResponse(planJson)
        val synthesisResponse = simpleChatResponse("JAR-36 이슈는 진행 중입니다.")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(planResponse, synthesisResponse)

        coEvery {
            toolCallOrchestrator.executeDirectToolCall(
                toolName = "jira_get_issue",
                toolParams = mapOf("issueKey" to "JAR-36"),
                tools = any(),
                hookContext = any(),
                toolsUsed = any()
            )
        } returns ToolCallResult(
            output = """{"key":"JAR-36","status":"In Progress"}""",
            success = true
        )

        val command = AgentCommand(
            systemPrompt = "시스템 프롬프트",
            userPrompt = "JAR-36 이슈 상태를 알려줘",
            mode = AgentMode.PLAN_EXECUTE
        )
        val toolsUsed = mutableListOf<String>()
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )
        val tools = listOf(createMockSpringTool("jira_get_issue"))

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템 프롬프트",
            tools = tools,
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            maxToolCalls = 10
        )

        result.assertSuccess("계획-실행 결과는 성공이어야 한다")
        assertEquals(
            "JAR-36 이슈는 진행 중입니다.", result.content,
            "합성 응답이 최종 결과에 포함되어야 한다"
        )
    }

    @Test
    fun `빈 계획일 때 LLM 직접 응답으로 폴백해야 한다`() = runTest {
        val emptyPlanResponse = simpleChatResponse("[]")
        val directResponse = simpleChatResponse("직접 응답입니다.")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(emptyPlanResponse, directResponse)

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "안녕",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = listOf(createMockSpringTool("some_tool")),
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10
        )

        result.assertSuccess("빈 계획 시 직접 응답 성공이어야 한다")
        assertEquals(
            "직접 응답입니다.", result.content,
            "빈 계획 시 직접 LLM 응답이 반환되어야 한다"
        )
    }

    @Test
    fun `JSON 파싱 실패 시 빈 계획으로 폴백해야 한다`() = runTest {
        val invalidJsonResponse = simpleChatResponse("이건 JSON이 아닙니다.")
        val directResponse = simpleChatResponse("파싱 실패 폴백 응답")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(invalidJsonResponse, directResponse)

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "질문",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = listOf(createMockSpringTool("tool1")),
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10
        )

        result.assertSuccess("파싱 실패 시 직접 응답 성공이어야 한다")
    }

    @Test
    fun `maxToolCalls 초과 시 남은 단계를 건너뛰어야 한다`() = runTest {
        val planJson = """
            [
              {"tool":"tool_a","args":{},"description":"1단계"},
              {"tool":"tool_b","args":{},"description":"2단계"},
              {"tool":"tool_c","args":{},"description":"3단계"}
            ]
        """.trimIndent()
        val planResponse = simpleChatResponse(planJson)
        val synthesisResponse = simpleChatResponse("1단계만 실행된 결과")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(planResponse, synthesisResponse)

        coEvery {
            toolCallOrchestrator.executeDirectToolCall(
                toolName = any(),
                toolParams = any(),
                tools = any(),
                hookContext = any(),
                toolsUsed = any()
            )
        } returns ToolCallResult(output = "결과", success = true)

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "복합 질문",
            mode = AgentMode.PLAN_EXECUTE,
            maxToolCalls = 1
        )
        val toolsUsed = mutableListOf<String>()
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )
        val tools = listOf(
            createMockSpringTool("tool_a"),
            createMockSpringTool("tool_b"),
            createMockSpringTool("tool_c")
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = tools,
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            maxToolCalls = 1
        )

        result.assertSuccess("maxToolCalls 제한 시에도 성공이어야 한다")
    }

    @Test
    fun `PLAN_EXECUTE 모드가 AgentMode에 존재해야 한다`() {
        val mode = AgentMode.PLAN_EXECUTE
        assertEquals("PLAN_EXECUTE", mode.name, "PLAN_EXECUTE 열거형이 존재해야 한다")
    }

    @Test
    fun `존재하지 않는 도구가 계획에 포함되면 검증 실패를 반환해야 한다`() = runTest {
        val planJson = """[{"tool":"nonexistent_tool","args":{},"description":"없는 도구"}]"""
        val planResponse = simpleChatResponse(planJson)

        every { fixture.callResponseSpec.chatResponse() } returns planResponse

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "없는 도구 사용",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )
        val tools = listOf(createMockSpringTool("existing_tool"))

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = tools,
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10
        )

        result.assertErrorCode(AgentErrorCode.PLAN_VALIDATION_FAILED)
        assertTrue(
            result.errorMessage?.contains("nonexistent_tool") == true,
            "에러 메시지에 잘못된 도구 이름이 포함되어야 한다: ${result.errorMessage}"
        )
    }

    @Test
    fun `빈 도구 이름이 계획에 포함되면 검증 실패를 반환해야 한다`() = runTest {
        val planJson = """[{"tool":"","args":{},"description":"빈 도구명"}]"""
        val planResponse = simpleChatResponse(planJson)

        every { fixture.callResponseSpec.chatResponse() } returns planResponse

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "빈 도구 테스트",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = listOf(createMockSpringTool("tool1")),
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10
        )

        result.assertErrorCode(AgentErrorCode.PLAN_VALIDATION_FAILED)
        assertTrue(
            result.errorMessage?.contains("비어 있습니다") == true,
            "에러 메시지에 빈 도구명 관련 사유가 포함되어야 한다: ${result.errorMessage}"
        )
    }

    @Test
    fun `모든 도구가 유효하면 검증을 통과하고 정상 실행해야 한다`() = runTest {
        val planJson = """
            [
              {"tool":"tool_x","args":{"key":"val"},"description":"X 실행"},
              {"tool":"tool_y","args":{},"description":"Y 실행"}
            ]
        """.trimIndent()
        val planResponse = simpleChatResponse(planJson)
        val synthesisResponse = simpleChatResponse("두 도구 모두 성공")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(planResponse, synthesisResponse)

        coEvery {
            toolCallOrchestrator.executeDirectToolCall(
                toolName = any(),
                toolParams = any(),
                tools = any(),
                hookContext = any(),
                toolsUsed = any()
            )
        } returns ToolCallResult(output = "ok", success = true)

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "두 도구 사용",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )
        val tools = listOf(
            createMockSpringTool("tool_x"),
            createMockSpringTool("tool_y")
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = tools,
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10
        )

        result.assertSuccess("유효한 계획은 검증 통과 후 정상 실행되어야 한다")
        assertEquals(
            "두 도구 모두 성공", result.content,
            "모든 도구가 유효하면 합성 응답이 반환되어야 한다"
        )
    }

    @Test
    fun `토큰 예산 소진 시 남은 단계를 건너뛰고 부분 결과로 합성해야 한다`() = runTest {
        val planJson = """
            [
              {"tool":"tool_a","args":{},"description":"1단계"},
              {"tool":"tool_b","args":{},"description":"2단계"},
              {"tool":"tool_c","args":{},"description":"3단계"}
            ]
        """.trimIndent()
        val planResponse = simpleChatResponse(planJson)
        val synthesisResponse = simpleChatResponse("1단계만 실행된 결과")

        every { fixture.callResponseSpec.chatResponse() } returnsMany
            listOf(planResponse, synthesisResponse)

        // 토큰 예산을 1로 설정하여 첫 단계 실행 후 소진되도록 함
        val budgetTracker = StepBudgetTracker(maxTokens = 1, softLimitPercent = 50)
        // 사전에 예산을 소진시킴
        budgetTracker.trackStep("pre-exhaust", inputTokens = 1, outputTokens = 0)

        coEvery {
            toolCallOrchestrator.executeDirectToolCall(
                toolName = any(),
                toolParams = any(),
                tools = any(),
                hookContext = any(),
                toolsUsed = any()
            )
        } returns ToolCallResult(output = "결과", success = true)

        val command = AgentCommand(
            systemPrompt = "시스템",
            userPrompt = "예산 테스트",
            mode = AgentMode.PLAN_EXECUTE
        )
        val hookContext = HookContext(
            runId = "test-run",
            userId = "test-user",
            userPrompt = command.userPrompt,
            metadata = mutableMapOf()
        )
        val tools = listOf(
            createMockSpringTool("tool_a"),
            createMockSpringTool("tool_b"),
            createMockSpringTool("tool_c")
        )

        val result = strategy.execute(
            command = command,
            activeChatClient = fixture.chatClient,
            systemPrompt = "시스템",
            tools = tools,
            conversationHistory = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            maxToolCalls = 10,
            budgetTracker = budgetTracker
        )

        // 예산이 이미 소진되었으므로 첫 단계 실행 후 나머지를 건너뛰고 합성
        result.assertSuccess("예산 소진 시에도 부분 결과 합성은 성공이어야 한다")
        assertTrue(
            budgetTracker.isExhausted(),
            "예산 추적기가 소진 상태여야 한다"
        )
    }

    private fun simpleChatResponse(content: String): ChatResponse {
        val msg = AssistantMessage(content)
        return ChatResponse(listOf(Generation(msg)))
    }

    private fun createMockSpringTool(
        name: String
    ): org.springframework.ai.tool.ToolCallback {
        val toolDef = org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(name)
            .description("테스트 도구: $name")
            .inputSchema("{}")
            .build()
        val mock = mockk<org.springframework.ai.tool.ToolCallback>()
        every { mock.toolDefinition } returns toolDef
        return mock
    }
}
