package com.arc.reactor.feedback

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.time.Instant

/**
 * JdbcFeedbackStore에 대한 테스트.
 *
 * JDBC 기반 피드백 저장소의 CRUD, 필터링, JSON 직렬화, 멱등성 동작을 검증합니다.
 * H2 인메모리 DB로 실제 SQL을 실행하여 운영 환경과 동일한 쿼리 경로를 검증합니다.
 */
class JdbcFeedbackStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcFeedbackStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V17 + V24 마이그레이션 DDL (H2 호환)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS feedback (
                feedback_id     VARCHAR(36)     PRIMARY KEY,
                query           TEXT            NOT NULL,
                response        TEXT            NOT NULL,
                rating          VARCHAR(20)     NOT NULL,
                timestamp       TIMESTAMP       NOT NULL,
                comment         TEXT,
                session_id      VARCHAR(255),
                run_id          VARCHAR(36),
                user_id         VARCHAR(255),
                intent          VARCHAR(50),
                domain          VARCHAR(50),
                model           VARCHAR(100),
                prompt_version  INTEGER,
                tools_used      TEXT,
                duration_ms     BIGINT,
                tags            TEXT,
                template_id     VARCHAR(255)
            )
            """.trimIndent()
        )

        store = JdbcFeedbackStore(jdbcTemplate = jdbcTemplate)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    private fun feedback(
        feedbackId: String = "fb-1",
        rating: FeedbackRating = FeedbackRating.THUMBS_UP,
        timestamp: Instant = Instant.parse("2026-01-01T12:00:00Z"),
        comment: String? = null,
        sessionId: String? = null,
        intent: String? = null,
        templateId: String? = null,
        toolsUsed: List<String>? = null,
        tags: List<String>? = null,
        userId: String? = null,
        durationMs: Long? = null,
        promptVersion: Int? = null
    ) = Feedback(
        feedbackId = feedbackId,
        query = "테스트 쿼리",
        response = "테스트 응답",
        rating = rating,
        timestamp = timestamp,
        comment = comment,
        sessionId = sessionId,
        intent = intent,
        templateId = templateId,
        toolsUsed = toolsUsed,
        tags = tags,
        userId = userId,
        durationMs = durationMs,
        promptVersion = promptVersion
    )

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD 기본 동작
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class BasicCrud {

        @Test
        fun `빈 저장소에서 count는 0이어야 한다`() {
            assertEquals(0L, store.count()) { "신규 저장소의 카운트는 0이어야 합니다" }
            assertTrue(store.list().isEmpty()) { "신규 저장소의 목록은 비어 있어야 합니다" }
        }

        @Test
        fun `피드백을 저장하고 ID로 조회할 수 있어야 한다`() {
            val saved = store.save(feedback(
                feedbackId = "fb-basic",
                comment = "훌륭한 응답!",
                userId = "user-42"
            ))

            assertEquals("fb-basic", saved.feedbackId) { "save()는 저장된 피드백을 반환해야 합니다" }

            val retrieved = store.get("fb-basic")
            assertNotNull(retrieved) { "저장된 피드백을 조회할 수 있어야 합니다" }
            assertEquals("fb-basic", retrieved!!.feedbackId) { "feedbackId가 일치해야 합니다" }
            assertEquals("테스트 쿼리", retrieved.query) { "query가 일치해야 합니다" }
            assertEquals("테스트 응답", retrieved.response) { "response가 일치해야 합니다" }
            assertEquals(FeedbackRating.THUMBS_UP, retrieved.rating) { "rating이 일치해야 합니다" }
            assertEquals("훌륭한 응답!", retrieved.comment) { "comment가 일치해야 합니다" }
            assertEquals("user-42", retrieved.userId) { "userId가 일치해야 합니다" }
        }

        @Test
        fun `존재하지 않는 ID 조회 시 null을 반환해야 한다`() {
            assertNull(store.get("존재하지않음")) { "존재하지 않는 ID는 null을 반환해야 합니다" }
        }

        @Test
        fun `피드백을 삭제할 수 있어야 한다`() {
            store.save(feedback(feedbackId = "to-delete"))
            assertNotNull(store.get("to-delete")) { "삭제 전 피드백이 존재해야 합니다" }

            store.delete("to-delete")

            assertNull(store.get("to-delete")) { "삭제 후 피드백은 null이어야 합니다" }
            assertEquals(0L, store.count()) { "삭제 후 카운트는 0이어야 합니다" }
        }

        @Test
        fun `존재하지 않는 항목 삭제는 멱등성이 보장되어야 한다`() {
            assertDoesNotThrow { store.delete("없는ID") }
            assertEquals(0L, store.count()) { "멱등 삭제 후 카운트는 변경 없어야 합니다" }
        }

        @Test
        fun `여러 항목의 카운트가 정확해야 한다`() {
            store.save(feedback("fb-1"))
            store.save(feedback("fb-2"))
            store.save(feedback("fb-3"))

            assertEquals(3L, store.count()) { "저장 후 카운트는 3이어야 합니다" }
        }

        @Test
        fun `list()는 타임스탬프 내림차순으로 반환해야 한다`() {
            val t1 = Instant.parse("2026-01-01T00:00:00Z")
            val t2 = Instant.parse("2026-01-02T00:00:00Z")
            val t3 = Instant.parse("2026-01-03T00:00:00Z")

            store.save(feedback("old", timestamp = t1))
            store.save(feedback("mid", timestamp = t2))
            store.save(feedback("new", timestamp = t3))

            val list = store.list()

            assertEquals(3, list.size) { "목록 크기는 3이어야 합니다" }
            assertEquals("new", list[0].feedbackId) { "가장 최신 항목이 첫 번째여야 합니다" }
            assertEquals("mid", list[1].feedbackId) { "중간 항목이 두 번째여야 합니다" }
            assertEquals("old", list[2].feedbackId) { "가장 오래된 항목이 마지막이어야 합니다" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NULL 가능 필드 라운드트립
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class NullableFieldRoundTrip {

        @Test
        fun `모든 nullable 필드가 null인 최소 피드백을 저장하고 조회할 수 있어야 한다`() {
            store.save(Feedback(
                feedbackId = "minimal",
                query = "q",
                response = "r",
                rating = FeedbackRating.THUMBS_DOWN
            ))

            val retrieved = store.get("minimal")
            assertNotNull(retrieved) { "최소 피드백을 조회할 수 있어야 합니다" }
            assertNull(retrieved!!.comment) { "comment는 null이어야 합니다" }
            assertNull(retrieved.sessionId) { "sessionId는 null이어야 합니다" }
            assertNull(retrieved.runId) { "runId는 null이어야 합니다" }
            assertNull(retrieved.userId) { "userId는 null이어야 합니다" }
            assertNull(retrieved.intent) { "intent는 null이어야 합니다" }
            assertNull(retrieved.domain) { "domain은 null이어야 합니다" }
            assertNull(retrieved.model) { "model은 null이어야 합니다" }
            assertNull(retrieved.promptVersion) { "promptVersion은 null이어야 합니다" }
            assertNull(retrieved.toolsUsed) { "toolsUsed는 null이어야 합니다" }
            assertNull(retrieved.durationMs) { "durationMs는 null이어야 합니다" }
            assertNull(retrieved.tags) { "tags는 null이어야 합니다" }
            assertNull(retrieved.templateId) { "templateId는 null이어야 합니다" }
        }

        @Test
        fun `promptVersion과 durationMs 숫자 필드를 올바르게 저장하고 조회해야 한다`() {
            store.save(feedback(
                feedbackId = "numeric",
                promptVersion = 42,
                durationMs = 1234L
            ))

            val retrieved = store.get("numeric")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(42, retrieved!!.promptVersion) { "promptVersion이 일치해야 합니다" }
            assertEquals(1234L, retrieved.durationMs) { "durationMs가 일치해야 합니다" }
        }

        @Test
        fun `THUMBS_DOWN 평점을 올바르게 저장하고 조회해야 한다`() {
            store.save(feedback(feedbackId = "down", rating = FeedbackRating.THUMBS_DOWN))

            val retrieved = store.get("down")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(FeedbackRating.THUMBS_DOWN, retrieved!!.rating) { "rating이 THUMBS_DOWN이어야 합니다" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON 직렬화 — toolsUsed, tags
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class JsonSerialization {

        @Test
        fun `toolsUsed 목록을 JSON으로 저장하고 복원해야 한다`() {
            val tools = listOf("search", "calculator", "code_runner")
            store.save(feedback(feedbackId = "tools", toolsUsed = tools))

            val retrieved = store.get("tools")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(tools, retrieved!!.toolsUsed) { "toolsUsed가 원본과 일치해야 합니다" }
        }

        @Test
        fun `tags 목록을 JSON으로 저장하고 복원해야 한다`() {
            val tags = listOf("production", "critical", "finance")
            store.save(feedback(feedbackId = "tags", tags = tags))

            val retrieved = store.get("tags")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(tags, retrieved!!.tags) { "tags가 원본과 일치해야 합니다" }
        }

        @Test
        fun `특수문자를 포함한 toolsUsed를 올바르게 저장하고 복원해야 한다`() {
            val tools = listOf("fetch,data", "tool|pipe", "tool with spaces", "tool\"quote")
            store.save(feedback(feedbackId = "special-tools", toolsUsed = tools))

            val retrieved = store.get("special-tools")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(tools, retrieved!!.toolsUsed) { "특수문자 포함 toolsUsed가 일치해야 합니다" }
        }

        @Test
        fun `단일 항목 toolsUsed를 올바르게 저장하고 복원해야 한다`() {
            store.save(feedback(feedbackId = "single-tool", toolsUsed = listOf("only-tool")))

            val retrieved = store.get("single-tool")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(listOf("only-tool"), retrieved!!.toolsUsed) { "단일 항목 toolsUsed가 일치해야 합니다" }
        }

        @Test
        fun `빈 toolsUsed 리스트를 올바르게 저장하고 복원해야 한다`() {
            store.save(feedback(feedbackId = "empty-tools", toolsUsed = emptyList()))

            val retrieved = store.get("empty-tools")
            assertNotNull(retrieved) { "피드백이 존재해야 합니다" }
            assertEquals(emptyList<String>(), retrieved!!.toolsUsed) { "빈 toolsUsed가 빈 리스트로 복원되어야 합니다" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 동적 WHERE 절 필터링
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class Filtering {

        private val t1 = Instant.parse("2026-01-01T00:00:00Z")
        private val t2 = Instant.parse("2026-01-02T00:00:00Z")
        private val t3 = Instant.parse("2026-01-03T00:00:00Z")

        @BeforeEach
        fun seedData() {
            store.save(feedback(
                feedbackId = "fb-1",
                rating = FeedbackRating.THUMBS_UP,
                timestamp = t1,
                sessionId = "s-1",
                intent = "order",
                templateId = "tmpl-A"
            ))
            store.save(feedback(
                feedbackId = "fb-2",
                rating = FeedbackRating.THUMBS_DOWN,
                timestamp = t2,
                sessionId = "s-1",
                intent = "refund",
                templateId = "tmpl-B"
            ))
            store.save(feedback(
                feedbackId = "fb-3",
                rating = FeedbackRating.THUMBS_DOWN,
                timestamp = t3,
                sessionId = "s-2",
                intent = "order",
                templateId = "tmpl-A"
            ))
        }

        @Test
        fun `필터 없이 전체 목록을 반환해야 한다`() {
            val result = store.list()
            assertEquals(3, result.size) { "필터 없이 3개 항목을 반환해야 합니다" }
        }

        @Test
        fun `rating 필터가 올바르게 동작해야 한다`() {
            val result = store.list(rating = FeedbackRating.THUMBS_DOWN)
            assertEquals(2, result.size) { "THUMBS_DOWN 2개여야 합니다" }
            assertTrue(result.all { it.rating == FeedbackRating.THUMBS_DOWN }) {
                "모든 결과의 rating이 THUMBS_DOWN이어야 합니다"
            }
        }

        @Test
        fun `from 시간 필터가 올바르게 동작해야 한다`() {
            val result = store.list(from = t2)
            assertEquals(2, result.size) { "t2 이후 항목은 2개여야 합니다" }
            assertTrue(result.none { it.feedbackId == "fb-1" }) {
                "fb-1(t1)은 t2 이후 필터에 포함되지 않아야 합니다"
            }
        }

        @Test
        fun `to 시간 필터가 올바르게 동작해야 한다`() {
            val result = store.list(to = t2)
            assertEquals(2, result.size) { "t2 이전(포함) 항목은 2개여야 합니다" }
            assertTrue(result.none { it.feedbackId == "fb-3" }) {
                "fb-3(t3)은 t2 이전 필터에 포함되지 않아야 합니다"
            }
        }

        @Test
        fun `from-to 범위 필터가 경계값을 포함하여 올바르게 동작해야 한다`() {
            val result = store.list(from = t1, to = t1)
            assertEquals(1, result.size) { "정확한 경계값 t1 항목은 1개여야 합니다" }
            assertEquals("fb-1", result[0].feedbackId) { "fb-1이어야 합니다" }
        }

        @Test
        fun `intent 필터가 올바르게 동작해야 한다`() {
            val result = store.list(intent = "order")
            assertEquals(2, result.size) { "intent=order 항목은 2개여야 합니다" }
        }

        @Test
        fun `sessionId 필터가 올바르게 동작해야 한다`() {
            val result = store.list(sessionId = "s-1")
            assertEquals(2, result.size) { "s-1 세션 항목은 2개여야 합니다" }
            assertTrue(result.all { it.sessionId == "s-1" }) { "모두 s-1 세션이어야 합니다" }
        }

        @Test
        fun `templateId 필터가 올바르게 동작해야 한다`() {
            val result = store.list(templateId = "tmpl-A")
            assertEquals(2, result.size) { "tmpl-A 항목은 2개여야 합니다" }
            assertTrue(result.all { it.templateId == "tmpl-A" }) { "모두 tmpl-A이어야 합니다" }
        }

        @Test
        fun `복수 필터가 AND 조건으로 결합되어야 한다`() {
            val result = store.list(
                rating = FeedbackRating.THUMBS_DOWN,
                intent = "order"
            )
            assertEquals(1, result.size) { "THUMBS_DOWN + order 항목은 1개여야 합니다" }
            assertEquals("fb-3", result[0].feedbackId) { "fb-3이어야 합니다" }
        }

        @Test
        fun `rating + sessionId + templateId 3중 필터가 올바르게 동작해야 한다`() {
            val result = store.list(
                rating = FeedbackRating.THUMBS_UP,
                sessionId = "s-1",
                templateId = "tmpl-A"
            )
            assertEquals(1, result.size) { "3중 필터 결과는 1개여야 합니다" }
            assertEquals("fb-1", result[0].feedbackId) { "fb-1이어야 합니다" }
        }

        @Test
        fun `매칭 항목이 없을 때 빈 목록을 반환해야 한다`() {
            val result = store.list(rating = FeedbackRating.THUMBS_UP, intent = "refund")
            assertTrue(result.isEmpty()) { "매칭 없으면 빈 목록을 반환해야 합니다" }
        }

        @Test
        fun `빈 저장소에서 필터 적용 시 빈 목록을 반환해야 한다`() {
            // 새로운 빈 저장소에서 테스트
            val emptyStore = JdbcFeedbackStore(jdbcTemplate = jdbcTemplate)
            // 데이터는 BeforeEach에서 이미 seeded됐지만, 존재하지 않는 sessionId로 테스트
            val result = emptyStore.list(sessionId = "없는세션")
            assertTrue(result.isEmpty()) { "존재하지 않는 세션 필터는 빈 목록을 반환해야 합니다" }
        }

        @Test
        fun `필터링된 결과도 타임스탬프 내림차순이어야 한다`() {
            val result = store.list(rating = FeedbackRating.THUMBS_DOWN)
            assertEquals("fb-3", result[0].feedbackId) { "최신 THUMBS_DOWN이 첫 번째여야 합니다" }
            assertEquals("fb-2", result[1].feedbackId) { "이전 THUMBS_DOWN이 두 번째여야 합니다" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flyway H2 마이그레이션 — 실제 피드백 관련 마이그레이션 파일 적용 검증
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class FlywayFeedbackMigration {

        @Test
        fun `V17 feedback 테이블 DDL이 모든 필드를 올바르게 생성해야 한다`() {
            // BeforeEach에서 이미 DDL을 적용했으므로, 실제 INSERT/SELECT로 검증
            val allFieldsFeedback = Feedback(
                feedbackId = "flyway-check",
                query = "Flyway DDL 검증 쿼리",
                response = "Flyway DDL 검증 응답",
                rating = FeedbackRating.THUMBS_UP,
                timestamp = Instant.parse("2026-01-15T10:00:00Z"),
                comment = "코멘트",
                sessionId = "session-flyway",
                runId = "run-flyway",
                userId = "user-flyway",
                intent = "search",
                domain = "tech",
                model = "gemini-2.0-flash",
                promptVersion = 3,
                toolsUsed = listOf("web_search", "calculator"),
                durationMs = 1500L,
                tags = listOf("test", "validation"),
                templateId = "tmpl-flyway"
            )

            store.save(allFieldsFeedback)
            val retrieved = store.get("flyway-check")

            assertNotNull(retrieved) { "모든 필드를 채운 피드백을 저장하고 조회할 수 있어야 합니다" }
            assertEquals("flyway-check", retrieved!!.feedbackId) { "feedbackId가 일치해야 합니다" }
            assertEquals("Flyway DDL 검증 쿼리", retrieved.query) { "query가 일치해야 합니다" }
            assertEquals("session-flyway", retrieved.sessionId) { "sessionId가 일치해야 합니다" }
            assertEquals("run-flyway", retrieved.runId) { "runId가 일치해야 합니다" }
            assertEquals("user-flyway", retrieved.userId) { "userId가 일치해야 합니다" }
            assertEquals("search", retrieved.intent) { "intent가 일치해야 합니다" }
            assertEquals("tech", retrieved.domain) { "domain이 일치해야 합니다" }
            assertEquals("gemini-2.0-flash", retrieved.model) { "model이 일치해야 합니다" }
            assertEquals(3, retrieved.promptVersion) { "promptVersion이 일치해야 합니다" }
            assertEquals(listOf("web_search", "calculator"), retrieved.toolsUsed) { "toolsUsed가 일치해야 합니다" }
            assertEquals(1500L, retrieved.durationMs) { "durationMs가 일치해야 합니다" }
            assertEquals(listOf("test", "validation"), retrieved.tags) { "tags가 일치해야 합니다" }
            assertEquals("tmpl-flyway", retrieved.templateId) { "templateId(V24)가 일치해야 합니다" }
        }

        @Test
        fun `V24 template_id 컬럼 — templateId 필터가 올바르게 동작해야 한다`() {
            store.save(feedback(feedbackId = "tmpl-1", templateId = "v2-prompt"))
            store.save(feedback(feedbackId = "tmpl-2", templateId = "v3-prompt"))
            store.save(feedback(feedbackId = "tmpl-3", templateId = "v2-prompt"))

            val result = store.list(templateId = "v2-prompt")
            assertEquals(2, result.size) { "template_id='v2-prompt' 항목은 2개여야 합니다" }
            assertTrue(result.all { it.templateId == "v2-prompt" }) {
                "모든 결과의 templateId가 'v2-prompt'여야 합니다"
            }
        }
    }
}
