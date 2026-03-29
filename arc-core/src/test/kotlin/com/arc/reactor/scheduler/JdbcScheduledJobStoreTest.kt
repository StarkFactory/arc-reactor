package com.arc.reactor.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.time.Instant

/** 가독성용 실패 메시지 헬퍼 — 반환값 그대로 전달 */
private infix fun <T> T.withMessage(@Suppress("UNUSED_PARAMETER") message: String): T = this

/**
 * [JdbcScheduledJobStore] 단위 테스트.
 *
 * H2 인메모리 데이터베이스를 사용하여 실제 JDBC 동작을 검증한다.
 * - CRUD 전 동작 (list, findById, findByName, save, update, delete)
 * - updateExecutionResult: last_status, last_run_at, 결과 잘림(truncation)
 * - 태그 직렬화/역직렬화 (쉼표 구분)
 * - jobType 기본값(MCP_TOOL) 및 AGENT 모드 저장
 * - 빈 tool_arguments 처리
 */
class JdbcScheduledJobStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcScheduledJobStore

    @BeforeEach
    fun setUp() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V11 기본 스키마 + V26~V35 ALTER 문 통합 (H2 호환 단일 CREATE)
        jdbcTemplate.execute(
            """
            CREATE TABLE scheduled_jobs (
                id                    VARCHAR(36)   PRIMARY KEY,
                name                  VARCHAR(200)  NOT NULL,
                description           TEXT,
                cron_expression       VARCHAR(100)  NOT NULL,
                timezone              VARCHAR(50)   NOT NULL DEFAULT 'Asia/Seoul',
                job_type              VARCHAR(20)   NOT NULL DEFAULT 'MCP_TOOL',
                mcp_server_name       VARCHAR(100),
                tool_name             VARCHAR(200),
                tool_arguments        TEXT          DEFAULT '{}',
                agent_prompt          TEXT,
                persona_id            VARCHAR(100),
                agent_system_prompt   TEXT,
                agent_model           VARCHAR(100),
                agent_max_tool_calls  INTEGER,
                tags                  VARCHAR(1000),
                slack_channel_id      VARCHAR(100),
                teams_webhook_url     VARCHAR(500),
                retry_on_failure      BOOLEAN       NOT NULL DEFAULT FALSE,
                max_retry_count       INTEGER       NOT NULL DEFAULT 3,
                execution_timeout_ms  BIGINT,
                enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
                last_run_at           TIMESTAMP,
                last_status           VARCHAR(20),
                last_result           TEXT,
                created_at            TIMESTAMP     NOT NULL,
                updated_at            TIMESTAMP     NOT NULL
            )
            """.trimIndent()
        )

        store = JdbcScheduledJobStore(jdbcTemplate)
    }

    /** 테스트 픽스처: 최소 필드만 채운 MCP_TOOL 작업 */
    private fun mcpJob(
        name: String = "test-job",
        enabled: Boolean = true,
        tags: Set<String> = emptySet()
    ) = ScheduledJob(
        name = name,
        cronExpression = "0 * * * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "my-server",
        toolName = "my-tool",
        toolArguments = mapOf("key" to "value"),
        enabled = enabled,
        tags = tags
    )

    /** 테스트 픽스처: AGENT 모드 작업 */
    private fun agentJob(name: String = "agent-job") = ScheduledJob(
        name = name,
        cronExpression = "0 0 * * * *",
        jobType = ScheduledJobType.AGENT,
        agentPrompt = "아침 브리핑을 작성해라",
        personaId = "briefing-persona",
        agentModel = "gemini-2.5-flash",
        agentMaxToolCalls = 5,
        slackChannelId = "C12345678",
        teamsWebhookUrl = "https://teams.webhook.url/hook",
        retryOnFailure = true,
        maxRetryCount = 2,
        executionTimeoutMs = 30_000L
    )

    // ──────────────────────────────────────────────────────────────────────
    // list
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class List작업 {

        @Test
        fun `초기 상태에서 빈 목록을 반환해야 한다`() {
            store.list().shouldBeEmpty() withMessage "저장된 작업이 없으면 빈 목록이어야 한다"
        }

        @Test
        fun `저장된 모든 작업을 반환해야 한다`() {
            store.save(mcpJob("a"))
            store.save(mcpJob("b"))

            store.list() shouldHaveSize 2
        }

        @Test
        fun `created_at 오름차순으로 반환해야 한다`() {
            val first = store.save(mcpJob("first"))
            Thread.sleep(10) // H2 타임스탬프 해상도 확보
            store.save(mcpJob("second"))

            val list = store.list()
            list[0].id shouldBe first.id
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // findById
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class ID로조회 {

        @Test
        fun `저장된 작업을 ID로 찾을 수 있어야 한다`() {
            val saved = store.save(mcpJob())

            val found = store.findById(saved.id)
            found.shouldNotBeNull() withMessage "저장된 작업은 findById로 찾을 수 있어야 한다"
            found.name shouldBe "test-job"
        }

        @Test
        fun `존재하지 않는 ID는 null을 반환해야 한다`() {
            store.findById("non-existent-id").shouldBeNull() withMessage "미존재 ID 조회는 null을 반환해야 한다"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // findByName
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 이름으로조회 {

        @Test
        fun `저장된 작업을 이름으로 찾을 수 있어야 한다`() {
            store.save(mcpJob("unique-name"))

            store.findByName("unique-name").shouldNotBeNull() withMessage "저장된 작업은 findByName으로 찾을 수 있어야 한다"
        }

        @Test
        fun `존재하지 않는 이름은 null을 반환해야 한다`() {
            store.findByName("ghost-job").shouldBeNull() withMessage "미존재 이름 조회는 null을 반환해야 한다"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // save
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 저장 {

        @Test
        fun `MCP_TOOL 작업을 저장하고 필드를 복원해야 한다`() {
            val saved = store.save(mcpJob("mcp-job"))

            val found = store.findById(saved.id)!!
            found.name shouldBe "mcp-job"
            found.cronExpression shouldBe "0 * * * * *"
            found.jobType shouldBe ScheduledJobType.MCP_TOOL
            found.mcpServerName shouldBe "my-server"
            found.toolName shouldBe "my-tool"
            found.toolArguments["key"] shouldBe "value"
            assertTrue(found.enabled, "enabled 기본값은 true여야 한다")
        }

        @Test
        fun `AGENT 모드 작업을 저장하고 필드를 복원해야 한다`() {
            val saved = store.save(agentJob())

            val found = store.findById(saved.id)!!
            found.jobType shouldBe ScheduledJobType.AGENT
            found.agentPrompt shouldBe "아침 브리핑을 작성해라"
            found.personaId shouldBe "briefing-persona"
            found.agentModel shouldBe "gemini-2.5-flash"
            found.agentMaxToolCalls shouldBe 5
            found.slackChannelId shouldBe "C12345678"
            found.teamsWebhookUrl shouldBe "https://teams.webhook.url/hook"
            assertTrue(found.retryOnFailure, "retryOnFailure가 true로 저장되어야 한다")
            found.maxRetryCount shouldBe 2
            found.executionTimeoutMs shouldBe 30_000L
        }

        @Test
        fun `빈 id 필드는 UUID로 자동 채워져야 한다`() {
            val saved = store.save(mcpJob())
            assertTrue(saved.id.isNotBlank(), "저장 후 ID가 자동 생성되어야 한다")
        }

        @Test
        fun `toolArguments가 없는 경우 빈 맵으로 복원되어야 한다`() {
            val jobNoArgs = ScheduledJob(
                name = "no-args-job",
                cronExpression = "0 * * * * *",
                mcpServerName = "s",
                toolName = "t",
                toolArguments = emptyMap()
            )
            val saved = store.save(jobNoArgs)

            val found = store.findById(saved.id)!!
            assertTrue(found.toolArguments.isEmpty(), "인자 없는 작업의 toolArguments는 빈 맵이어야 한다")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // update
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 수정 {

        @Test
        fun `기존 작업의 필드를 수정해야 한다`() {
            val saved = store.save(mcpJob("original"))
            val updated = saved.copy(
                name = "updated",
                cronExpression = "0 0 * * * *",
                enabled = false
            )

            val result = store.update(saved.id, updated)
            result.shouldNotBeNull() withMessage "update는 수정된 작업을 반환해야 한다"
            result.name shouldBe "updated"
            result.cronExpression shouldBe "0 0 * * * *"
            assertFalse(result.enabled, "enabled=false가 반영되어야 한다")
        }

        @Test
        fun `존재하지 않는 ID 수정은 null을 반환해야 한다`() {
            val ghost = mcpJob("ghost")
            store.update("ghost-id", ghost).shouldBeNull() withMessage "미존재 ID 수정은 null을 반환해야 한다"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // delete
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 삭제 {

        @Test
        fun `삭제 후 findById는 null을 반환해야 한다`() {
            val saved = store.save(mcpJob())

            store.delete(saved.id)

            store.findById(saved.id).shouldBeNull() withMessage "삭제된 작업은 findById로 찾을 수 없어야 한다"
        }

        @Test
        fun `존재하지 않는 ID 삭제는 예외 없이 처리되어야 한다`() {
            // 예외가 발생하지 않아야 한다
            store.delete("non-existent")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // updateExecutionResult
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 실행결과갱신 {

        @Test
        fun `last_status와 last_run_at이 갱신되어야 한다`() {
            val saved = store.save(mcpJob())
            val before = Instant.now().minusSeconds(1)

            store.updateExecutionResult(saved.id, JobExecutionStatus.SUCCESS, "완료")

            val found = store.findById(saved.id)!!
            found.lastStatus shouldBe JobExecutionStatus.SUCCESS
            found.lastResult shouldBe "완료"
            assertNotNull(found.lastRunAt, "실행 후 lastRunAt이 설정되어야 한다")
            val lastRunAt = found.lastRunAt!!
            assertTrue(
                lastRunAt.isAfter(before) || lastRunAt == before,
                "lastRunAt은 갱신 시각 이후이어야 한다"
            )
        }

        @Test
        fun `결과 텍스트가 5000자를 초과하면 잘려야 한다`() {
            val saved = store.save(mcpJob())
            val longResult = "x".repeat(6000)

            store.updateExecutionResult(saved.id, JobExecutionStatus.FAILED, longResult)

            val found = store.findById(saved.id)!!
            val savedResult = found.lastResult
            assertNotNull(savedResult, "긴 결과도 저장되어야 한다")
            assertTrue(
                (savedResult?.length ?: 0) <= ScheduledJobStore.RESULT_TRUNCATION_LIMIT,
                "결과는 ${ScheduledJobStore.RESULT_TRUNCATION_LIMIT}자 이하로 잘려야 한다"
            )
        }

        @Test
        fun `null 결과를 저장할 수 있어야 한다`() {
            val saved = store.save(mcpJob())

            store.updateExecutionResult(saved.id, JobExecutionStatus.SKIPPED, null)

            val found = store.findById(saved.id)!!
            found.lastStatus shouldBe JobExecutionStatus.SKIPPED
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 태그 직렬화/역직렬화
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class 태그직렬화 {

        @Test
        fun `태그가 저장 후 동일하게 복원되어야 한다`() {
            val tags = setOf("alpha", "beta", "gamma")
            val saved = store.save(mcpJob(tags = tags))

            val found = store.findById(saved.id)!!
            found.tags shouldBe tags
        }

        @Test
        fun `빈 태그 집합이 저장되고 빈 집합으로 복원되어야 한다`() {
            val saved = store.save(mcpJob(tags = emptySet()))

            val found = store.findById(saved.id)!!
            assertTrue(found.tags.isEmpty(), "빈 태그 집합은 빈 Set으로 복원되어야 한다")
        }
    }
}
