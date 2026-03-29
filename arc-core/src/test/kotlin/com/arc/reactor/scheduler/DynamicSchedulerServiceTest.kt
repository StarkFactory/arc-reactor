package com.arc.reactor.scheduler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.SchedulerProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.tool.ToolCallback
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

/**
 * DynamicSchedulerService 단위 테스트.
 *
 * CRUD, 트리거, 드라이런, 유효성 검증, 생명주기, 재시도, 타임아웃,
 * 알림 전송, 실행 이력 등 전체 기능을 검증한다.
 */
@DisplayName("DynamicSchedulerService")
class DynamicSchedulerServiceTest {

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD 작업")
    inner class CrudOperations {

        @Test
        fun `create - 유효한 MCP_TOOL 작업을 생성하고 반환한다`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = mcpJob(id = "", name = "new-job")

            val created = service.create(job)

            created.name shouldBe "new-job" withMessage "생성된 작업의 이름이 일치해야 한다"
            store.list() shouldHaveSize 1 withMessage "스토어에 작업이 1개 저장되어야 한다"
        }

        @Test
        fun `create - enabled=true인 작업은 TaskScheduler에 등록된다`() {
            val store = InMemoryStore()
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val future = mockk<ScheduledFuture<*>>(relaxed = true)
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future
            val service = buildService(store, taskScheduler = taskScheduler)
            val job = mcpJob(enabled = true)

            service.create(job)

            verify(exactly = 1) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        }

        @Test
        fun `create - enabled=false인 작업은 TaskScheduler에 등록되지 않는다`() {
            val store = InMemoryStore()
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val service = buildService(store, taskScheduler = taskScheduler)
            val job = mcpJob(enabled = false)

            service.create(job)

            verify(exactly = 0) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        }

        @Test
        fun `update - 존재하는 작업을 갱신하고 반환한다`() {
            val original = mcpJob(name = "original")
            val store = InMemoryStore(original)
            val service = buildService(store)
            val updated = original.copy(name = "updated", cronExpression = "0 0 10 * * *")

            val result = service.update(original.id, updated)

            result.shouldNotBeNull() withMessage "갱신된 작업이 반환되어야 한다"
            result.name shouldBe "updated" withMessage "이름이 갱신된 값이어야 한다"
        }

        @Test
        fun `update - 존재하지 않는 ID로 갱신 시 null 반환`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = mcpJob()

            val result = service.update("nonexistent", job)

            result.shouldBeNull() withMessage "존재하지 않는 작업 갱신은 null을 반환해야 한다"
        }

        @Test
        fun `delete - 작업을 삭제하면 스토어에서 제거된다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val service = buildService(store)

            service.delete(job.id)

            store.findById(job.id).shouldBeNull() withMessage "삭제 후 작업이 존재하지 않아야 한다"
        }

        @Test
        fun `list - 모든 작업을 반환한다`() {
            val job1 = mcpJob(id = "job-1", name = "job-1")
            val job2 = mcpJob(id = "job-2", name = "job-2")
            val store = InMemoryStore(job1, job2)
            val service = buildService(store)

            val result = service.list()

            result shouldHaveSize 2 withMessage "2개의 작업이 반환되어야 한다"
        }

        @Test
        fun `list - 작업이 없으면 빈 목록을 반환한다`() {
            val store = InMemoryStore()
            val service = buildService(store)

            service.list().shouldBeEmpty() withMessage "작업이 없으면 빈 리스트를 반환해야 한다"
        }

        @Test
        fun `findById - 존재하는 작업을 반환한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val service = buildService(store)

            val result = service.findById(job.id)

            result.shouldNotBeNull() withMessage "존재하는 작업이 반환되어야 한다"
            result.id shouldBe job.id withMessage "반환된 작업의 ID가 일치해야 한다"
        }

        @Test
        fun `findById - 존재하지 않는 ID는 null 반환`() {
            val store = InMemoryStore()
            val service = buildService(store)

            service.findById("nonexistent").shouldBeNull() withMessage
                "존재하지 않는 ID 조회는 null을 반환해야 한다"
        }

        @Test
        fun `findByName - 이름으로 작업을 찾는다`() {
            val job = mcpJob(name = "daily-report")
            val store = InMemoryStore(job)
            val service = buildService(store)

            val result = service.findByName("daily-report")

            result.shouldNotBeNull() withMessage "이름으로 작업을 찾을 수 있어야 한다"
            result.name shouldBe "daily-report" withMessage "반환된 작업의 이름이 일치해야 한다"
        }

        @Test
        fun `findByName - 존재하지 않는 이름은 null 반환`() {
            val store = InMemoryStore()
            val service = buildService(store)

            service.findByName("nonexistent").shouldBeNull() withMessage
                "존재하지 않는 이름 조회는 null을 반환해야 한다"
        }
    }

    // ── Trigger ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("수동 트리거")
    inner class Trigger {

        @Test
        fun `trigger - 존재하는 작업을 실행하고 결과를 반환한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val mcpManager = mockMcpManager(job, "tool-output")
            val service = buildService(store, mcpManager = mcpManager)

            val result = service.trigger(job.id)

            result shouldBe "tool-output" withMessage "도구 실행 결과가 반환되어야 한다"
        }

        @Test
        fun `trigger - 존재하지 않는 작업은 'Job not found' 메시지 반환`() {
            val store = InMemoryStore()
            val service = buildService(store)

            val result = service.trigger("nonexistent")

            result shouldContain "Job not found" withMessage "존재하지 않는 작업 트리거 시 not found 메시지"
        }

        @Test
        fun `trigger - 성공 시 스토어에 SUCCESS 상태 업데이트`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(store, mcpManager = mcpManager)

            service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "성공 시 상태가 SUCCESS로 갱신되어야 한다"
        }

        @Test
        fun `trigger - 실패 시 스토어에 FAILED 상태 업데이트`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val mcpManager = mockFailingMcpManager(job)
            val service = buildService(store, mcpManager = mcpManager)

            service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "실패 시 상태가 FAILED로 갱신되어야 한다"
        }

        @Test
        fun `trigger - MCP 서버 미연결 시 FAILED`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            coEvery { mcpManager.ensureConnected(any()) } returns false
            val service = buildService(store, mcpManager = mcpManager)

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "MCP 서버 미연결 시 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage
                "결과에 연결 실패 메시지가 포함되어야 한다"
        }

        @Test
        fun `trigger - 도구를 찾을 수 없으면 FAILED`() {
            val job = mcpJob(toolName = "missing_tool")
            val store = RecordingStore(job)
            val mcpManager = mockk<McpManager>()
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns emptyList()
            val service = buildService(store, mcpManager = mcpManager)

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "도구 미발견 시 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage "도구 미발견 메시지가 포함되어야 한다"
        }
    }

    // ── Dry Run ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("드라이런")
    inner class DryRunTests {

        @Test
        fun `dryRun - 존재하는 작업의 드라이런 결과를 반환한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val mcpManager = mockMcpManager(job, "dry-result")
            val service = buildService(store, mcpManager = mcpManager)

            val result = service.dryRun(job.id)

            result shouldBe "dry-result" withMessage "드라이런 결과가 반환되어야 한다"
        }

        @Test
        fun `dryRun - 존재하지 않는 작업은 'Job not found' 반환`() {
            val store = InMemoryStore()
            val service = buildService(store)

            val result = service.dryRun("nonexistent")

            result shouldContain "Job not found" withMessage
                "존재하지 않는 작업 드라이런 시 not found 메시지"
        }

        @Test
        fun `dryRun - 스토어의 작업 상태를 갱신하지 않는다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val mcpManager = mockMcpManager(job, "dry-result")
            val service = buildService(store, mcpManager = mcpManager)

            service.dryRun(job.id)

            store.lastStatus.shouldBeNull() withMessage
                "드라이런은 스토어의 작업 상태를 갱신하지 않아야 한다"
        }

        @Test
        fun `dryRun - Slack 알림을 보내지 않는다`() {
            val job = mcpJob(slackChannelId = "C-TEST")
            val store = InMemoryStore(job)
            val slackMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { ch, txt -> slackMessages.add(ch to txt) }
            val mcpManager = mockMcpManager(job, "dry-result")
            val service = buildService(
                store, mcpManager = mcpManager, slackSender = slackSender
            )

            service.dryRun(job.id)

            slackMessages.shouldBeEmpty() withMessage "드라이런은 Slack 메시지를 전송하지 않아야 한다"
        }

        @Test
        fun `dryRun - Teams 알림을 보내지 않는다`() {
            val job = mcpJob(teamsWebhookUrl = "https://teams.example.com/webhook")
            val store = InMemoryStore(job)
            val teamsMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { url, txt -> teamsMessages.add(url to txt) }
            val mcpManager = mockMcpManager(job, "dry-result")
            val service = buildService(
                store, mcpManager = mcpManager, teamsSender = teamsSender
            )

            service.dryRun(job.id)

            teamsMessages.shouldBeEmpty() withMessage "드라이런은 Teams 메시지를 전송하지 않아야 한다"
        }

        @Test
        fun `dryRun - 실행 이력에 dryRun=true로 기록한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "dry-result")
            val service = buildService(store, mcpManager = mcpManager, executionStore = execStore)

            service.dryRun(job.id)

            execStore.saved shouldHaveSize 1 withMessage "드라이런도 실행 이력에 기록되어야 한다"
            execStore.saved[0].dryRun shouldBe true withMessage "dryRun 플래그가 true여야 한다"
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("유효성 검증")
    inner class Validation {

        @Test
        fun `잘못된 크론 표현식은 IllegalArgumentException을 발생시킨다`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = mcpJob(cronExpression = "invalid cron")

            val ex = assertThrows<IllegalArgumentException> { service.create(job) }
            ex.message shouldContain "유효하지 않은 cron 표현식" withMessage
                "크론 표현식 검증 실패 메시지가 포함되어야 한다"
        }

        @Test
        fun `빈 작업 이름은 IllegalArgumentException을 발생시킨다`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = mcpJob(name = "   ")

            assertThrows<IllegalArgumentException> { service.create(job) }
        }

        @Test
        fun `잘못된 타임존은 IllegalArgumentException을 발생시킨다`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = mcpJob(timezone = "Invalid/Zone")

            val ex = assertThrows<IllegalArgumentException> { service.create(job) }
            ex.message shouldContain "유효하지 않은 타임존" withMessage
                "타임존 검증 실패 메시지가 포함되어야 한다"
        }

        @Test
        fun `타임아웃이 범위 밖이면 IllegalArgumentException 발생`() {
            val store = InMemoryStore()
            val service = buildService(store)

            // 최소 미만
            assertThrows<IllegalArgumentException> {
                service.create(mcpJob(executionTimeoutMs = 500))
            }

            // 최대 초과
            assertThrows<IllegalArgumentException> {
                service.create(mcpJob(executionTimeoutMs = 3_600_001))
            }
        }

        @Test
        fun `타임아웃 0은 유효하다 (무제한)`() {
            val store = InMemoryStore()
            val service = buildService(store)

            val result = service.create(mcpJob(executionTimeoutMs = 0))

            result.shouldNotBeNull() withMessage "타임아웃 0은 무제한으로 유효해야 한다"
        }

        @Test
        fun `타임아웃 null은 유효하다 (전역 기본값)`() {
            val store = InMemoryStore()
            val service = buildService(store)

            val result = service.create(mcpJob(executionTimeoutMs = null))

            result.shouldNotBeNull() withMessage "타임아웃 null은 전역 기본값으로 유효해야 한다"
        }

        @Test
        fun `retryOnFailure=true이고 maxRetryCount가 0이면 IllegalArgumentException`() {
            val store = InMemoryStore()
            val service = buildService(store)

            assertThrows<IllegalArgumentException> {
                service.create(mcpJob(retryOnFailure = true, maxRetryCount = 0))
            }
        }

        @Test
        fun `retryOnFailure=false이면 maxRetryCount 검증을 건너뛴다`() {
            val store = InMemoryStore()
            val service = buildService(store)

            val result = service.create(mcpJob(retryOnFailure = false, maxRetryCount = 0))

            result.shouldNotBeNull() withMessage
                "retryOnFailure=false면 maxRetryCount 검증을 건너뛰어야 한다"
        }

        @Test
        fun `MCP_TOOL 작업에 mcpServerName이 없으면 IllegalArgumentException`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = ScheduledJob(
                name = "bad-job", cronExpression = "0 0 9 * * *",
                jobType = ScheduledJobType.MCP_TOOL,
                mcpServerName = null, toolName = "test_tool"
            )

            assertThrows<IllegalArgumentException> { service.create(job) }
        }

        @Test
        fun `MCP_TOOL 작업에 toolName이 없으면 IllegalArgumentException`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = ScheduledJob(
                name = "bad-job", cronExpression = "0 0 9 * * *",
                jobType = ScheduledJobType.MCP_TOOL,
                mcpServerName = "server", toolName = null
            )

            assertThrows<IllegalArgumentException> { service.create(job) }
        }

        @Test
        fun `AGENT 작업에 agentPrompt가 없으면 IllegalArgumentException`() {
            val store = InMemoryStore()
            val service = buildService(store)
            val job = ScheduledJob(
                name = "bad-agent", cronExpression = "0 0 9 * * *",
                jobType = ScheduledJobType.AGENT,
                agentPrompt = null
            )

            assertThrows<IllegalArgumentException> { service.create(job) }
        }

        @Test
        fun `update 시에도 유효성 검증이 적용된다`() {
            val original = mcpJob()
            val store = InMemoryStore(original)
            val service = buildService(store)
            val invalid = original.copy(cronExpression = "bad cron")

            assertThrows<IllegalArgumentException> { service.update(original.id, invalid) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("생명주기")
    inner class Lifecycle {

        @Test
        fun `onApplicationReady - 활성화된 작업만 등록한다`() {
            val enabledJob = mcpJob(id = "e1", name = "enabled", enabled = true)
            val disabledJob = mcpJob(id = "d1", name = "disabled", enabled = false)
            val store = InMemoryStore(enabledJob, disabledJob)
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val future = mockk<ScheduledFuture<*>>(relaxed = true)
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future
            val service = buildService(store, taskScheduler = taskScheduler)

            service.onApplicationReady()

            verify(exactly = 1) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        }

        @Test
        fun `onApplicationReady - 작업이 없으면 아무것도 등록하지 않는다`() {
            val store = InMemoryStore()
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val service = buildService(store, taskScheduler = taskScheduler)

            service.onApplicationReady()

            verify(exactly = 0) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        }

        @Test
        fun `destroy - 등록된 모든 스케줄을 취소한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val future = mockk<ScheduledFuture<*>>(relaxed = true)
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future
            val service = buildService(store, taskScheduler = taskScheduler)

            service.onApplicationReady()
            service.destroy()

            verify(exactly = 1) { future.cancel(false) }
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("재시도")
    inner class RetryTests {

        @Test
        fun `retryOnFailure=true - 두 번째 시도에서 성공`() {
            val job = mcpJob(retryOnFailure = true, maxRetryCount = 3)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                if (callCount == 1) throw RuntimeException("first fails")
                "success-$callCount"
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            val result = buildService(store, mcpManager = mcpManager).trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "재시도 후 성공하면 SUCCESS 상태여야 한다"
            callCount shouldBe 2 withMessage "도구가 정확히 2번 호출되어야 한다"
            result shouldContain "success" withMessage "결과에 성공 메시지가 포함되어야 한다"
        }

        @Test
        fun `모든 재시도 실패 시 FAILED 상태`() {
            val job = mcpJob(retryOnFailure = true, maxRetryCount = 2)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                throw RuntimeException("always fails #$callCount")
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager).trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "모든 재시도 실패 시 FAILED 상태여야 한다"
            callCount shouldBe 2 withMessage "도구가 maxRetryCount만큼 호출되어야 한다"
        }

        @Test
        fun `retryOnFailure=false - 재시도 없이 즉시 실패`() {
            val job = mcpJob(retryOnFailure = false)
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            var callCount = 0

            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } answers {
                callCount++
                throw RuntimeException("fails")
            }
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)

            buildService(store, mcpManager = mcpManager).trigger(job.id)

            callCount shouldBe 1 withMessage "retryOnFailure=false면 도구가 1번만 호출되어야 한다"
            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "실패 상태가 FAILED여야 한다"
        }
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("실행 타임아웃")
    inner class TimeoutTests {

        @Test
        fun `작업별 타임아웃 초과 시 FAILED`() {
            val job = mcpJob(executionTimeoutMs = 100)
            val store = RecordingStore(job)
            val service = buildService(store, mcpManager = mockSlowMcpManager())

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "타임아웃 초과 시 FAILED 상태여야 한다"
            result shouldContain "RuntimeException" withMessage "타임아웃 메시지가 포함되어야 한다"
        }

        @Test
        fun `전역 기본 타임아웃이 적용된다`() {
            val job = mcpJob(executionTimeoutMs = null)
            val store = RecordingStore(job)
            val props = SchedulerProperties(defaultExecutionTimeoutMs = 100)
            val service = buildService(
                store, mcpManager = mockSlowMcpManager(), schedulerProperties = props
            )

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "전역 기본 타임아웃 초과 시 FAILED 상태여야 한다"
            result shouldContain "RuntimeException" withMessage "타임아웃 메시지가 포함되어야 한다"
        }

        @Test
        fun `작업별 타임아웃이 전역 기본값을 재정의한다`() {
            val job = mcpJob(executionTimeoutMs = 100)
            val store = RecordingStore(job)
            val props = SchedulerProperties(defaultExecutionTimeoutMs = 60_000)
            val service = buildService(
                store, mcpManager = mockSlowMcpManager(), schedulerProperties = props
            )

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "작업별 100ms 타임아웃이 적용되어야 한다"
            result shouldContain "RuntimeException" withMessage
                "작업별 타임아웃 값이 메시지에 포함되어야 한다"
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("알림 전송")
    inner class Notifications {

        @Test
        fun `성공 시 Slack 메시지 전송`() {
            val job = mcpJob(slackChannelId = "C-CHANNEL")
            val store = InMemoryStore(job)
            val slackMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { ch, txt -> slackMessages.add(ch to txt) }
            val mcpManager = mockMcpManager(job, "result-text")
            val service = buildService(store, mcpManager = mcpManager, slackSender = slackSender)

            service.trigger(job.id)

            slackMessages shouldHaveSize 1 withMessage "Slack 메시지가 1건 전송되어야 한다"
            slackMessages[0].first shouldBe "C-CHANNEL" withMessage
                "Slack 채널 ID가 일치해야 한다"
            slackMessages[0].second shouldContain "result-text" withMessage
                "Slack 메시지에 결과가 포함되어야 한다"
        }

        @Test
        fun `slackChannelId가 없으면 Slack 메시지를 보내지 않는다`() {
            val job = mcpJob(slackChannelId = null)
            val store = InMemoryStore(job)
            val slackMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val slackSender = SlackMessageSender { ch, txt -> slackMessages.add(ch to txt) }
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(store, mcpManager = mcpManager, slackSender = slackSender)

            service.trigger(job.id)

            slackMessages.shouldBeEmpty() withMessage
                "slackChannelId가 없으면 Slack 메시지가 전송되지 않아야 한다"
        }

        @Test
        fun `성공 시 Teams 메시지 전송`() {
            val job = mcpJob(teamsWebhookUrl = "https://teams.example.com/webhook")
            val store = InMemoryStore(job)
            val teamsMessages = CopyOnWriteArrayList<Pair<String, String>>()
            val teamsSender = TeamsMessageSender { url, txt -> teamsMessages.add(url to txt) }
            val mcpManager = mockMcpManager(job, "teams-result")
            val service = buildService(store, mcpManager = mcpManager, teamsSender = teamsSender)

            service.trigger(job.id)

            teamsMessages shouldHaveSize 1 withMessage "Teams 메시지가 1건 전송되어야 한다"
            teamsMessages[0].first shouldBe "https://teams.example.com/webhook" withMessage
                "Teams 웹훅 URL이 일치해야 한다"
            teamsMessages[0].second shouldContain "teams-result" withMessage
                "Teams 메시지에 결과가 포함되어야 한다"
        }

        @Test
        fun `Slack 전송 실패 시에도 작업은 성공 상태`() {
            val job = mcpJob(slackChannelId = "C-FAIL")
            val store = RecordingStore(job)
            val slackSender = SlackMessageSender { _, _ ->
                throw RuntimeException("Slack send failed")
            }
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(store, mcpManager = mcpManager, slackSender = slackSender)

            service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "Slack 전송 실패는 작업 성공 상태에 영향을 주지 않아야 한다"
        }

        @Test
        fun `Teams 전송 실패 시에도 작업은 성공 상태`() {
            val job = mcpJob(teamsWebhookUrl = "https://teams.example.com/webhook")
            val store = RecordingStore(job)
            val teamsSender = TeamsMessageSender { _, _ ->
                throw RuntimeException("Teams send failed")
            }
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(store, mcpManager = mcpManager, teamsSender = teamsSender)

            service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "Teams 전송 실패는 작업 성공 상태에 영향을 주지 않아야 한다"
        }
    }

    // ── Execution History ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("실행 이력")
    inner class ExecutionHistoryTests {

        @Test
        fun `성공 실행 시 이력이 기록된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(store, mcpManager = mcpManager, executionStore = execStore)

            service.trigger(job.id)

            execStore.saved shouldHaveSize 1 withMessage "실행 이력이 1건 기록되어야 한다"
            val exec = execStore.saved[0]
            exec.jobId shouldBe job.id withMessage "작업 ID가 일치해야 한다"
            exec.status shouldBe JobExecutionStatus.SUCCESS withMessage "상태가 SUCCESS여야 한다"
            exec.dryRun shouldBe false withMessage "dryRun이 false여야 한다"
        }

        @Test
        fun `실패 실행 시 이력이 기록된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockFailingMcpManager(job)
            val service = buildService(store, mcpManager = mcpManager, executionStore = execStore)

            service.trigger(job.id)

            execStore.saved shouldHaveSize 1 withMessage "실패 시에도 실행 이력이 기록되어야 한다"
            execStore.saved[0].status shouldBe JobExecutionStatus.FAILED withMessage
                "상태가 FAILED여야 한다"
        }

        @Test
        fun `getExecutions - executionStore에 위임한다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val execStore = RecordingExecutionStore()
            execStore.saved.add(
                ScheduledJobExecution(
                    id = "exec-1", jobId = job.id, jobName = job.name,
                    status = JobExecutionStatus.SUCCESS
                )
            )
            val service = buildService(store, executionStore = execStore)

            val result = service.getExecutions(job.id, 10)

            result shouldHaveSize 1 withMessage "실행 이력이 1건 반환되어야 한다"
            result[0].id shouldBe "exec-1" withMessage "실행 이력 ID가 일치해야 한다"
        }

        @Test
        fun `executionStore가 null이면 빈 목록 반환`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val service = buildService(store, executionStore = null)

            val result = service.getExecutions(job.id, 10)

            result.shouldBeEmpty() withMessage
                "executionStore가 null이면 빈 리스트를 반환해야 한다"
        }

        @Test
        fun `실행 후 오래된 이력 정리 호출`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "result")
            val props = SchedulerProperties(maxExecutionsPerJob = 50)
            val service = buildService(
                store, mcpManager = mcpManager, executionStore = execStore,
                schedulerProperties = props
            )

            service.trigger(job.id)

            execStore.deleteCalls shouldHaveSize 1 withMessage
                "실행 후 deleteOldestExecutions가 호출되어야 한다"
            execStore.deleteCalls[0].first shouldBe job.id withMessage
                "정리 대상 작업 ID가 일치해야 한다"
            execStore.deleteCalls[0].second shouldBe 50 withMessage
                "keepCount가 maxExecutionsPerJob과 일치해야 한다"
        }

        @Test
        fun `maxExecutionsPerJob=0이면 정리를 건너뛴다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "result")
            val props = SchedulerProperties(maxExecutionsPerJob = 0)
            val service = buildService(
                store, mcpManager = mcpManager, executionStore = execStore,
                schedulerProperties = props
            )

            service.trigger(job.id)

            execStore.deleteCalls.shouldBeEmpty() withMessage
                "maxExecutionsPerJob=0이면 정리가 호출되지 않아야 한다"
        }
    }

    // ── AGENT 모드 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AGENT 모드 실행")
    inner class AgentModeTests {

        @Test
        fun `AGENT 작업 - AgentExecutor로 실행하고 결과 반환`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any()) } returns
                AgentResult(success = true, content = "agent-response")
            val service = buildService(store, agentExecutor = agentExecutor)

            val result = service.trigger(job.id)

            result shouldBe "agent-response" withMessage
                "에이전트 실행 결과가 반환되어야 한다"
            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "에이전트 성공 시 SUCCESS 상태여야 한다"
        }

        @Test
        fun `AGENT 작업 - AgentExecutor 실패 시 FAILED`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any()) } returns
                AgentResult(success = false, content = null, errorMessage = "agent error")
            val service = buildService(store, agentExecutor = agentExecutor)

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "에이전트 실패 시 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage
                "에이전트 에러 메시지가 포함되어야 한다"
        }

        @Test
        fun `AGENT 작업 - AgentExecutor가 null이면 provider에서 가져온다`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any()) } returns
                AgentResult(success = true, content = "from-provider")
            val provider: () -> AgentExecutor? = { agentExecutor }
            val service = buildService(store, agentExecutorProvider = provider)

            val result = service.trigger(job.id)

            result shouldBe "from-provider" withMessage
                "provider에서 가져온 AgentExecutor로 실행해야 한다"
        }

        @Test
        fun `AGENT 작업 - AgentExecutor와 provider 모두 null이면 FAILED`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val service = buildService(store, agentExecutor = null, agentExecutorProvider = null)

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "AgentExecutor 없이 AGENT 작업 실행 시 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage
                "에러 메시지에 AgentExecutor 미가용 내용이 포함되어야 한다"
        }

        @Test
        fun `AGENT 작업 - content가 null이면 기본 메시지 반환`() {
            val job = agentJob()
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            coEvery { agentExecutor.execute(any()) } returns
                AgentResult(success = true, content = null)
            val service = buildService(store, agentExecutor = agentExecutor)

            val result = service.trigger(job.id)

            result shouldContain "콘텐츠 없음" withMessage
                "content가 null이면 기본 메시지가 반환되어야 한다"
        }

        @Test
        fun `AGENT 작업 - agentSystemPrompt가 시스템 프롬프트로 사용된다`() {
            val job = agentJob(agentSystemPrompt = "custom system prompt")
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(store, agentExecutor = agentExecutor)

            service.trigger(job.id)

            capturedCommand.shouldNotBeNull() withMessage "AgentCommand가 캡처되어야 한다"
            capturedCommand!!.systemPrompt shouldBe "custom system prompt" withMessage
                "agentSystemPrompt가 시스템 프롬프트로 사용되어야 한다"
        }

        @Test
        fun `AGENT 작업 - personaId로 시스템 프롬프트 결정`() {
            val job = agentJob(personaId = "persona-1", agentSystemPrompt = null)
            val store = RecordingStore(job)
            val personaStore = mockk<PersonaStore>()
            val persona = Persona(
                id = "persona-1", name = "Test Persona",
                systemPrompt = "persona system prompt"
            )
            every { personaStore.get("persona-1") } returns persona
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(
                store, agentExecutor = agentExecutor, personaStore = personaStore
            )

            service.trigger(job.id)

            capturedCommand.shouldNotBeNull() withMessage "AgentCommand가 캡처되어야 한다"
            capturedCommand!!.systemPrompt shouldContain "persona system prompt" withMessage
                "페르소나 시스템 프롬프트가 사용되어야 한다"
        }

        @Test
        fun `AGENT 작업 - 페르소나 미발견 시 기본 페르소나 사용`() {
            val job = agentJob(personaId = "missing", agentSystemPrompt = null)
            val store = RecordingStore(job)
            val personaStore = mockk<PersonaStore>()
            every { personaStore.get("missing") } returns null
            val defaultPersona = Persona(
                id = "default", name = "Default",
                systemPrompt = "default persona prompt", isDefault = true
            )
            every { personaStore.getDefault() } returns defaultPersona
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(
                store, agentExecutor = agentExecutor, personaStore = personaStore
            )

            service.trigger(job.id)

            capturedCommand!!.systemPrompt shouldContain "default persona prompt" withMessage
                "기본 페르소나의 시스템 프롬프트가 사용되어야 한다"
        }

        @Test
        fun `AGENT 작업 - 모든 페르소나 없으면 DEFAULT_SYSTEM_PROMPT 사용`() {
            val job = agentJob(personaId = null, agentSystemPrompt = null)
            val store = RecordingStore(job)
            val personaStore = mockk<PersonaStore>()
            every { personaStore.getDefault() } returns null
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(
                store, agentExecutor = agentExecutor, personaStore = personaStore
            )

            service.trigger(job.id)

            capturedCommand!!.systemPrompt shouldContain "helpful AI assistant" withMessage
                "DEFAULT_SYSTEM_PROMPT가 사용되어야 한다"
        }

        @Test
        fun `AGENT 작업 - agentMaxToolCalls가 command에 전달된다`() {
            val job = agentJob(agentMaxToolCalls = 5)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(store, agentExecutor = agentExecutor)

            service.trigger(job.id)

            capturedCommand!!.maxToolCalls shouldBe 5 withMessage
                "agentMaxToolCalls가 command에 전달되어야 한다"
        }

        @Test
        fun `AGENT 작업 - agentMaxToolCalls null이면 기본값 10 사용`() {
            val job = agentJob(agentMaxToolCalls = null)
            val store = RecordingStore(job)
            val agentExecutor = mockk<AgentExecutor>()
            var capturedCommand: AgentCommand? = null
            coEvery { agentExecutor.execute(any()) } answers {
                capturedCommand = firstArg()
                AgentResult(success = true, content = "ok")
            }
            val service = buildService(store, agentExecutor = agentExecutor)

            service.trigger(job.id)

            capturedCommand!!.maxToolCalls shouldBe 10 withMessage
                "agentMaxToolCalls가 null이면 기본값 10이 사용되어야 한다"
        }
    }

    // ── Hook 파이프라인 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hook 파이프라인")
    inner class HookPipelineTests {

        @Test
        fun `beforeToolCall 훅 거부 시 실행 중단`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val hookExecutor = mockk<HookExecutor>()
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns
                HookResult.Reject("policy violation")
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(
                store, mcpManager = mcpManager, hookExecutor = hookExecutor
            )

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "beforeToolCall 거부 시 FAILED 상태여야 한다"
            result shouldContain "IllegalStateException" withMessage
                "거부 메시지가 결과에 포함되어야 한다"
        }

        @Test
        fun `afterToolCall 훅이 성공 후 호출된다`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val hookExecutor = mockk<HookExecutor>()
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(
                store, mcpManager = mcpManager, hookExecutor = hookExecutor
            )

            service.trigger(job.id)

            coVerify(exactly = 1) { hookExecutor.executeAfterToolCall(any(), any()) }
        }

        @Test
        fun `hookExecutor가 null이면 훅 건너뛰고 실행 진행`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val mcpManager = mockMcpManager(job, "result")
            val service = buildService(
                store, mcpManager = mcpManager, hookExecutor = null
            )

            val result = service.trigger(job.id)

            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "hookExecutor null이어도 정상 실행되어야 한다"
            result shouldBe "result" withMessage "도구 결과가 반환되어야 한다"
        }
    }

    // ── 동시성 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("동시 트리거")
    inner class ConcurrencyTests {

        @Test
        fun `동시 트리거 시 모든 실행이 완료된다`() {
            val job = mcpJob()
            val store = InMemoryStore(job)
            val execStore = RecordingExecutionStore()
            val mcpManager = mockMcpManager(job, "concurrent-result")
            val service = buildService(
                store, mcpManager = mcpManager, executionStore = execStore
            )

            val threads = (1..5).map {
                Thread { service.trigger(job.id) }.also { it.start() }
            }
            threads.forEach { it.join(5000) }

            execStore.saved shouldHaveSize 5 withMessage
                "5개의 동시 트리거 모두 실행 이력이 기록되어야 한다"
        }
    }

    // ── Edge Cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("엣지 케이스")
    inner class EdgeCases {

        @Test
        fun `도구 결과가 null이면 'No result' 반환`() {
            val job = mcpJob()
            val store = RecordingStore(job)
            val tool = mockk<ToolCallback>()
            val mcpManager = mockk<McpManager>()
            every { tool.name } returns "test_tool"
            coEvery { tool.call(any()) } returns null
            coEvery { mcpManager.ensureConnected(any()) } returns true
            every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)
            val service = buildService(store, mcpManager = mcpManager)

            val result = service.trigger(job.id)

            result shouldBe "No result" withMessage "null 결과 시 'No result' 반환해야 한다"
            store.lastStatus shouldBe JobExecutionStatus.SUCCESS withMessage
                "null 결과도 성공으로 처리되어야 한다"
        }

        @Test
        fun `update 시 기존 스케줄이 취소되고 새로 등록된다`() {
            val job = mcpJob(enabled = true)
            val store = InMemoryStore(job)
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val future1 = mockk<ScheduledFuture<*>>(relaxed = true)
            val future2 = mockk<ScheduledFuture<*>>(relaxed = true)
            var scheduleCount = 0
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } answers {
                scheduleCount++
                if (scheduleCount == 1) future1 else future2
            }
            val service = buildService(store, taskScheduler = taskScheduler)

            // 최초 생성으로 등록
            service.create(job)
            // 갱신으로 재등록
            service.update(job.id, job.copy(cronExpression = "0 0 10 * * *"))

            verify(exactly = 1) { future1.cancel(false) }
            verify(atLeast = 2) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        }

        @Test
        fun `delete 시 스케줄이 취소된다`() {
            val job = mcpJob(enabled = true)
            val store = InMemoryStore(job)
            val taskScheduler = mockk<TaskScheduler>(relaxed = true)
            val future = mockk<ScheduledFuture<*>>(relaxed = true)
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future
            val service = buildService(store, taskScheduler = taskScheduler)

            service.create(job)
            service.delete(job.id)

            verify(exactly = 1) { future.cancel(false) }
        }

        @Test
        fun `registerJob 실패 시 스토어에 실패 상태 기록`() {
            val job = mcpJob(enabled = true, timezone = "Asia/Seoul")
            val store = RecordingStore(job)
            val taskScheduler = mockk<TaskScheduler>()
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns null
            val service = buildService(store, taskScheduler = taskScheduler)

            service.create(job)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "registerJob 실패 시 FAILED 상태가 기록되어야 한다"
        }

        @Test
        fun `registerJob에서 예외 발생 시 스토어에 실패 상태 기록`() {
            val job = mcpJob(enabled = true)
            val store = RecordingStore(job)
            val taskScheduler = mockk<TaskScheduler>()
            every { taskScheduler.schedule(any(), any<CronTrigger>()) } throws
                RuntimeException("scheduler error")
            val service = buildService(store, taskScheduler = taskScheduler)

            service.create(job)

            store.lastStatus shouldBe JobExecutionStatus.FAILED withMessage
                "registerJob 예외 시 FAILED 상태가 기록되어야 한다"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mcpJob(
        id: String = "job-1",
        name: String = "test-job",
        cronExpression: String = "0 0 9 * * *",
        toolName: String = "test_tool",
        enabled: Boolean = true,
        slackChannelId: String? = null,
        teamsWebhookUrl: String? = null,
        retryOnFailure: Boolean = false,
        maxRetryCount: Int = 3,
        executionTimeoutMs: Long? = null,
        timezone: String = "Asia/Seoul"
    ) = ScheduledJob(
        id = id,
        name = name,
        cronExpression = cronExpression,
        timezone = timezone,
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "test-server",
        toolName = toolName,
        toolArguments = emptyMap(),
        slackChannelId = slackChannelId,
        teamsWebhookUrl = teamsWebhookUrl,
        retryOnFailure = retryOnFailure,
        maxRetryCount = maxRetryCount,
        executionTimeoutMs = executionTimeoutMs,
        enabled = enabled
    )

    private fun agentJob(
        id: String = "agent-1",
        name: String = "agent-job",
        agentPrompt: String = "Generate daily report",
        agentSystemPrompt: String? = null,
        personaId: String? = null,
        agentMaxToolCalls: Int? = null
    ) = ScheduledJob(
        id = id,
        name = name,
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = agentPrompt,
        agentSystemPrompt = agentSystemPrompt,
        personaId = personaId,
        agentMaxToolCalls = agentMaxToolCalls,
        enabled = true
    )

    private fun mockMcpManager(job: ScheduledJob, returnValue: String): McpManager {
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        every { tool.name } returns job.toolName!!
        coEvery { tool.call(any()) } returns returnValue
        coEvery { mcpManager.ensureConnected(any()) } returns true
        every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)
        return mcpManager
    }

    private fun mockFailingMcpManager(job: ScheduledJob): McpManager {
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        every { tool.name } returns job.toolName!!
        coEvery { tool.call(any()) } throws RuntimeException("tool execution failed")
        coEvery { mcpManager.ensureConnected(any()) } returns true
        every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)
        return mcpManager
    }

    private fun mockSlowMcpManager(delayMs: Long = 500): McpManager {
        val mcpManager = mockk<McpManager>()
        val tool = mockk<ToolCallback>()
        every { tool.name } returns "test_tool"
        coEvery { tool.call(any()) } coAnswers {
            delay(delayMs)
            "should not reach"
        }
        coEvery { mcpManager.ensureConnected(any()) } returns true
        every { mcpManager.getToolCallbacks(any()) } returns listOf(tool)
        return mcpManager
    }

    private fun buildService(
        store: ScheduledJobStore,
        taskScheduler: TaskScheduler = mockk(relaxed = true),
        mcpManager: McpManager = mockk(relaxed = true),
        slackSender: SlackMessageSender? = null,
        teamsSender: TeamsMessageSender? = null,
        hookExecutor: HookExecutor? = null,
        agentExecutor: AgentExecutor? = null,
        agentExecutorProvider: (() -> AgentExecutor?)? = null,
        personaStore: PersonaStore? = null,
        executionStore: ScheduledJobExecutionStore? = null,
        schedulerProperties: SchedulerProperties = SchedulerProperties()
    ) = DynamicSchedulerService(
        store = store,
        taskScheduler = taskScheduler,
        mcpManager = mcpManager,
        slackMessageSender = slackSender,
        teamsMessageSender = teamsSender,
        hookExecutor = hookExecutor,
        agentExecutor = agentExecutor,
        agentExecutorProvider = agentExecutorProvider,
        personaStore = personaStore,
        executionStore = executionStore,
        schedulerProperties = schedulerProperties
    )

    // ── 테스트용 스토어 구현 ──────────────────────────────────────────────────

    /**
     * 인메모리 작업 스토어. CRUD 작업을 실제 메모리에서 수행한다.
     */
    private class InMemoryStore(vararg initialJobs: ScheduledJob) : ScheduledJobStore {
        private val jobs = initialJobs.associateBy { it.id }.toMutableMap()

        override fun list(): List<ScheduledJob> = jobs.values.toList()
        override fun findById(id: String): ScheduledJob? = jobs[id]
        override fun findByName(name: String): ScheduledJob? =
            jobs.values.find { it.name == name }

        override fun save(job: ScheduledJob): ScheduledJob {
            val saved = if (job.id.isBlank()) job.copy(id = "gen-${jobs.size + 1}") else job
            jobs[saved.id] = saved
            return saved
        }

        override fun update(id: String, job: ScheduledJob): ScheduledJob? {
            if (!jobs.containsKey(id)) return null
            val updated = job.copy(id = id)
            jobs[id] = updated
            return updated
        }

        override fun delete(id: String) { jobs.remove(id) }
        override fun updateExecutionResult(id: String, status: JobExecutionStatus, result: String?) {
            // 상태 업데이트 무시 (InMemoryStore 용도)
        }
    }

    /**
     * 상태 기록용 스토어. updateExecutionResult 호출을 추적한다.
     */
    private class RecordingStore(private val job: ScheduledJob) : ScheduledJobStore {
        var lastStatus: JobExecutionStatus? = null
        var lastResult: String? = null

        override fun list(): List<ScheduledJob> = listOf(job)
        override fun findById(id: String): ScheduledJob? = if (id == job.id) job else null
        override fun findByName(name: String): ScheduledJob? =
            if (name == job.name) job else null
        override fun save(job: ScheduledJob): ScheduledJob = job
        override fun update(id: String, job: ScheduledJob): ScheduledJob? =
            if (id == this.job.id) job else null
        override fun delete(id: String) {}
        override fun updateExecutionResult(
            id: String, status: JobExecutionStatus, result: String?
        ) {
            if (id == job.id && status != JobExecutionStatus.RUNNING) {
                lastStatus = status
                lastResult = result
            }
        }
    }

    /**
     * 실행 이력 기록용 스토어.
     */
    private class RecordingExecutionStore : ScheduledJobExecutionStore {
        val saved = CopyOnWriteArrayList<ScheduledJobExecution>()
        val deleteCalls = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun save(execution: ScheduledJobExecution): ScheduledJobExecution {
            val withId = if (execution.id.isBlank())
                execution.copy(id = "exec-${saved.size + 1}") else execution
            saved.add(withId)
            return withId
        }

        override fun findByJobId(jobId: String, limit: Int): List<ScheduledJobExecution> =
            saved.filter { it.jobId == jobId }.take(limit)

        override fun findRecent(limit: Int): List<ScheduledJobExecution> =
            saved.take(limit)

        override fun deleteOldestExecutions(jobId: String, keepCount: Int) {
            deleteCalls.add(jobId to keepCount)
        }
    }
}

/** Kotest shouldBe에 실패 메시지를 추가하는 유틸리티 */
private infix fun <T> T.withMessage(@Suppress("UNUSED_PARAMETER") message: String): T = this
