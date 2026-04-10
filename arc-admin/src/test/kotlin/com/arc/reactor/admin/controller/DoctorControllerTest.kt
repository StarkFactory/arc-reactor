package com.arc.reactor.admin.controller

import com.arc.reactor.auth.UserRole
import com.arc.reactor.diagnostics.DoctorCheck
import com.arc.reactor.diagnostics.DoctorDiagnostics
import com.arc.reactor.diagnostics.DoctorReport
import com.arc.reactor.diagnostics.DoctorSection
import com.arc.reactor.diagnostics.DoctorStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * [DoctorController] REST 엔드포인트 테스트.
 *
 * R237: 인증, 상태별 HTTP 코드, summary endpoint, fail-safe 동작 검증.
 */
class DoctorControllerTest {

    private val doctor = mockk<DoctorDiagnostics>()
    private val controller = DoctorController(doctor)

    /**
     * R244: 테스트 헬퍼 업데이트 — DoctorController가 `resolveFormat()`에서
     * `exchange.request.headers.getFirst(ACCEPT)`를 호출하므로 request 경로도 mock한다.
     * 기본은 빈 Accept 헤더 → JSON 포맷.
     */
    private fun exchangeWithRole(
        role: UserRole?,
        acceptHeader: String? = null
    ): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attributes = mutableMapOf<String, Any>()
        if (role != null) {
            attributes["userRole"] = role
        }
        every { exchange.attributes } returns attributes

        val request = mockk<ServerHttpRequest>()
        val headers = HttpHeaders()
        if (acceptHeader != null) {
            headers.add(HttpHeaders.ACCEPT, acceptHeader)
        }
        every { request.headers } returns headers
        every { exchange.request } returns request

        return exchange
    }

    private fun reportWithStatus(vararg sectionStatuses: DoctorStatus): DoctorReport {
        val sections = sectionStatuses.mapIndexed { index, status ->
            DoctorSection(
                name = "Section $index",
                status = status,
                checks = listOf(
                    DoctorCheck("check", status, "detail")
                ),
                message = "msg"
            )
        }
        return DoctorReport(
            generatedAt = Instant.parse("2026-04-11T11:00:00Z"),
            sections = sections
        )
    }

    @Nested
    inner class Authentication {

        @Test
        fun `GET report는 USER 역할에 403을 반환해야 한다`() {
            val response = controller.report(exchangeWithRole(UserRole.USER))
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
            val body = response.body as? AdminErrorResponse
            assertNotNull(body)
            assertEquals("Admin access required", body!!.error)
        }

        @Test
        fun `GET report는 역할 없는 요청에 403을 반환해야 한다`() {
            val response = controller.report(exchangeWithRole(null))
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        }

        @Test
        fun `GET summary도 USER 역할에 403을 반환해야 한다`() {
            val response = controller.summary(exchangeWithRole(UserRole.USER))
            assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        }

        @Test
        fun `ADMIN 역할은 report에 접근할 수 있어야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode)
        }

        @Test
        fun `ADMIN_DEVELOPER 역할도 report에 접근할 수 있어야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(exchangeWithRole(UserRole.ADMIN_DEVELOPER))
            assertEquals(HttpStatus.OK, response.statusCode)
        }
    }

    @Nested
    inner class ReportEndpoint {

        @Test
        fun `모든 섹션 OK이면 200과 X-Doctor-Status OK 헤더를 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.OK,
                DoctorStatus.OK
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("OK", response.headers.getFirst(DoctorController.STATUS_HEADER))
            val body = response.body as DoctorReport
            assertTrue(body.allHealthy())
        }

        @Test
        fun `SKIPPED 섹션이 섞여도 allHealthy면 OK 헤더를 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.SKIPPED,
                DoctorStatus.SKIPPED
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("OK", response.headers.getFirst(DoctorController.STATUS_HEADER))
        }

        @Test
        fun `WARN 섹션이 있으면 200과 WARN 헤더를 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.WARN
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode) {
                "WARN은 여전히 200 반환"
            }
            assertEquals("WARN", response.headers.getFirst(DoctorController.STATUS_HEADER))
        }

        @Test
        fun `ERROR 섹션이 있으면 500과 ERROR 헤더를 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.ERROR
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
            assertEquals("ERROR", response.headers.getFirst(DoctorController.STATUS_HEADER))
        }

        @Test
        fun `WARN과 ERROR가 모두 있으면 ERROR가 우선되어야 한다 (500)`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.WARN,
                DoctorStatus.ERROR
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
            assertEquals("ERROR", response.headers.getFirst(DoctorController.STATUS_HEADER))
        }

        @Test
        fun `report body에 전체 sections 리스트가 포함되어야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.WARN
            )
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))
            val body = response.body as DoctorReport
            assertEquals(2, body.sections.size)
        }
    }

    @Nested
    inner class SummaryEndpoint {

        @Test
        fun `summary는 summary 문자열과 status를 포함한 맵을 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.OK
            )
            val response = controller.summary(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            assertNotNull(body["summary"]) { "summary 포함" }
            assertEquals("OK", body["status"])
            assertEquals(true, body["allHealthy"])
            assertNotNull(body["generatedAt"])
        }

        @Test
        fun `WARN 상태도 summary에서 200을 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.WARN
            )
            val response = controller.summary(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.OK, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            assertEquals("WARN", body["status"])
            assertEquals(false, body["allHealthy"])
        }

        @Test
        fun `ERROR 상태는 summary에서 500을 반환해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.ERROR
            )
            val response = controller.summary(exchangeWithRole(UserRole.ADMIN))
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            assertEquals("ERROR", body["status"])
        }

        @Test
        fun `summary 헤더는 X-Doctor-Status를 포함해야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.summary(exchangeWithRole(UserRole.ADMIN))
            assertEquals("OK", response.headers.getFirst(DoctorController.STATUS_HEADER))
        }
    }

    @Nested
    inner class FailSafeBehavior {

        @Test
        fun `DoctorDiagnostics 예외 시에도 500과 ERROR 보고서를 반환해야 한다`() {
            every { doctor.runDiagnostics() } throws RuntimeException("simulated failure")
            val response = controller.report(exchangeWithRole(UserRole.ADMIN))

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) {
                "예외 시 500 반환"
            }
            assertEquals("ERROR", response.headers.getFirst(DoctorController.STATUS_HEADER))

            val body = response.body as DoctorReport
            assertEquals(1, body.sections.size)
            val section = body.sections[0]
            assertEquals(DoctorStatus.ERROR, section.status)
            assertTrue(section.name == "Doctor Diagnostics")
            assertTrue(section.checks.first().detail.contains("simulated failure"))
        }

        @Test
        fun `summary endpoint도 예외 처리가 되어야 한다`() {
            every { doctor.runDiagnostics() } throws IllegalStateException("broken")
            val response = controller.summary(exchangeWithRole(UserRole.ADMIN))

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
            @Suppress("UNCHECKED_CAST")
            val body = response.body as Map<String, Any>
            assertEquals("ERROR", body["status"])
        }
    }

    @Nested
    inner class HeaderConstants {

        @Test
        fun `STATUS_HEADER는 X-Doctor-Status여야 한다`() {
            assertEquals("X-Doctor-Status", DoctorController.STATUS_HEADER)
        }

        @Test
        fun `STATUS 상수는 OK WARN ERROR여야 한다`() {
            assertEquals("OK", DoctorController.STATUS_OK)
            assertEquals("WARN", DoctorController.STATUS_WARN)
            assertEquals("ERROR", DoctorController.STATUS_ERROR)
        }

        @Test
        fun `TEXT_MARKDOWN_VALUE 상수는 text-markdown이어야 한다 (R244)`() {
            assertEquals("text/markdown", DoctorController.TEXT_MARKDOWN_VALUE)
        }
    }

    @Nested
    inner class ContentNegotiation {

        @Test
        fun `Accept 헤더 없음은 JSON 반환 (backward compat)`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(exchangeWithRole(UserRole.ADMIN, acceptHeader = null))

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(
                MediaType.APPLICATION_JSON,
                response.headers.contentType
            ) {
                "Accept 헤더 없으면 JSON 기본"
            }
            assertTrue(response.body is DoctorReport) {
                "body는 DoctorReport 객체여야 한다"
            }
        }

        @Test
        fun `Accept application-json은 JSON 반환`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "application/json")
            )

            assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
            assertTrue(response.body is DoctorReport)
        }

        @Test
        fun `Accept text-plain은 한국어 멀티라인 텍스트 반환 (R239 toHumanReadable)`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "text/plain")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(MediaType.TEXT_PLAIN, response.headers.contentType) {
                "Content-Type은 text/plain이어야 한다"
            }
            val body = response.body as String
            assertTrue(body.contains("=== Arc Reactor Doctor Report ===")) {
                "toHumanReadable 헤더 포함"
            }
            assertTrue(body.contains("전체 상태: 정상")) {
                "한국어 overall 라벨 포함"
            }
        }

        @Test
        fun `Accept text-markdown은 Slack mrkdwn 포맷 반환 (R239 toSlackMarkdown)`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "text/markdown")
            )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(
                "text/markdown",
                response.headers.contentType?.toString()
            ) {
                "Content-Type은 text/markdown이어야 한다"
            }
            val body = response.body as String
            assertTrue(body.contains("*Arc Reactor Doctor Report*")) {
                "Slack bold 타이틀 포함"
            }
        }

        @Test
        fun `Accept text-x-markdown도 markdown으로 인식`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "text/x-markdown")
            )

            assertEquals("text/markdown", response.headers.contentType?.toString()) {
                "text/x-markdown은 TEXT_MARKDOWN으로 라우팅"
            }
        }

        @Test
        fun `Accept wildcard는 JSON 기본 반환`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "*/*")
            )

            assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType) {
                "wildcard는 JSON 기본"
            }
            assertTrue(response.body is DoctorReport)
        }

        @Test
        fun `Accept 여러 타입 중 text-markdown이 우선 매칭`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(
                    UserRole.ADMIN,
                    acceptHeader = "application/json, text/markdown, text/plain"
                )
            )

            assertEquals("text/markdown", response.headers.contentType?.toString()) {
                "markdown이 우선순위 1"
            }
        }

        @Test
        fun `Accept 여러 타입 중 text-plain이 두 번째 우선 매칭`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(
                    UserRole.ADMIN,
                    acceptHeader = "application/json, text/plain"
                )
            )

            assertEquals(MediaType.TEXT_PLAIN, response.headers.contentType) {
                "markdown 없으면 text/plain 우선"
            }
        }

        @Test
        fun `quality factor가 있는 Accept 헤더도 파싱되어야 한다`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(
                    UserRole.ADMIN,
                    acceptHeader = "text/plain;q=0.9, application/json;q=0.8"
                )
            )

            assertEquals(MediaType.TEXT_PLAIN, response.headers.contentType) {
                "q= 파라미터는 무시하고 순서대로 매칭"
            }
        }

        @Test
        fun `알 수 없는 미디어 타입은 JSON 기본으로 fallback`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.OK)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "application/xml")
            )

            assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType) {
                "알 수 없는 타입 → JSON fallback"
            }
        }

        @Test
        fun `text-plain 응답도 ERROR 시 HTTP 500과 올바른 Content-Type을 유지`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(DoctorStatus.ERROR)
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "text/plain")
            )

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) {
                "ERROR는 여전히 500"
            }
            assertEquals(MediaType.TEXT_PLAIN, response.headers.contentType) {
                "ERROR + text/plain 조합도 Content-Type 유지"
            }
            assertEquals("ERROR", response.headers.getFirst(DoctorController.STATUS_HEADER))
            val body = response.body as String
            assertTrue(body.contains("전체 상태: 오류 포함")) {
                "한국어 오류 라벨 포함"
            }
        }

        @Test
        fun `text-markdown WARN 응답도 200과 WARN 헤더 유지`() {
            every { doctor.runDiagnostics() } returns reportWithStatus(
                DoctorStatus.OK,
                DoctorStatus.WARN
            )
            val response = controller.report(
                exchangeWithRole(UserRole.ADMIN, acceptHeader = "text/markdown")
            )

            assertEquals(HttpStatus.OK, response.statusCode) {
                "WARN은 200 유지"
            }
            assertEquals("WARN", response.headers.getFirst(DoctorController.STATUS_HEADER))
            assertEquals("text/markdown", response.headers.contentType?.toString())
        }
    }
}
