package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.assertErrorCode
import com.arc.reactor.agent.assertFailure
import com.arc.reactor.agent.assertSuccess
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.plan.DefaultPlanValidator
import com.arc.reactor.agent.plan.PlanValidator
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * PlanExecuteStrategy 통합 테스트.
 *
 * 개별 단위 테스트(PlanExecuteStrategyTest)와 달리,
 * 실제 [DefaultPlanValidator]와 [SystemPromptBuilder]를 주입하여
 * 계획 생성 -> 검증 -> 단계 실행 -> 합성의 전체 파이프라인이
 * 올바르게 연동되는지 검증한다.
 */
@DisplayName("PlanExecuteStrategy 통합 테스트")
class PlanExecuteStrategyIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var toolCallOrchestrator: ToolCallOrchestrator
    private lateinit var systemPromptBuilder: SystemPromptBuilder
    private lateinit var planValidator: PlanValidator
    private lateinit var strategy: PlanExecuteStrategy

    /** 매 호출의 시스템 프롬프트를 캡처하기 위한 슬롯 */
    private val capturedSystemPrompts = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        toolCallOrchestrator = mockk(relaxed = true)
        systemPromptBuilder = SystemPromptBuilder()
        planValidator = DefaultPlanValidator()
        capturedSystemPrompts.clear()

        strategy = PlanExecuteStrategy(
            toolCallOrchestrator = toolCallOrchestrator,
            buildRequestSpec = { chatClient, systemPrompt, messages, options, tools ->
                capturedSystemPrompts.add(systemPrompt)
                fixture.requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> mockk(relaxed = true) },
            systemPromptBuilder = systemPromptBuilder,
            planValidator = planValidator
        )
    }

    // -- 헬퍼 메서드 --

    private fun simpleChatResponse(content: String): ChatResponse {
        val msg = AssistantMessage(content)
        return ChatResponse(listOf(Generation(msg)))
    }

    private fun createMockSpringTool(
        name: String,
        description: String = "테스트 도구: $name"
    ): org.springframework.ai.tool.ToolCallback {
        val toolDef = org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema("{}")
            .build()
        val mock = mockk<org.springframework.ai.tool.ToolCallback>()
        every { mock.toolDefinition } returns toolDef
        return mock
    }

    private fun defaultCommand(userPrompt: String) = AgentCommand(
        systemPrompt = "시스템 프롬프트",
        userPrompt = userPrompt,
        mode = AgentMode.PLAN_EXECUTE
    )

    private fun defaultHookContext(userPrompt: String) = HookContext(
        runId = "integration-test-run",
        userId = "test-user",
        userPrompt = userPrompt,
        metadata = mutableMapOf()
    )

    // =========================================================================
    // 1. 계획 생성 흐름 — SystemPromptBuilder.buildPlanningPrompt 연동
    // =========================================================================
    @Nested
    @DisplayName("계획 생성 흐름")
    inner class PlanGenerationFlow {

        @Test
        fun `계획 단계에서 SystemPromptBuilder의 planningPrompt가 시스템 프롬프트로 사용되어야 한다`() =
            runTest {
                val planJson =
                    """[{"tool":"jira_get_issue","args":{"key":"JAR-36"},"description":"이슈 조회"}]"""
                val synthesisText = "JAR-36은 진행 중입니다."

                every { fixture.callResponseSpec.chatResponse() } returnsMany
                    listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

                coEvery {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = any(), toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                } returns ToolCallResult(output = """{"status":"In Progress"}""", success = true)

                val tools = listOf(createMockSpringTool("jira_get_issue", "Jira 이슈 조회"))
                val command = defaultCommand("JAR-36 이슈 상태를 알려줘")

                strategy.execute(
                    command = command,
                    activeChatClient = fixture.chatClient,
                    systemPrompt = "시스템 프롬프트",
                    tools = tools,
                    conversationHistory = emptyList(),
                    hookContext = defaultHookContext(command.userPrompt),
                    toolsUsed = mutableListOf(),
                    maxToolCalls = 10
                )

                // 첫 번째 호출이 계획 생성 프롬프트여야 한다
                assertTrue(capturedSystemPrompts.isNotEmpty(), "시스템 프롬프트가 캡처되어야 한다")
                val planningPrompt = capturedSystemPrompts.first()
                planningPrompt shouldContain "플래너"
                planningPrompt shouldContain "JSON"
                planningPrompt shouldContain "jira_get_issue"
            }

        @Test
        fun `LLM이 반환한 JSON 계획이 PlanStep 객체로 올바르게 파싱되어야 한다`() = runTest {
            val planJson = """[
                {"tool":"search_docs","args":{"query":"architecture"},"description":"문서 검색"},
                {"tool":"summarize","args":{"text":"result"},"description":"요약"}
            ]"""
            val synthesisText = "문서 검색 및 요약 완료"

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "ok", success = true)

            val tools = listOf(
                createMockSpringTool("search_docs"),
                createMockSpringTool("summarize")
            )
            val command = defaultCommand("아키텍처 문서를 검색하고 요약해줘")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("2단계 계획이 파싱되어 실행 성공해야 한다")

            // 두 도구 모두 실행되었는지 확인
            coVerify(exactly = 2) {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }
    }

    // =========================================================================
    // 2. 계획 검증 흐름 — DefaultPlanValidator 연동
    // =========================================================================
    @Nested
    @DisplayName("계획 검증 흐름")
    inner class PlanValidationFlow {

        @Test
        fun `유효한 도구 이름으로 검증 통과 후 실행이 진행되어야 한다`() = runTest {
            val planJson =
                """[{"tool":"valid_tool","args":{"param":"value"},"description":"유효 도구 실행"}]"""
            val synthesisText = "도구 실행 결과입니다."

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "valid_tool", toolParams = mapOf("param" to "value"),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "result-data", success = true)

            val tools = listOf(createMockSpringTool("valid_tool"))
            val command = defaultCommand("유효 도구 테스트")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("유효한 도구명은 검증 통과 후 실행 성공해야 한다")
            coVerify(exactly = 1) {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "valid_tool", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }

        @Test
        fun `존재하지 않는 도구 이름이면 검증 실패하고 PLAN_VALIDATION_FAILED를 반환해야 한다`() =
            runTest {
                val planJson = """[
                    {"tool":"nonexistent_tool","args":{},"description":"없는 도구 호출"}
                ]"""

                every { fixture.callResponseSpec.chatResponse() } returns
                    simpleChatResponse(planJson)

                val tools = listOf(createMockSpringTool("existing_tool"))
                val command = defaultCommand("없는 도구로 실행 시도")

                val result = strategy.execute(
                    command = command,
                    activeChatClient = fixture.chatClient,
                    systemPrompt = "시스템 프롬프트",
                    tools = tools,
                    conversationHistory = emptyList(),
                    hookContext = defaultHookContext(command.userPrompt),
                    toolsUsed = mutableListOf(),
                    maxToolCalls = 10
                )

                result.assertErrorCode(AgentErrorCode.PLAN_VALIDATION_FAILED)
                result.errorMessage!! shouldContain "nonexistent_tool"

                // 검증 실패 시 도구 실행이 호출되지 않아야 한다
                coVerify(exactly = 0) {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = any(), toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                }
            }

        @Test
        fun `복수 도구 중 하나만 미등록이면 전체 계획이 검증 실패해야 한다`() = runTest {
            val planJson = """[
                {"tool":"tool_a","args":{},"description":"A 실행"},
                {"tool":"unknown_tool","args":{},"description":"없는 도구"},
                {"tool":"tool_b","args":{},"description":"B 실행"}
            ]"""

            every { fixture.callResponseSpec.chatResponse() } returns
                simpleChatResponse(planJson)

            val tools = listOf(
                createMockSpringTool("tool_a"),
                createMockSpringTool("tool_b")
            )
            val command = defaultCommand("A와 B를 실행해줘")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertErrorCode(AgentErrorCode.PLAN_VALIDATION_FAILED)
            result.errorMessage!! shouldContain "unknown_tool"

            // 부분 실행 없이 전체 차단되어야 한다
            coVerify(exactly = 0) {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }
    }

    // =========================================================================
    // 3. 단계 실행 흐름 — 순차 실행 및 합성
    // =========================================================================
    @Nested
    @DisplayName("단계 실행 흐름")
    inner class StepExecutionFlow {

        @Test
        fun `계획 단계가 순차적으로 실행되어야 한다`() = runTest {
            val planJson = """[
                {"tool":"step_1","args":{"id":"1"},"description":"1단계 실행"},
                {"tool":"step_2","args":{"id":"2"},"description":"2단계 실행"}
            ]"""
            val synthesisText = "모든 단계 완료"

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "step_1", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "step-1-result", success = true)

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "step_2", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "step-2-result", success = true)

            val tools = listOf(
                createMockSpringTool("step_1"),
                createMockSpringTool("step_2")
            )
            val command = defaultCommand("두 단계를 순차 실행해줘")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("순차 실행 결과가 성공이어야 한다")

            // 순서가 보장되어야 한다 (step_1 먼저, step_2 나중)
            coVerifyOrder {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "step_1", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "step_2", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }

        @Test
        fun `합성 호출 시 모든 도구 결과가 LLM에 전달되어야 한다`() = runTest {
            val planJson = """[
                {"tool":"fetch_data","args":{},"description":"데이터 조회"},
                {"tool":"analyze","args":{},"description":"분석 수행"}
            ]"""

            // 합성 프롬프트 캡처를 위한 커스텀 requestSpec
            val capturedMessages = mutableListOf<String>()
            val localFixture = AgentTestFixture()
            val localStrategy = PlanExecuteStrategy(
                toolCallOrchestrator = toolCallOrchestrator,
                buildRequestSpec = { _, sysPrompt, msgs, _, _ ->
                    capturedSystemPrompts.add(sysPrompt)
                    // UserMessage 내용을 캡처
                    for (msg in msgs) {
                        if (msg is org.springframework.ai.chat.messages.UserMessage) {
                            capturedMessages.add(msg.text)
                        }
                    }
                    localFixture.requestSpec
                },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> mockk(relaxed = true) },
                systemPromptBuilder = systemPromptBuilder,
                planValidator = planValidator
            )

            every { localFixture.callResponseSpec.chatResponse() } returnsMany
                listOf(
                    simpleChatResponse(planJson),
                    simpleChatResponse("데이터 분석 결과: 정상")
                )

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "fetch_data", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "raw-data-output", success = true)

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "analyze", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "analysis-complete", success = true)

            val tools = listOf(
                createMockSpringTool("fetch_data"),
                createMockSpringTool("analyze")
            )
            val command = defaultCommand("데이터를 조회하고 분석해줘")

            val result = localStrategy.execute(
                command = command,
                activeChatClient = localFixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("합성 결과가 성공이어야 한다")

            // 합성 호출의 UserMessage (두 번째 캡처)에 모든 도구 결과가 포함되어야 한다
            assertTrue(
                capturedMessages.size >= 2,
                "최소 2번의 UserMessage가 캡처되어야 한다 (계획 + 합성): ${capturedMessages.size}"
            )
            val synthesisMessage = capturedMessages.last()
            synthesisMessage shouldContain "raw-data-output"
            synthesisMessage shouldContain "analysis-complete"
            synthesisMessage shouldContain "fetch_data"
            synthesisMessage shouldContain "analyze"
        }
    }

    // =========================================================================
    // 4. 엔드투엔드 해피 패스
    // =========================================================================
    @Nested
    @DisplayName("엔드투엔드 해피 패스")
    inner class EndToEndHappyPath {

        @Test
        fun `사용자 질의부터 최종 합성까지 전체 파이프라인이 성공해야 한다`() = runTest {
            // 1) LLM이 계획 JSON 반환
            val planJson = """[
                {"tool":"jira_get_issue","args":{"key":"JAR-36"},"description":"이슈 조회"},
                {"tool":"jira_get_comments","args":{"key":"JAR-36"},"description":"댓글 조회"}
            ]"""
            // 2) 최종 합성 응답
            val synthesisText = "JAR-36은 진행 중이며 최근 댓글은 '리뷰 완료'입니다."

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

            // 도구 실행 결과 mock
            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "jira_get_issue",
                    toolParams = mapOf("key" to "JAR-36"),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(
                output = """{"key":"JAR-36","status":"In Progress","assignee":"kim"}""",
                success = true
            )

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "jira_get_comments",
                    toolParams = mapOf("key" to "JAR-36"),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(
                output = """[{"author":"lee","body":"리뷰 완료"}]""",
                success = true
            )

            val tools = listOf(
                createMockSpringTool("jira_get_issue", "Jira 이슈 상세 조회"),
                createMockSpringTool("jira_get_comments", "Jira 이슈 댓글 조회")
            )
            val command = defaultCommand("JAR-36 이슈 상태와 최근 댓글을 알려줘")
            val toolsUsed = mutableListOf<String>()

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = toolsUsed,
                maxToolCalls = 10
            )

            // 최종 결과 검증
            result.assertSuccess("엔드투엔드 실행이 성공이어야 한다")
            result.content shouldBe synthesisText

            // 계획 프롬프트가 buildPlanningPrompt로 생성되었는지 확인
            val planningPrompt = capturedSystemPrompts.first()
            planningPrompt shouldContain "플래너"
            planningPrompt shouldContain "jira_get_issue"
            planningPrompt shouldContain "jira_get_comments"

            // 두 도구 모두 순차 실행되었는지 확인
            coVerifyOrder {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "jira_get_issue", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = "jira_get_comments", toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }

            // 합성 호출이 2번째 시스템 프롬프트 호출임을 확인 (총 2회: 계획 + 합성)
            assertEquals(
                2, capturedSystemPrompts.size,
                "LLM 호출은 계획(1) + 합성(1) = 2회여야 한다"
            )
        }
    }

    // =========================================================================
    // 5. 오류 처리
    // =========================================================================
    @Nested
    @DisplayName("오류 처리")
    inner class ErrorHandling {

        @Test
        fun `JSON 파싱 실패 시 빈 계획으로 간주하여 직접 응답으로 폴백해야 한다`() = runTest {
            val invalidJson = "이건 JSON이 아니라 자연어 응답입니다."
            val directResponse = "직접 응답: 안녕하세요."

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(invalidJson), simpleChatResponse(directResponse))

            val command = defaultCommand("안녕하세요")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = listOf(createMockSpringTool("any_tool")),
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("JSON 파싱 실패 시 직접 응답 성공이어야 한다")
            result.content shouldBe directResponse

            // 도구 실행이 호출되지 않아야 한다
            coVerify(exactly = 0) {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }

        @Test
        fun `빈 JSON 배열 계획이면 직접 응답으로 폴백해야 한다`() = runTest {
            val emptyPlan = "응답: []"
            val directResponse = "도구 없이 직접 답변합니다."

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(emptyPlan), simpleChatResponse(directResponse))

            val command = defaultCommand("간단한 질문")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = listOf(createMockSpringTool("tool1")),
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("빈 계획 시 직접 응답 성공이어야 한다")
            result.content shouldBe directResponse
        }

        @Test
        fun `도구 실행 중 예외 발생 시 Error 메시지 포함된 부분 결과로 합성되어야 한다`() =
            runTest {
                val planJson = """[
                    {"tool":"tool_ok","args":{},"description":"성공 도구"},
                    {"tool":"tool_fail","args":{},"description":"실패 도구"},
                    {"tool":"tool_ok2","args":{},"description":"후속 성공 도구"}
                ]"""
                val synthesisText = "부분 결과 기반 합성 응답"

                every { fixture.callResponseSpec.chatResponse() } returnsMany
                    listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

                coEvery {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = "tool_ok", toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                } returns ToolCallResult(output = "ok-result", success = true)

                coEvery {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = "tool_fail", toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                } throws RuntimeException("API 서버 연결 실패")

                coEvery {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = "tool_ok2", toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                } returns ToolCallResult(output = "ok2-result", success = true)

                val tools = listOf(
                    createMockSpringTool("tool_ok"),
                    createMockSpringTool("tool_fail"),
                    createMockSpringTool("tool_ok2")
                )
                val command = defaultCommand("세 도구를 실행해줘")

                val result = strategy.execute(
                    command = command,
                    activeChatClient = fixture.chatClient,
                    systemPrompt = "시스템 프롬프트",
                    tools = tools,
                    conversationHistory = emptyList(),
                    hookContext = defaultHookContext(command.userPrompt),
                    toolsUsed = mutableListOf(),
                    maxToolCalls = 10
                )

                // 도구 실패에도 합성까지 완료되어야 한다
                result.assertSuccess("도구 실패 시에도 부분 결과로 합성 성공해야 한다")

                // 세 도구 모두 실행 시도되어야 한다
                coVerify(exactly = 3) {
                    toolCallOrchestrator.executeDirectToolCall(
                        toolName = any(), toolParams = any(),
                        tools = any(), hookContext = any(), toolsUsed = any()
                    )
                }
            }

        @Test
        fun `불완전한 JSON 계획 시 파싱 실패로 빈 계획 폴백해야 한다`() = runTest {
            // 닫히지 않은 JSON 배열
            val incompleteJson = """[{"tool":"test","args":{},"description":"미완성"""
            val directResponse = "파싱 실패 후 직접 응답"

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(incompleteJson), simpleChatResponse(directResponse))

            val command = defaultCommand("테스트 질문")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = listOf(createMockSpringTool("test")),
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 10
            )

            result.assertSuccess("불완전한 JSON 시 직접 응답으로 폴백 성공해야 한다")
            result.content shouldBe directResponse
        }

        @Test
        fun `maxToolCalls 제한으로 조기 종료 시 실행된 결과만으로 합성해야 한다`() = runTest {
            val planJson = """[
                {"tool":"tool_1","args":{},"description":"1단계"},
                {"tool":"tool_2","args":{},"description":"2단계"},
                {"tool":"tool_3","args":{},"description":"3단계"}
            ]"""
            val synthesisText = "1단계만 실행된 결과 합성"

            every { fixture.callResponseSpec.chatResponse() } returnsMany
                listOf(simpleChatResponse(planJson), simpleChatResponse(synthesisText))

            coEvery {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            } returns ToolCallResult(output = "result", success = true)

            val tools = listOf(
                createMockSpringTool("tool_1"),
                createMockSpringTool("tool_2"),
                createMockSpringTool("tool_3")
            )
            val command = defaultCommand("3단계 실행")

            val result = strategy.execute(
                command = command,
                activeChatClient = fixture.chatClient,
                systemPrompt = "시스템 프롬프트",
                tools = tools,
                conversationHistory = emptyList(),
                hookContext = defaultHookContext(command.userPrompt),
                toolsUsed = mutableListOf(),
                maxToolCalls = 1
            )

            result.assertSuccess("maxToolCalls 제한 시에도 합성 성공해야 한다")

            // maxToolCalls=1이므로 도구는 1번만 실행되어야 한다
            coVerify(exactly = 1) {
                toolCallOrchestrator.executeDirectToolCall(
                    toolName = any(), toolParams = any(),
                    tools = any(), hookContext = any(), toolsUsed = any()
                )
            }
        }
    }
}
