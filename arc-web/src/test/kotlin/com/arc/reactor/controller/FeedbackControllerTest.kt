package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.feedback.Feedback
import com.arc.reactor.feedback.FeedbackRating
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.hook.impl.CapturedExecutionMetadata
import com.arc.reactor.hook.impl.FeedbackMetadataCaptureHook
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import java.time.Instant

/**
 * FeedbackController에 대한 테스트.
 *
 * 피드백 REST API의 동작을 검증합니다.
 */
class FeedbackControllerTest {

    private lateinit var feedbackStore: FeedbackStore
    private lateinit var metadataCaptureHook: FeedbackMetadataCaptureHook
    private lateinit var controller: FeedbackController

    @BeforeEach
    fun setup() {
        feedbackStore = mockk()
        metadataCaptureHook = mockk()
        controller = FeedbackController(feedbackStore, metadataCaptureHook)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun noAuthExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }

    private val now = Instant.parse("2026-02-16T12:00:00Z")

    private fun sampleFeedback(
        feedbackId: String = "fb-1",
        rating: FeedbackRating = FeedbackRating.THUMBS_UP
    ) = Feedback(
        feedbackId = feedbackId,
        query = "What is AI?",
        response = "AI is artificial intelligence.",
        rating = rating,
        timestamp = now,
        comment = "Good answer",
        sessionId = "s-1",
        runId = "run-1",
        userId = "user-1",
        intent = "general",
        toolsUsed = listOf("search"),
        durationMs = 500
    )

    @Nested
    inner class SubmitFeedback {

        @Test
        fun `created feedback로 return 201해야 한다`() = runTest {
            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }
            every { metadataCaptureHook.get(any()) } returns null

            val request = SubmitFeedbackRequest(
                rating = "thumbs_up",
                query = "What is AI?",
                response = "AI is artificial intelligence.",
                comment = "Great!"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201 Created" }
            val body = response.body!!
            assertEquals("thumbs_up", body.rating) { "Rating should be lowercase" }
            assertEquals("What is AI?", body.query) { "Query should match" }
            assertEquals("Great!", body.comment) { "Comment should match" }
        }

        @Test
        fun `invalid rating에 대해 return 400해야 한다`() = runTest {
            val request = SubmitFeedbackRequest(rating = "invalid_rating")
            val exception = assertThrows(ServerWebInputException::class.java) {
                controller.submitFeedback(request, noAuthExchange())
            }
            assertTrue(exception.reason?.contains("Invalid rating") == true) {
                "Invalid rating should produce ServerWebInputException with clear reason"
            }
        }

        @Test
        fun `runId provided일 때 auto-enrich from metadata해야 한다`() = runTest {
            val metadata = CapturedExecutionMetadata(
                runId = "run-42",
                userId = "user-7",
                userPrompt = "Cached question",
                agentResponse = "Cached answer",
                toolsUsed = listOf("calculator", "search"),
                durationMs = 1500,
                sessionId = "cached-session"
            )
            every { metadataCaptureHook.get("run-42") } returns metadata

            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }

            val request = SubmitFeedbackRequest(
                rating = "thumbs_down",
                runId = "run-42",
                comment = "Wrong answer"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201" }
            val saved = slot.captured
            assertEquals("Cached question", saved.query) { "Query should be auto-enriched from metadata" }
            assertEquals("Cached answer", saved.response) { "Response should be auto-enriched from metadata" }
            assertEquals(listOf("calculator", "search"), saved.toolsUsed) { "toolsUsed should be auto-enriched" }
            assertEquals(1500L, saved.durationMs) { "durationMs should be auto-enriched" }
            assertEquals("cached-session", saved.sessionId) { "sessionId should be auto-enriched from metadata" }
            assertNull(saved.userId) { "userId should be null when no authenticated user" }
        }

        @Test
        fun `prefer explicit values over metadata해야 한다`() = runTest {
            val metadata = CapturedExecutionMetadata(
                runId = "run-1",
                userId = "meta-user",
                userPrompt = "Meta question",
                agentResponse = "Meta answer",
                toolsUsed = listOf("meta-tool"),
                durationMs = 999,
                sessionId = "meta-session"
            )
            every { metadataCaptureHook.get("run-1") } returns metadata

            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }

            val request = SubmitFeedbackRequest(
                rating = "thumbs_up",
                runId = "run-1",
                query = "My explicit query",
                response = "My explicit response",
                toolsUsed = listOf("explicit-tool"),
                durationMs = 100,
                sessionId = "explicit-session"
            )
            controller.submitFeedback(request, noAuthExchange())

            val saved = slot.captured
            assertEquals("My explicit query", saved.query) { "Explicit query should take precedence" }
            assertEquals("My explicit response", saved.response) { "Explicit response should take precedence" }
            assertEquals(listOf("explicit-tool"), saved.toolsUsed) { "Explicit toolsUsed should take precedence" }
            assertEquals(100L, saved.durationMs) { "Explicit durationMs should take precedence" }
            assertEquals("explicit-session", saved.sessionId) { "Explicit sessionId should take precedence" }
            assertNull(saved.userId) { "userId should be null when no authenticated user" }
        }

        @Test
        fun `accept uppercase rating해야 한다`() = runTest {
            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }
            every { metadataCaptureHook.get(any()) } returns null

            val request = SubmitFeedbackRequest(
                rating = "THUMBS_DOWN",
                query = "test",
                response = "test"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should accept uppercase rating" }
            assertEquals(FeedbackRating.THUMBS_DOWN, slot.captured.rating) { "Should parse uppercase rating" }
        }

        @Test
        fun `surrounding spaces로 accept rating해야 한다`() = runTest {
            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }
            every { metadataCaptureHook.get(any()) } returns null

            val request = SubmitFeedbackRequest(
                rating = "  thumbs_up  ",
                query = "test",
                response = "test"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should accept trimmed rating input" }
            assertEquals(FeedbackRating.THUMBS_UP, slot.captured.rating) {
                "Rating should be parsed after trimming surrounding spaces"
            }
        }
    }

    @Nested
    inner class ListFeedback {

        @Test
        fun `admin에 대해 return feedback list해야 한다`() = runTest {
            every {
                feedbackStore.list(
                    rating = null, from = null, to = null,
                    intent = null, sessionId = null, templateId = null
                )
            } returns listOf(sampleFeedback())

            val response = controller.listFeedback(
                rating = null, from = null, to = null,
                intent = null, sessionId = null, templateId = null,
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            @Suppress("UNCHECKED_CAST")
            val body = response.body as PaginatedResponse<FeedbackResponse>
            assertEquals(1, body.items.size) { "Should have 1 entry" }
            assertEquals(1, body.total) { "Total should be 1" }
        }

        @Test
        fun `non-admin user에 대해 return 403해야 한다`() = runTest {
            val response = controller.listFeedback(
                rating = null, from = null, to = null,
                intent = null, sessionId = null, templateId = null,
                offset = 0, limit = 50,
                exchange = userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `pass filter parameters to store해야 한다`() = runTest {
            every {
                feedbackStore.list(
                    rating = FeedbackRating.THUMBS_DOWN,
                    from = Instant.parse("2026-01-01T00:00:00Z"),
                    to = Instant.parse("2026-02-01T00:00:00Z"),
                    intent = "refund",
                    sessionId = "s-42",
                    templateId = null
                )
            } returns emptyList()

            val response = controller.listFeedback(
                rating = "thumbs_down",
                from = "2026-01-01T00:00:00Z",
                to = "2026-02-01T00:00:00Z",
                intent = "refund",
                sessionId = "s-42",
                templateId = null,
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200 with filters" }
            verify(exactly = 1) {
                feedbackStore.list(
                    rating = FeedbackRating.THUMBS_DOWN,
                    from = Instant.parse("2026-01-01T00:00:00Z"),
                    to = Instant.parse("2026-02-01T00:00:00Z"),
                    intent = "refund",
                    sessionId = "s-42",
                    templateId = null
                )
            }
        }

        @Test
        fun `surrounding spaces로 parse rating filter해야 한다`() = runTest {
            every {
                feedbackStore.list(
                    rating = FeedbackRating.THUMBS_UP,
                    from = null,
                    to = null,
                    intent = null,
                    sessionId = null,
                    templateId = null
                )
            } returns emptyList()

            val response = controller.listFeedback(
                rating = "  thumbs_up  ",
                from = null,
                to = null,
                intent = null,
                sessionId = null,
                templateId = null,
                offset = 0, limit = 50,
                exchange = adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Trimmed rating filter should be accepted" }
            verify(exactly = 1) {
                feedbackStore.list(
                    rating = FeedbackRating.THUMBS_UP,
                    from = null,
                    to = null,
                    intent = null,
                    sessionId = null,
                    templateId = null
                )
            }
        }

        @Test
        fun `role is missing일 때 reject list해야 한다`() = runTest {
            every {
                feedbackStore.list(
                    rating = null, from = null, to = null,
                    intent = null, sessionId = null, templateId = null
                )
            } returns emptyList()

            val response = controller.listFeedback(
                rating = null, from = null, to = null,
                intent = null, sessionId = null, templateId = null,
                offset = 0, limit = 50,
                exchange = noAuthExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Missing role should be rejected"
            }
        }
    }

    @Nested
    inner class ExportFeedback {

        @Test
        fun `admin에 대해 return export in eval-testing format해야 한다`() = runTest {
            every { feedbackStore.list() } returns listOf(sampleFeedback())

            val response = controller.exportFeedback(adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any?>
            assertEquals(1, body["version"]) { "Version should be 1" }
            assertEquals("arc-reactor", body["source"]) { "Source should be arc-reactor" }
            assertNotNull(body["exportedAt"]) { "exportedAt should be present" }
            @Suppress("UNCHECKED_CAST")
            val items = body["items"] as List<Map<String, Any?>>
            assertEquals(1, items.size) { "Should have 1 item" }
            assertEquals("thumbs_up", items[0]["rating"]) { "Rating should be lowercase in export" }
        }

        @Test
        fun `non-admin user에 대해 return 403해야 한다`() = runTest {
            val response = controller.exportFeedback(userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }
    }

    @Nested
    inner class GetFeedback {

        @Test
        fun `return feedback by ID해야 한다`() = runTest {
            every { feedbackStore.get("fb-1") } returns sampleFeedback()

            val response = controller.getFeedback("fb-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body!! as FeedbackResponse
            assertEquals("fb-1", body.feedbackId) { "feedbackId should match" }
            assertEquals("thumbs_up", body.rating) { "Rating should be lowercase" }
            assertEquals(now.toString(), body.timestamp) { "Timestamp should be ISO 8601" }
        }

        @Test
        fun `nonexistent feedback에 대해 return 404해야 한다`() = runTest {
            every { feedbackStore.get("nonexistent") } returns null

            val response = controller.getFeedback("nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404 for missing feedback" }
        }
    }

    @Nested
    inner class DeleteFeedback {

        @Test
        fun `admin에 대해 return 204 on successful deletion해야 한다`() = runTest {
            every { feedbackStore.delete("fb-1") } returns Unit

            val response = controller.deleteFeedback("fb-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204 No Content" }
        }

        @Test
        fun `non-admin user에 대해 return 403해야 한다`() = runTest {
            val response = controller.deleteFeedback("fb-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `correct feedbackId로 call store delete해야 한다`() = runTest {
            every { feedbackStore.delete(any()) } returns Unit

            controller.deleteFeedback("target-fb", adminExchange())

            verify(exactly = 1) { feedbackStore.delete("target-fb") }
        }
    }

    @Nested
    inner class InvalidTimestampHandling {

        @Test
        fun `invalid from timestamp에 대해 return 400해야 한다`() = runTest {
            val exception = assertThrows(ServerWebInputException::class.java) {
                controller.listFeedback(
                    rating = null,
                    from = "not-a-timestamp",
                    to = null,
                    intent = null,
                    sessionId = null,
                    templateId = null,
                    offset = 0, limit = 50,
                    exchange = adminExchange()
                )
            }
            assertTrue(exception.reason?.contains("Invalid timestamp for 'from'") == true) {
                "Unparseable from timestamp should raise clear input exception"
            }
        }

        @Test
        fun `invalid to timestamp에 대해 return 400해야 한다`() = runTest {
            val exception = assertThrows(ServerWebInputException::class.java) {
                controller.listFeedback(
                    rating = null,
                    from = null,
                    to = "2026-13-45T99:99:99Z",
                    intent = null,
                    sessionId = null,
                    templateId = null,
                    offset = 0, limit = 50,
                    exchange = adminExchange()
                )
            }
            assertTrue(exception.reason?.contains("Invalid timestamp for 'to'") == true) {
                "Unparseable to timestamp should raise clear input exception"
            }
        }

        @Test
        fun `invalid rating in list에 대해 return 400해야 한다`() = runTest {
            val exception = assertThrows(ServerWebInputException::class.java) {
                controller.listFeedback(
                    rating = "five_stars",
                    from = null,
                    to = null,
                    intent = null,
                    sessionId = null,
                    templateId = null,
                    offset = 0, limit = 50,
                    exchange = adminExchange()
                )
            }
            assertTrue(exception.reason?.contains("Invalid rating") == true) {
                "Invalid rating filter should raise clear input exception"
            }
        }
    }

    @Nested
    inner class MetadataFallback {

        @Test
        fun `runId has no cached metadata일 때 use empty defaults해야 한다`() = runTest {
            every { metadataCaptureHook.get("expired-run") } returns null

            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }

            val request = SubmitFeedbackRequest(
                rating = "thumbs_down",
                runId = "expired-run",
                comment = "Bad answer"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should still create feedback" }
            assertEquals("", slot.captured.query) { "Query should be empty when no metadata and no explicit value" }
            assertEquals("", slot.captured.response) { "Response should be empty when no metadata and no explicit value" }
        }

        @Test
        fun `work without runId at all해야 한다`() = runTest {
            val slot = slot<Feedback>()
            every { feedbackStore.save(capture(slot)) } answers { slot.captured }

            val request = SubmitFeedbackRequest(
                rating = "thumbs_up",
                query = "Direct query",
                response = "Direct response"
            )
            val response = controller.submitFeedback(request, noAuthExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should create feedback without runId" }
            assertEquals("Direct query", slot.captured.query) { "Query should come from request" }
            assertNull(slot.captured.runId) { "runId should be null" }
        }
    }
}
