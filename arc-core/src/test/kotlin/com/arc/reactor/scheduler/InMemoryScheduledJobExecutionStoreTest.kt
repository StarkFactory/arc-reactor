package com.arc.reactor.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 실패 메시지 어노테이션 헬퍼 — 가독성용, 반환값은 그대로 */
private infix fun <T> T.withMessage(@Suppress("UNUSED_PARAMETER") message: String): T = this

/**
 * [InMemoryScheduledJobExecutionStore] 단위 테스트.
 *
 * 대상: 저장, ID 자동 생성, 최신 우선 순서, jobId 필터링, 한도 초과 퇴출, 오래된 항목 삭제.
 */
class InMemoryScheduledJobExecutionStoreTest {

    private lateinit var store: InMemoryScheduledJobExecutionStore

    @BeforeEach
    fun setUp() {
        store = InMemoryScheduledJobExecutionStore()
    }

    /** 공통 픽스처 */
    private fun execution(
        jobId: String = "job-1",
        id: String = "",
        status: JobExecutionStatus = JobExecutionStatus.SUCCESS
    ) = ScheduledJobExecution(
        id = id,
        jobId = jobId,
        jobName = "test-job",
        status = status
    )

    @Nested
    inner class 저장 {

        @Test
        fun `빈 ID 입력 시 UUID를 자동 생성해야 한다`() {
            val saved = store.save(execution(id = ""))
            saved.id.shouldNotBeBlank()
        }

        @Test
        fun `ID를 명시하면 해당 ID를 그대로 사용해야 한다`() {
            val saved = store.save(execution(id = "exec-123"))
            saved.id shouldBe "exec-123"
        }

        @Test
        fun `저장된 항목은 findRecent로 조회해야 한다`() {
            store.save(execution())
            store.findRecent(10) shouldHaveSize 1
        }
    }

    @Nested
    inner class 최신우선순서 {

        @Test
        fun `가장 최근 저장된 항목이 첫 번째로 반환되어야 한다`() {
            val first = store.save(execution(id = "first"))
            val second = store.save(execution(id = "second"))

            val recent = store.findRecent(10)
            recent[0].id shouldBe second.id
            recent[1].id shouldBe first.id
        }

        @Test
        fun `findByJobId도 최신 항목을 먼저 반환해야 한다`() {
            val exec1 = store.save(execution(id = "e1", jobId = "job-A"))
            val exec2 = store.save(execution(id = "e2", jobId = "job-A"))

            val results = store.findByJobId("job-A", 10)
            results[0].id shouldBe exec2.id
            results[1].id shouldBe exec1.id
        }
    }

    @Nested
    inner class JobId로조회 {

        @Test
        fun `해당 jobId의 항목만 반환해야 한다`() {
            store.save(execution(jobId = "job-A"))
            store.save(execution(jobId = "job-B"))

            val results = store.findByJobId("job-A", 10)
            results shouldHaveSize 1
            results[0].jobId shouldBe "job-A"
        }

        @Test
        fun `limit을 초과하지 않아야 한다`() {
            repeat(5) { store.save(execution(jobId = "job-1")) }

            store.findByJobId("job-1", 3) shouldHaveSize 3
        }

        @Test
        fun `존재하지 않는 jobId 조회 시 빈 목록을 반환해야 한다`() {
            store.findByJobId("없는-job", 10).shouldBeEmpty() withMessage "미존재 jobId 조회는 빈 목록이어야 한다"
        }
    }

    @Nested
    inner class 최근항목조회 {

        @Test
        fun `limit이 전체 항목 수보다 크면 전부 반환해야 한다`() {
            repeat(3) { store.save(execution()) }
            store.findRecent(100) shouldHaveSize 3
        }

        @Test
        fun `limit 만큼만 반환해야 한다`() {
            repeat(5) { store.save(execution()) }
            store.findRecent(3) shouldHaveSize 3
        }
    }

    @Nested
    inner class 한도초과퇴출 {

        @Test
        fun `MAX_ENTRIES 초과 시 가장 오래된 항목을 퇴출해야 한다`() {
            // MAX_ENTRIES = 200 (internal constant)
            val maxEntries = 200
            repeat(maxEntries) { i -> store.save(execution(id = "old-$i")) }

            // 모두 저장됐는지 확인
            store.findRecent(maxEntries + 1) shouldHaveSize maxEntries

            // 한 건 추가해 한도를 초과시킨다
            val newest = store.save(execution(id = "newest"))
            val results = store.findRecent(maxEntries + 1)

            results shouldHaveSize maxEntries
            results[0].id shouldBe newest.id
        }
    }

    @Nested
    inner class 오래된항목삭제 {

        @Test
        fun `keepCount보다 많으면 오래된 항목을 삭제해야 한다`() {
            repeat(5) { store.save(execution(jobId = "job-1")) }

            store.deleteOldestExecutions("job-1", keepCount = 3)

            store.findByJobId("job-1", 100) shouldHaveSize 3
        }

        @Test
        fun `keepCount 이하이면 삭제하지 않아야 한다`() {
            repeat(2) { store.save(execution(jobId = "job-1")) }

            store.deleteOldestExecutions("job-1", keepCount = 3)

            store.findByJobId("job-1", 100) shouldHaveSize 2
        }

        @Test
        fun `deleteOldestExecutions는 다른 jobId 항목에 영향을 주지 않아야 한다`() {
            repeat(3) { store.save(execution(jobId = "job-A")) }
            repeat(3) { store.save(execution(jobId = "job-B")) }

            store.deleteOldestExecutions("job-A", keepCount = 1)

            store.findByJobId("job-A", 100) shouldHaveSize 1
            store.findByJobId("job-B", 100) shouldHaveSize 3
        }

        @Test
        fun `존재하지 않는 jobId에 대한 삭제는 예외 없이 종료해야 한다`() {
            store.deleteOldestExecutions("없는-job", keepCount = 5)
            // 예외 없이 종료해야 한다
        }
    }
}
