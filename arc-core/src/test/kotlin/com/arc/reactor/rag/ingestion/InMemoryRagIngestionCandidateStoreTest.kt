package com.arc.reactor.rag.ingestion

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * InMemoryRagIngestionCandidateStore에 대한 테스트.
 *
 * FIFO 퇴거 로직, 중복 수집 방지, 필터 조회, 리뷰 업데이트를 검증한다.
 */
class InMemoryRagIngestionCandidateStoreTest {

    private fun candidate(
        id: String = UUID.randomUUID().toString(),
        runId: String = UUID.randomUUID().toString(),
        userId: String = "user-1",
        channel: String? = null,
        status: RagIngestionCandidateStatus = RagIngestionCandidateStatus.PENDING
    ) = RagIngestionCandidate(
        id = id,
        runId = runId,
        userId = userId,
        channel = channel,
        query = "test query",
        response = "test response",
        status = status
    )

    @Nested
    inner class SaveAndFind {

        @Test
        fun `저장 후 ID로 조회할 수 있다`() {
            val store = InMemoryRagIngestionCandidateStore()
            val c = candidate(id = "c1")

            store.save(c)

            store.findById("c1") shouldBe c
        }

        @Test
        fun `저장 후 runId로 조회할 수 있다`() {
            val store = InMemoryRagIngestionCandidateStore()
            val c = candidate(runId = "run-1")

            store.save(c)

            store.findByRunId("run-1") shouldNotBe null
            store.findByRunId("run-1")!!.runId shouldBe "run-1"
        }

        @Test
        fun `존재하지 않는 ID는 null을 반환한다`() {
            val store = InMemoryRagIngestionCandidateStore()

            store.findById("nonexistent") shouldBe null
        }

        @Test
        fun `존재하지 않는 runId는 null을 반환한다`() {
            val store = InMemoryRagIngestionCandidateStore()

            store.findByRunId("nonexistent") shouldBe null
        }
    }

    @Nested
    inner class Deduplication {

        @Test
        fun `같은 runId 중복 저장 시 기존 후보를 반환한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            val first = candidate(id = "c1", runId = "run-dup")
            val second = candidate(id = "c2", runId = "run-dup")

            store.save(first)
            val result = store.save(second)

            result.id shouldBe "c1"
            store.findById("c2") shouldBe null
        }
    }

    @Nested
    inner class ListFiltering {

        @Test
        fun `최신 순으로 조회한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            store.save(candidate(id = "c1", runId = "r1"))
            store.save(candidate(id = "c2", runId = "r2"))
            store.save(candidate(id = "c3", runId = "r3"))

            val list = store.list(limit = 10)

            list.size shouldBe 3
            list[0].id shouldBe "c3"
            list[2].id shouldBe "c1"
        }

        @Test
        fun `limit 제한을 적용한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            repeat(5) { i -> store.save(candidate(id = "c$i", runId = "r$i")) }

            store.list(limit = 2).size shouldBe 2
        }

        @Test
        fun `status 필터를 적용한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            store.save(candidate(id = "c1", runId = "r1", status = RagIngestionCandidateStatus.PENDING))
            store.save(candidate(id = "c2", runId = "r2", status = RagIngestionCandidateStatus.INGESTED))

            val pending = store.list(limit = 10, status = RagIngestionCandidateStatus.PENDING)

            pending.size shouldBe 1
            pending[0].id shouldBe "c1"
        }

        @Test
        fun `channel 필터를 적용한다 (대소문자 무시)`() {
            val store = InMemoryRagIngestionCandidateStore()
            store.save(candidate(id = "c1", runId = "r1", channel = "Slack"))
            store.save(candidate(id = "c2", runId = "r2", channel = "web"))

            val slack = store.list(limit = 10, channel = "slack")

            slack.size shouldBe 1
            slack[0].id shouldBe "c1"
        }

        @Test
        fun `빈 channel 필터는 무시한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            store.save(candidate(id = "c1", runId = "r1", channel = "web"))

            store.list(limit = 10, channel = "  ").size shouldBe 1
        }
    }

    @Nested
    inner class UpdateReview {

        @Test
        fun `리뷰 상태를 업데이트한다`() {
            val store = InMemoryRagIngestionCandidateStore()
            store.save(candidate(id = "c1", runId = "r1"))

            val updated = store.updateReview(
                id = "c1",
                status = RagIngestionCandidateStatus.INGESTED,
                reviewedBy = "admin",
                reviewComment = "good",
                ingestedDocumentId = "doc-1"
            )

            updated shouldNotBe null
            updated!!.status shouldBe RagIngestionCandidateStatus.INGESTED
            updated.reviewedBy shouldBe "admin"
            updated.reviewComment shouldBe "good"
            updated.ingestedDocumentId shouldBe "doc-1"
            updated.reviewedAt shouldNotBe null
        }

        @Test
        fun `존재하지 않는 ID 리뷰는 null을 반환한다`() {
            val store = InMemoryRagIngestionCandidateStore()

            store.updateReview(
                id = "nonexistent",
                status = RagIngestionCandidateStatus.REJECTED,
                reviewedBy = "admin",
                reviewComment = null
            ) shouldBe null
        }
    }

    @Nested
    inner class CapacityEviction {

        @Test
        fun `20000개 초과 시 가장 오래된 항목부터 제거한다`() {
            val store = InMemoryRagIngestionCandidateStore()

            // 20001개 삽입
            val ids = (1..20001).map { i ->
                val c = candidate(id = "c$i", runId = "r$i")
                store.save(c)
                "c$i"
            }

            // 가장 오래된 것이 제거되어야 한다
            store.findById("c1") shouldBe null

            // 가장 최근 것은 남아있어야 한다
            store.findById("c20001") shouldNotBe null

            // runId도 함께 정리되어야 한다
            store.findByRunId("r1") shouldBe null
        }

        @Test
        fun `퇴거 후에도 신규 삽입이 정상 동작한다`() {
            val store = InMemoryRagIngestionCandidateStore()

            // 20002개 삽입 (2개 퇴거)
            repeat(20002) { i -> store.save(candidate(id = "c$i", runId = "r$i")) }

            // 퇴거 후 새 항목 삽입
            val fresh = candidate(id = "fresh", runId = "r-fresh")
            store.save(fresh)

            store.findById("fresh") shouldNotBe null
            store.findByRunId("r-fresh") shouldNotBe null
        }
    }
}
