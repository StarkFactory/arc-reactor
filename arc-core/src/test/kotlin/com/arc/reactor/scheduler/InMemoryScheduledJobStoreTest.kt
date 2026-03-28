package com.arc.reactor.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 실패 메시지 어노테이션 헬퍼 — 가독성용, 반환값은 그대로 */
private infix fun <T> T.withMessage(@Suppress("UNUSED_PARAMETER") message: String): T = this

/**
 * [InMemoryScheduledJobStore] 단위 테스트.
 *
 * 대상: CRUD 전 동작, 타임스탬프 설정, ID 자동 생성, 결과 잘림(truncation).
 */
class InMemoryScheduledJobStoreTest {

    private lateinit var store: InMemoryScheduledJobStore

    @BeforeEach
    fun setUp() {
        store = InMemoryScheduledJobStore()
    }

    /** 공통 픽스처 — 최소 필드만 채운 ScheduledJob */
    private fun job(name: String = "test-job", id: String = "") = ScheduledJob(
        id = id,
        name = name,
        cronExpression = "0 * * * * *"
    )

    @Nested
    inner class List작업 {

        @Test
        fun `비어있을 때 빈 목록을 반환해야 한다`() {
            store.list().shouldBeEmpty() withMessage "초기 상태는 빈 목록이어야 한다"
        }

        @Test
        fun `저장된 모든 작업을 반환해야 한다`() {
            store.save(job("a"))
            store.save(job("b"))
            store.list() shouldHaveSize 2
        }

        @Test
        fun `createdAt 기준 오름차순으로 반환해야 한다`() {
            val first = store.save(job("first"))
            Thread.sleep(5) // createdAt 차이를 만들기 위해 최소 지연
            val second = store.save(job("second"))

            val list = store.list()
            list[0].id shouldBe first.id
            list[1].id shouldBe second.id
        }
    }

    @Nested
    inner class ID로조회 {

        @Test
        fun `존재하는 ID로 작업을 조회해야 한다`() {
            val saved = store.save(job())
            store.findById(saved.id).shouldNotBeNull() withMessage "저장된 작업은 findById로 찾을 수 있어야 한다"
        }

        @Test
        fun `존재하지 않는 ID 조회 시 null을 반환해야 한다`() {
            store.findById("없는-id").shouldBeNull() withMessage "미존재 ID 조회는 null을 반환해야 한다"
        }
    }

    @Nested
    inner class 이름으로조회 {

        @Test
        fun `이름으로 작업을 조회해야 한다`() {
            store.save(job("my-job"))
            store.findByName("my-job").shouldNotBeNull() withMessage "저장된 작업은 이름으로 찾을 수 있어야 한다"
        }

        @Test
        fun `존재하지 않는 이름 조회 시 null을 반환해야 한다`() {
            store.findByName("없는-이름").shouldBeNull() withMessage "미존재 이름 조회는 null을 반환해야 한다"
        }
    }

    @Nested
    inner class 저장 {

        @Test
        fun `빈 ID 입력 시 UUID를 자동 생성해야 한다`() {
            val saved = store.save(job(id = ""))
            saved.id.isNotBlank() shouldBe true
        }

        @Test
        fun `ID를 명시하면 해당 ID를 그대로 사용해야 한다`() {
            val saved = store.save(job(id = "explicit-id"))
            saved.id shouldBe "explicit-id"
        }

        @Test
        fun `저장 시 createdAt과 updatedAt을 설정해야 한다`() {
            val saved = store.save(job())
            saved.createdAt.shouldNotBeNull() withMessage "createdAt이 설정되어야 한다"
            saved.updatedAt.shouldNotBeNull() withMessage "updatedAt이 설정되어야 한다"
        }

        @Test
        fun `저장된 작업은 findById로 조회할 수 있어야 한다`() {
            val saved = store.save(job("new-job"))
            val found = store.findById(saved.id)
            found.shouldNotBeNull() withMessage "저장 후 findById로 조회 가능해야 한다"
            found.name shouldBe "new-job"
        }
    }

    @Nested
    inner class 수정 {

        @Test
        fun `존재하는 작업을 수정해야 한다`() {
            val saved = store.save(job("original"))
            val updated = store.update(saved.id, saved.copy(name = "modified"))

            updated.shouldNotBeNull() withMessage "존재하는 작업 수정은 null이 아니어야 한다"
            updated.name shouldBe "modified"
        }

        @Test
        fun `수정 시 ID를 입력값 ID로 덮어써야 한다`() {
            val saved = store.save(job())
            val updated = store.update(saved.id, saved.copy(id = "다른-id"))
            updated?.id shouldBe saved.id
        }

        @Test
        fun `수정 시 원본 createdAt을 유지해야 한다`() {
            val saved = store.save(job())
            val updated = store.update(saved.id, saved)
            updated?.createdAt shouldBe saved.createdAt
        }

        @Test
        fun `존재하지 않는 ID 수정 시 null을 반환해야 한다`() {
            store.update("없는-id", job()).shouldBeNull() withMessage "미존재 ID 수정은 null을 반환해야 한다"
        }
    }

    @Nested
    inner class 삭제 {

        @Test
        fun `작업을 삭제한 뒤 조회되지 않아야 한다`() {
            val saved = store.save(job())
            store.delete(saved.id)
            store.findById(saved.id).shouldBeNull() withMessage "삭제 후 조회는 null이어야 한다"
        }

        @Test
        fun `삭제 후 목록에서 제거되어야 한다`() {
            val saved = store.save(job())
            store.delete(saved.id)
            store.list().shouldBeEmpty() withMessage "삭제 후 목록은 비어있어야 한다"
        }

        @Test
        fun `존재하지 않는 ID 삭제 시 예외 없이 종료해야 한다`() {
            store.delete("없는-id") // 예외 없이 종료해야 한다
        }
    }

    @Nested
    inner class 실행결과갱신 {

        @Test
        fun `마지막 실행 상태와 결과를 갱신해야 한다`() {
            val saved = store.save(job())
            store.updateExecutionResult(saved.id, JobExecutionStatus.SUCCESS, "완료")

            val found = store.findById(saved.id)
            found?.lastStatus shouldBe JobExecutionStatus.SUCCESS
            found?.lastResult shouldBe "완료"
        }

        @Test
        fun `결과 텍스트가 RESULT_TRUNCATION_LIMIT을 초과하면 잘라야 한다`() {
            val saved = store.save(job())
            val longResult = "X".repeat(ScheduledJobStore.RESULT_TRUNCATION_LIMIT + 100)
            store.updateExecutionResult(saved.id, JobExecutionStatus.FAILED, longResult)

            val found = store.findById(saved.id)
            found?.lastResult?.length shouldBe ScheduledJobStore.RESULT_TRUNCATION_LIMIT
        }

        @Test
        fun `null 결과로 갱신할 수 있어야 한다`() {
            val saved = store.save(job())
            store.updateExecutionResult(saved.id, JobExecutionStatus.SKIPPED, null)

            val found = store.findById(saved.id)
            found?.lastStatus shouldBe JobExecutionStatus.SKIPPED
            found?.lastResult.shouldBeNull() withMessage "null 결과는 null로 저장되어야 한다"
        }

        @Test
        fun `존재하지 않는 ID에 대한 갱신은 무시해야 한다`() {
            store.updateExecutionResult("없는-id", JobExecutionStatus.SUCCESS, "결과")
            // 예외 없이 종료해야 한다
        }
    }
}
