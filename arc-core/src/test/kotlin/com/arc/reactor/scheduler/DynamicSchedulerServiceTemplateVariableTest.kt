package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class DynamicSchedulerServiceTemplateVariableTest {

    @Nested
    inner class AgentPromptTemplateResolution {

        @Test
        fun `AGENT 작업이 resolves date template variable in agentPrompt`() {
            val job = agentJob(agentPrompt = "{{date}} 브리핑해줘")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            val expectedDate = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
            assertEquals("$expectedDate 브리핑해줘", commandSlot.captured.userPrompt,
                "{{date}} should be replaced with current date in yyyy-MM-dd format")
        }

        @Test
        fun `AGENT 작업이 resolves datetime template variable in agentPrompt`() {
            val job = agentJob(agentPrompt = "현재 시각은 {{datetime}}입니다")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val resolved = commandSlot.captured.userPrompt
            assertTrue(resolved.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""
                .let { "현재 시각은 ${it}입니다" })),
                "{{datetime}} should be replaced with yyyy-MM-dd HH:mm:ss format, got: $resolved")
        }

        @Test
        fun `AGENT 작업이 resolves day_of_week template variable in agentPrompt`() {
            val job = agentJob(agentPrompt = "오늘은 {{day_of_week}}")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            val expectedDay = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            assertEquals("오늘은 $expectedDay", commandSlot.captured.userPrompt,
                "{{day_of_week}} should be replaced with English day name")
        }

        @Test
        fun `AGENT 작업이 resolves job_name and job_id template variables`() {
            val job = agentJob(agentPrompt = "잡 {{job_name}} ({{job_id}}) 실행 결과")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            assertEquals("잡 morning-briefing (agent-job-1) 실행 결과",
                commandSlot.captured.userPrompt,
                "{{job_name}} and {{job_id}} should be replaced with job metadata")
        }

        @Test
        fun `AGENT 작업이 resolves multiple template variables in a single prompt`() {
            val job = agentJob(agentPrompt = "{{date}} {{day_of_week}} {{job_name}} 브리핑")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val resolved = commandSlot.captured.userPrompt
            assertTrue(!resolved.contains("{{"),
                "All template variables should be resolved, got: $resolved")
            assertTrue(resolved.contains("morning-briefing"),
                "{{job_name}} should be resolved in the prompt")
        }

        @Test
        fun `AGENT 작업이 passes through prompt without template variables unchanged`() {
            val job = agentJob(agentPrompt = "일반 프롬프트입니다")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            assertEquals("일반 프롬프트입니다", commandSlot.captured.userPrompt,
                "Prompt without template variables should remain unchanged")
        }

        @Test
        fun `AGENT 작업이 resolves date using job timezone instead of default Seoul`() {
            val nyTimezone = "America/New_York"
            val job = ScheduledJob(
                id = "tz-job-1",
                name = "ny-briefing",
                cronExpression = "0 0 22 * * *",
                jobType = ScheduledJobType.AGENT,
                agentPrompt = "Report for {{date}} at {{time}}",
                timezone = nyTimezone,
                enabled = true
            )
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            val commandSlot = slot<AgentCommand>()
            coEvery { agentExecutor.execute(capture(commandSlot)) } returns AgentResult.success("ok")

            val service = buildService(store, agentExecutor = agentExecutor)
            service.trigger(job.id)

            val nyNow = LocalDateTime.now(ZoneId.of(nyTimezone))
            val expectedDate = nyNow.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val resolved = commandSlot.captured.userPrompt
            assertTrue(resolved.startsWith("Report for $expectedDate"),
                "{{date}} should resolve using $nyTimezone (expected $expectedDate), got: $resolved")
            assertTrue(!resolved.contains("{{"),
                "All template variables should be resolved, got: $resolved")
        }
    }

    @Nested
    inner class McpToolArgumentsTemplateResolution {

        @Test
        fun `MCP_TOOL 작업이 resolves template variables in string tool arguments`() {
            val job = mcpToolJob(toolArguments = mapOf(
                "query" to "status:open created:{{date}}",
                "limit" to 10
            ))
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            val tool = mockk<ToolCallback>()
            val argsSlot = slot<Map<String, Any?>>()

            every { tool.name } returns "search_issues"
            coEvery { tool.call(capture(argsSlot)) } returns """{"results": []}"""
            coEvery { mcpManager.ensureConnected("atlassian") } returns true
            every { mcpManager.getToolCallbacks("atlassian") } returns listOf(tool)

            val service = buildService(store, mcpManager = mcpManager)
            service.trigger(job.id)

            val resolvedQuery = argsSlot.captured["query"] as String
            val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            val expectedDate = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
            assertEquals("status:open created:$expectedDate", resolvedQuery,
                "{{date}} in tool argument should be resolved")
            assertEquals(10, argsSlot.captured["limit"],
                "Non-string arguments should remain unchanged")
        }

        @Test
        fun `MCP_TOOL 작업이 resolves job_name in tool arguments`() {
            val job = mcpToolJob(toolArguments = mapOf(
                "title" to "Report from {{job_name}}"
            ))
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            val tool = mockk<ToolCallback>()
            val argsSlot = slot<Map<String, Any?>>()

            every { tool.name } returns "search_issues"
            coEvery { tool.call(capture(argsSlot)) } returns "ok"
            coEvery { mcpManager.ensureConnected("atlassian") } returns true
            every { mcpManager.getToolCallbacks("atlassian") } returns listOf(tool)

            val service = buildService(store, mcpManager = mcpManager)
            service.trigger(job.id)

            assertEquals("Report from daily-search", argsSlot.captured["title"],
                "{{job_name}} in tool argument should be resolved to job name")
        }

        @Test
        fun `MCP_TOOL 작업이 resolves time template variable in tool arguments`() {
            val job = mcpToolJob(toolArguments = mapOf(
                "message" to "Executed at {{time}}"
            ))
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            val tool = mockk<ToolCallback>()
            val argsSlot = slot<Map<String, Any?>>()

            every { tool.name } returns "search_issues"
            coEvery { tool.call(capture(argsSlot)) } returns "ok"
            coEvery { mcpManager.ensureConnected("atlassian") } returns true
            every { mcpManager.getToolCallbacks("atlassian") } returns listOf(tool)

            val service = buildService(store, mcpManager = mcpManager)
            service.trigger(job.id)

            val resolvedMessage = argsSlot.captured["message"] as String
            assertTrue(resolvedMessage.matches(Regex("Executed at \\d{2}:\\d{2}:\\d{2}")),
                "{{time}} should be replaced with HH:mm:ss format, got: $resolvedMessage")
        }
    }

    // -- 헬퍼 ---------------------------------------------------------------

    private fun agentJob(
        agentPrompt: String? = "오늘 아침 브리핑해줘"
    ) = ScheduledJob(
        id = "agent-job-1",
        name = "morning-briefing",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = agentPrompt,
        enabled = true
    )

    private fun mcpToolJob(
        toolArguments: Map<String, Any> = emptyMap()
    ) = ScheduledJob(
        id = "mcp-job-1",
        name = "daily-search",
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "atlassian",
        toolName = "search_issues",
        toolArguments = toolArguments,
        enabled = true
    )

    private fun buildService(
        store: ScheduledJobStore,
        mcpManager: McpManager = mockk(relaxed = true),
        agentExecutor: AgentExecutor? = null
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = mockk(relaxed = true),
        mcpManager = mcpManager,
        agentExecutor = agentExecutor
    )

    private class RecordingStore(private val job: ScheduledJob) : ScheduledJobStore {
        var lastStatus: JobExecutionStatus? = null
        var lastResult: String? = null

        override fun list(): List<ScheduledJob> = listOf(job)
        override fun findById(id: String): ScheduledJob? = if (id == job.id) job else null
        override fun findByName(name: String): ScheduledJob? = if (name == job.name) job else null
        override fun save(job: ScheduledJob): ScheduledJob = job
        override fun update(id: String, job: ScheduledJob): ScheduledJob? =
            if (id == this.job.id) job else null
        override fun delete(id: String) {}
        override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
            if (id == job.id) {
                lastStatus = status
                lastResult = result
            }
        }
    }
}
