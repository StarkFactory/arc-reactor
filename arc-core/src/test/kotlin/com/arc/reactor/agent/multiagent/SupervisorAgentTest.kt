package com.arc.reactor.agent.multiagent

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultSupervisorAgent 단위 테스트.
 *
 * 단일 위임, 폴백, 복수 위임, 메타데이터 전파를 검증한다.
 */
class SupervisorAgentTest {

    private lateinit var agentExecutor: AgentExecutor
    private lateinit var registry: DefaultAgentRegistry
    private lateinit var supervisor: DefaultSupervisorAgent

    private val jiraSpec = AgentSpec(
        id = "jira-agent",
        name = "Jira 전문 에이전트",
        description = "Jira 이슈 조회, 생성, 업데이트를 담당한다",
        toolNames = listOf("jira_search", "jira_create_issue"),
        keywords = listOf("jira", "이슈", "티켓")
    )

    private val confluenceSpec = AgentSpec(
        id = "confluence-agent",
        name = "Confluence 전문 에이전트",
        description = "Confluence 문서 검색 및 작성을 담당한다",
        toolNames = listOf("confluence_search"),
        keywords = listOf("confluence", "문서", "위키"),
        systemPromptOverride = "너는 Confluence 문서 전문가야."
    )

    private val analysisSpec = AgentSpec(
        id = "analysis-agent",
        name = "분석 전문 에이전트",
        description = "데이터 분석 및 리포트 생성을 담당한다",
        toolNames = listOf("run_query"),
        keywords = listOf("분석", "리포트"),
        mode = AgentMode.PLAN_EXECUTE
    )

    @BeforeEach
    fun setUp() {
        agentExecutor = mockk()
        registry = DefaultAgentRegistry()
        supervisor = DefaultSupervisorAgent(
            agentExecutor = agentExecutor,
            agentRegistry = registry
        )
    }

    @Nested
    inner class SingleDelegation {

        @Test
        fun `매칭 에이전트에 위임하여 결과를 반환해야 한다`() = runTest {
            registry.register(jiraSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("JIRA-123 이슈 조회 완료")

            val command = AgentCommand(
                systemPrompt = "기본 프롬프트",
                userPrompt = "jira 이슈 목록 보여줘"
            )
            val result = supervisor.delegate(command)

            assertTrue(result.success, "위임 결과가 성공이어야 한다")
            assertEquals(
                "JIRA-123 이슈 조회 완료", result.content,
                "위임된 에이전트의 응답이 반환되어야 한다"
            )
        }

        @Test
        fun `위임 시 에이전트 ID를 메타데이터에 포함해야 한다`() = runTest {
            registry.register(jiraSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 티켓 확인"
            )
            supervisor.delegate(command)

            val captured = commandSlot.captured
            assertEquals(
                "jira-agent",
                captured.metadata["delegatedAgentId"],
                "메타데이터에 위임 에이전트 ID가 포함되어야 한다"
            )
            assertEquals(
                "Jira 전문 에이전트",
                captured.metadata["delegatedAgentName"],
                "메타데이터에 위임 에이전트 이름이 포함되어야 한다"
            )
        }

        @Test
        fun `systemPromptOverride가 있으면 시스템 프롬프트를 교체해야 한다`() = runTest {
            registry.register(confluenceSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("문서 검색 완료")

            val command = AgentCommand(
                systemPrompt = "기본 프롬프트",
                userPrompt = "confluence 문서 찾아줘"
            )
            supervisor.delegate(command)

            assertEquals(
                "너는 Confluence 문서 전문가야.",
                commandSlot.captured.systemPrompt,
                "시스템 프롬프트가 오버라이드되어야 한다"
            )
        }

        @Test
        fun `systemPromptOverride가 없으면 원본 프롬프트를 유지해야 한다`() = runTest {
            registry.register(jiraSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val command = AgentCommand(
                systemPrompt = "기본 프롬프트",
                userPrompt = "jira 이슈 조회"
            )
            supervisor.delegate(command)

            assertEquals(
                "기본 프롬프트",
                commandSlot.captured.systemPrompt,
                "오버라이드가 없으면 원본 프롬프트를 유지해야 한다"
            )
        }

        @Test
        fun `위임 시 에이전트의 실행 모드를 적용해야 한다`() = runTest {
            registry.register(analysisSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("분석 완료")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "데이터 분석 리포트 만들어줘"
            )
            supervisor.delegate(command)

            assertEquals(
                AgentMode.PLAN_EXECUTE,
                commandSlot.captured.mode,
                "에이전트 사양의 실행 모드가 적용되어야 한다"
            )
        }

        @Test
        fun `위임 시 allowedToolNames를 메타데이터에 포함해야 한다`() = runTest {
            registry.register(jiraSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈 보여줘"
            )
            supervisor.delegate(command)

            @Suppress("UNCHECKED_CAST")
            val allowedTools = commandSlot.captured.metadata["allowedToolNames"]
                as? List<String>
            assertNotNull(allowedTools, "allowedToolNames가 메타데이터에 있어야 한다")
            assertTrue(
                allowedTools!!.contains("jira_search"),
                "허용 도구 목록에 jira_search가 포함되어야 한다"
            )
        }
    }

    @Nested
    inner class FallbackExecution {

        @Test
        fun `매칭 에이전트가 없으면 기본 실행으로 폴백해야 한다`() = runTest {
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("기본 실행 결과")

            val command = AgentCommand(
                systemPrompt = "기본 프롬프트",
                userPrompt = "날씨 알려줘"
            )
            val result = supervisor.delegate(command)

            assertTrue(result.success, "폴백 실행이 성공이어야 한다")
            assertEquals(
                "기본 실행 결과", result.content,
                "기본 AgentExecutor의 결과가 반환되어야 한다"
            )
            // 폴백 시 메타데이터에 delegatedAgentId가 없어야 한다
            assertFalse(
                commandSlot.captured.metadata.containsKey("delegatedAgentId"),
                "폴백 시 위임 메타데이터가 없어야 한다"
            )
        }

        @Test
        fun `레지스트리가 비어있으면 기본 실행으로 폴백해야 한다`() = runTest {
            coEvery { agentExecutor.execute(any()) } returns
                AgentResult.success("폴백 결과")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "아무 질문이나"
            )
            val result = supervisor.delegate(command)

            assertTrue(result.success, "빈 레지스트리에서도 성공해야 한다")
            coVerify(exactly = 1) { agentExecutor.execute(any()) }
        }
    }

    @Nested
    inner class MultiAgentDelegation {

        @Test
        fun `복수 에이전트 매칭 시 순차 실행하여 결과를 병합해야 한다`() = runTest {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)

            var callCount = 0
            coEvery { agentExecutor.execute(any()) } answers {
                callCount++
                when {
                    firstArg<AgentCommand>().metadata["delegatedAgentId"] == "jira-agent" ->
                        AgentResult.success("Jira 결과", toolsUsed = listOf("jira_search"))
                    else ->
                        AgentResult.success(
                            "Confluence 결과",
                            toolsUsed = listOf("confluence_search")
                        )
                }
            }

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈를 confluence 문서로 정리해줘"
            )
            val result = supervisor.delegate(command)

            assertTrue(result.success, "복수 위임 결과가 성공이어야 한다")
            assertTrue(
                result.content?.contains("Jira") == true,
                "병합 결과에 Jira 결과가 포함되어야 한다"
            )
            assertTrue(
                result.content?.contains("Confluence") == true,
                "병합 결과에 Confluence 결과가 포함되어야 한다"
            )
            assertTrue(
                result.toolsUsed.containsAll(
                    listOf("jira_search", "confluence_search")
                ),
                "사용된 도구가 모두 포함되어야 한다"
            )
        }

        @Test
        fun `복수 위임 중 일부 실패해도 성공 결과가 있으면 성공해야 한다`() = runTest {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)

            coEvery { agentExecutor.execute(any()) } answers {
                val agentId = firstArg<AgentCommand>().metadata["delegatedAgentId"]
                if (agentId == "jira-agent") {
                    AgentResult.success("Jira 결과")
                } else {
                    AgentResult.failure("Confluence 에러")
                }
            }

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈를 confluence 문서로 정리해줘"
            )
            val result = supervisor.delegate(command)

            assertTrue(result.success, "일부 실패해도 성공 결과가 있으면 성공이어야 한다")
            @Suppress("UNCHECKED_CAST")
            val meta = result.metadata
            assertEquals(1, meta["successCount"], "성공 카운트가 1이어야 한다")
            assertEquals(2, meta["totalCount"], "전체 카운트가 2이어야 한다")
        }

        @Test
        fun `모든 위임이 실패하면 실패 결과를 반환해야 한다`() = runTest {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)

            coEvery { agentExecutor.execute(any()) } returns
                AgentResult.failure("서비스 에러")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈를 confluence 문서로 정리해줘"
            )
            val result = supervisor.delegate(command)

            assertFalse(result.success, "모든 위임 실패 시 실패여야 한다")
        }

        @Test
        fun `maxDelegations를 초과하면 제한된 수의 에이전트만 실행해야 한다`() = runTest {
            registry.register(jiraSpec)
            registry.register(confluenceSpec)
            registry.register(analysisSpec)

            val limitedSupervisor = DefaultSupervisorAgent(
                agentExecutor = agentExecutor,
                agentRegistry = registry,
                maxDelegations = 1
            )

            coEvery { agentExecutor.execute(any()) } returns
                AgentResult.success("결과")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈 분석해서 confluence 문서로 정리"
            )
            limitedSupervisor.delegate(command)

            // maxDelegations=1이면 단일 위임으로 처리
            coVerify(exactly = 1) { agentExecutor.execute(any()) }
        }
    }

    @Nested
    inner class MetadataPropagation {

        @Test
        fun `원본 명령의 메타데이터를 위임 명령에 보존해야 한다`() = runTest {
            registry.register(jiraSpec)
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns
                AgentResult.success("done")

            val command = AgentCommand(
                systemPrompt = "prompt",
                userPrompt = "jira 이슈 확인해줘",
                userId = "user-42",
                metadata = mapOf("tenantId" to "acme-corp", "channel" to "slack")
            )
            supervisor.delegate(command)

            val captured = commandSlot.captured
            assertEquals("user-42", captured.userId, "userId가 보존되어야 한다")
            assertEquals(
                "acme-corp", captured.metadata["tenantId"],
                "tenantId가 보존되어야 한다"
            )
            assertEquals(
                "slack", captured.metadata["channel"],
                "channel이 보존되어야 한다"
            )
        }
    }
}
