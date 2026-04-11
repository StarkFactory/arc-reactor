package com.arc.reactor.controller

import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.auth.UserStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * arc-web 커버리지 갭 보완 테스트.
 *
 * 이 파일은 기존 테스트에서 누락된 엣지 케이스를 보강한다:
 * - AuthController.changePassword (전혀 테스트되지 않음)
 * - AuthController 누락 경로 (non-Bearer 로그아웃, me 404 등)
 * - GlobalExceptionHandler 누락 핸들러 (FileSizeLimitException, CancellationException)
 */
class WebCoverageGapTest {

    // ─── AuthController 공통 픽스처 ───────────────────────────────────────────

    private lateinit var authProvider: DefaultAuthProvider
    private lateinit var userStore: UserStore
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var tokenRevocationStore: TokenRevocationStore
    private lateinit var controller: AuthController

    @BeforeEach
    fun setup() {
        authProvider = mockk()
        userStore = mockk()
        jwtTokenProvider = mockk()
        tokenRevocationStore = mockk(relaxed = true)
        controller = AuthController(
            authProvider = authProvider,
            userStore = userStore,
            jwtTokenProvider = jwtTokenProvider,
            authProperties = AuthProperties(selfRegistrationEnabled = true),
            tokenRevocationStore = tokenRevocationStore,
            iamTokenExchangeService = null
        )
    }

    private fun exchangeWithUserId(userId: String): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to userId
        )
        return exchange
    }

    private fun exchangeWithoutUserId(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }

    private fun exchangeWithAuthHeader(headerValue: String): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val request = mockk<ServerHttpRequest>()
        val headers = HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, headerValue) }
        every { exchange.request } returns request
        every { request.headers } returns headers
        return exchange
    }

    // ─── AuthController.changePassword ────────────────────────────────────────

    @Nested
    inner class ChangePassword {

        private val existingUser = User(
            id = "user-42",
            email = "tony@stark.com",
            name = "Tony Stark",
            passwordHash = "old-hash"
        )

        @Test
        fun `성공적인 비밀번호 변경 시 200을 반환해야 한다`() {
            every { authProvider.getUserById("user-42") } returns existingUser
            every { authProvider.authenticate(existingUser.email, "correct-old-pw") } returns existingUser
            every { authProvider.hashPassword("newPassword!1") } returns "new-hash"
            every { userStore.update(any()) } answers { firstArg() }

            val response = controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "correct-old-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithUserId("user-42")
            )

            assertEquals(HttpStatus.OK, response.statusCode) {
                "비밀번호 변경 성공 시 HTTP 200 OK를 반환해야 한다"
            }
            verify(exactly = 1) { userStore.update(any()) }
        }

        @Test
        fun `현재 비밀번호가 틀리면 400을 반환해야 한다`() {
            every { authProvider.getUserById("user-42") } returns existingUser
            every { authProvider.authenticate(existingUser.email, "wrong-old-pw") } returns null

            val response = controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "wrong-old-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithUserId("user-42")
            )

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "현재 비밀번호가 틀리면 HTTP 400 Bad Request를 반환해야 한다"
            }
            val body = response.body as ErrorResponse
            assertTrue(body.error.contains("incorrect", ignoreCase = true)) {
                "오류 메시지에 'incorrect'가 포함되어야 한다. 실제: ${body.error}"
            }
            verify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `사용자를 찾을 수 없으면 404를 반환해야 한다`() {
            every { authProvider.getUserById("unknown-user") } returns null

            val response = controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "any-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithUserId("unknown-user")
            )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "존재하지 않는 사용자의 비밀번호 변경 요청은 HTTP 404 Not Found여야 한다"
            }
            verify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `JWT가 없으면 401을 반환해야 한다`() {
            val response = controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "any-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithoutUserId()
            )

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "인증 없이 비밀번호 변경을 시도하면 HTTP 401 Unauthorized여야 한다"
            }
            verify(exactly = 0) { authProvider.getUserById(any()) }
            verify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `커스텀 AuthProvider 사용 시 비밀번호 변경은 400을 반환해야 한다`() {
            val customAuthProvider = mockk<com.arc.reactor.auth.AuthProvider>()
            val customController = AuthController(
                authProvider = customAuthProvider,
                userStore = userStore,
                jwtTokenProvider = jwtTokenProvider,
                authProperties = AuthProperties(selfRegistrationEnabled = true),
                tokenRevocationStore = tokenRevocationStore,
                iamTokenExchangeService = null
            )

            every { customAuthProvider.getUserById("user-42") } returns existingUser
            every { customAuthProvider.authenticate(existingUser.email, "correct-old-pw") } returns existingUser

            val response = customController.changePassword(
                ChangePasswordRequest(
                    currentPassword = "correct-old-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithUserId("user-42")
            )

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "커스텀 AuthProvider에서 비밀번호 변경은 지원되지 않으므로 HTTP 400이어야 한다"
            }
            val body = response.body as ErrorResponse
            assertTrue(body.error.contains("not supported", ignoreCase = true)) {
                "오류 메시지에 'not supported'가 포함되어야 한다. 실제: ${body.error}"
            }
            verify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `비밀번호 변경 성공 후 갱신된 해시로 사용자 정보가 저장되어야 한다`() {
            every { authProvider.getUserById("user-42") } returns existingUser
            every { authProvider.authenticate(existingUser.email, "correct-old-pw") } returns existingUser
            every { authProvider.hashPassword("newPassword!1") } returns "hashed-new-pw"
            var savedUser: User? = null
            every { userStore.update(any()) } answers {
                savedUser = firstArg()
                firstArg()
            }

            controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "correct-old-pw",
                    newPassword = "newPassword!1"
                ),
                exchangeWithUserId("user-42")
            )

            assertNotNull(savedUser) { "userStore.update가 정확히 1회 호출되어야 한다" }
            assertEquals("hashed-new-pw", savedUser!!.passwordHash) {
                "저장된 사용자의 passwordHash가 새로 해싱된 값으로 업데이트되어야 한다"
            }
            assertEquals("user-42", savedUser!!.id) {
                "비밀번호 변경 후 사용자 ID가 유지되어야 한다"
            }
        }
    }

    // ─── AuthController.logout 엣지 케이스 ────────────────────────────────────

    @Nested
    inner class LogoutEdgeCases {

        @Test
        fun `Bearer 접두사가 없는 Authorization 헤더는 401을 반환해야 한다`() {
            val response = controller.logout(exchangeWithAuthHeader("Token some-token"))

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "Bearer 접두사 없이 로그아웃 시도하면 HTTP 401 Unauthorized여야 한다"
            }
        }

        @Test
        fun `tokenId가 null이면 revoke를 호출하지 않고 401을 반환해야 한다`() {
            // R323: 폐기 불가능한 토큰을 200 OK로 응답하면 클라이언트가 잘못된 로그아웃 확인을
            // 받으므로, 명시적으로 401을 반환해야 한다. JwtAuthWebFilter의 jti 거부와 대칭.
            every { jwtTokenProvider.extractTokenId("valid-but-no-jti") } returns null
            every { jwtTokenProvider.extractExpiration("valid-but-no-jti") } returns Instant.now().plusSeconds(3600)

            val response = controller.logout(exchangeWithAuthHeader("Bearer valid-but-no-jti"))

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "R323: tokenId가 null인 토큰은 폐기 불가능하므로 401을 반환해야 한다"
            }
            verify(exactly = 0) { tokenRevocationStore.revoke(any(), any()) }
        }

        @Test
        fun `만료시간이 null이면 revoke를 호출하지 않고 401을 반환해야 한다`() {
            // R323: exp claim이 없으면 revoke TTL을 계산할 수 없으므로 401 반환.
            every { jwtTokenProvider.extractTokenId("valid-token") } returns "jti-999"
            every { jwtTokenProvider.extractExpiration("valid-token") } returns null

            val response = controller.logout(exchangeWithAuthHeader("Bearer valid-token"))

            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode) {
                "R323: 만료시간이 null인 토큰은 폐기할 수 없으므로 401을 반환해야 한다"
            }
            verify(exactly = 0) { tokenRevocationStore.revoke(any(), any()) }
        }
    }

    // ─── AuthController.me 엣지 케이스 ────────────────────────────────────────

    @Nested
    inner class MeEndpointEdgeCases {

        @Test
        fun `userId는 존재하지만 사용자를 DB에서 찾을 수 없으면 404를 반환해야 한다`() {
            every { authProvider.getUserById("deleted-user") } returns null

            val response = controller.me(exchangeWithUserId("deleted-user"))

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "DB에 존재하지 않는 사용자의 /me 요청은 HTTP 404 Not Found여야 한다"
            }
            // 컨트롤러는 notFoundResponse("User not found")를 반환하므로 바디에 오류 메시지 포함
            assertNotNull(response.body) { "404 응답에 오류 설명 바디가 포함되어야 한다" }
        }

        @Test
        fun `ADMIN 역할 사용자의 adminScope가 정확하게 반환되어야 한다`() {
            val adminUser = User(
                id = "admin-1",
                email = "admin@example.com",
                name = "Admin User",
                passwordHash = "hash",
                role = UserRole.ADMIN
            )
            every { authProvider.getUserById("admin-1") } returns adminUser

            val response = controller.me(exchangeWithUserId("admin-1"))

            assertEquals(HttpStatus.OK, response.statusCode) { "ADMIN 사용자의 /me 요청은 200 OK여야 한다" }
            val body = response.body as UserResponse
            assertEquals("ADMIN", body.role) { "역할이 ADMIN이어야 한다" }
            assertNotNull(body.adminScope) { "ADMIN 역할은 adminScope가 null이 아니어야 한다" }
        }
    }

    // ─── GlobalExceptionHandler 누락 핸들러 ────────────────────────────────────

    @Nested
    inner class GlobalExceptionHandlerGaps {

        private val handler = GlobalExceptionHandler()

        @Test
        fun `FileSizeLimitException은 400 Bad Request를 반환해야 한다`() {
            val ex = FileSizeLimitException("파일 크기가 10MB 제한을 초과했습니다")

            val response = handler.handleFileSizeLimit(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "FileSizeLimitException은 HTTP 400 Bad Request를 반환해야 한다"
            }
            assertNotNull(response.body) { "응답 바디가 null이 아니어야 한다" }
            assertEquals("입력 길이가 제한을 초과했습니다", response.body!!.error) {
                "FileSizeLimitException의 에러 메시지는 한글 마스킹 메시지여야 한다"
            }
            assertNotNull(response.body!!.timestamp) { "응답에 타임스탬프가 포함되어야 한다" }
        }

        @Test
        fun `FileSizeLimitException은 내부 파일 크기 정보를 클라이언트에 노출하지 않아야 한다`() {
            val ex = FileSizeLimitException("File secret_data.zip exceeds size limit of 52428800B")

            val response = handler.handleFileSizeLimit(ex)

            assertTrue(!response.body!!.error.contains("secret_data.zip")) {
                "파일 이름이 응답에 노출되어서는 안 된다"
            }
            assertTrue(!response.body!!.error.contains("52428800")) {
                "파일 크기 제한값이 응답에 노출되어서는 안 된다"
            }
        }

        @Test
        fun `CancellationException은 503 Service Unavailable을 반환해야 한다`() {
            val ex = CancellationException("요청이 취소되었습니다")

            val response = handler.handleCancellation(ex)

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode) {
                "CancellationException은 HTTP 503 Service Unavailable을 반환해야 한다"
            }
            assertNull(response.body) {
                "CancellationException 처리 시 바디가 없는 503 응답을 반환해야 한다"
            }
        }

        @Test
        fun `handleGenericException은 CancellationException을 재전파해야 한다`() {
            val ex = CancellationException("코루틴 취소")

            var thrown: Throwable? = null
            try {
                handler.handleGenericException(ex)
            } catch (e: CancellationException) {
                thrown = e
            }

            assertNotNull(thrown) {
                "handleGenericException은 CancellationException을 catch하지 않고 재전파해야 한다"
            }
            assertTrue(thrown is CancellationException) {
                "재전파된 예외가 CancellationException 타입이어야 한다"
            }
        }

        @Test
        fun `ResponseStatusException 400은 올바른 한글 메시지를 반환해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.BAD_REQUEST, "some internal reason")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode) {
                "400 상태 코드가 보존되어야 한다"
            }
            assertEquals("잘못된 요청입니다", response.body!!.error) {
                "400에 대한 한글 마스킹 메시지가 '잘못된 요청입니다'여야 한다"
            }
            assertTrue(!response.body!!.error.contains("some internal reason")) {
                "내부 reason이 클라이언트에 노출되어서는 안 된다"
            }
        }

        @Test
        fun `ResponseStatusException 403은 올바른 한글 메시지를 반환해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "403 상태 코드가 보존되어야 한다"
            }
            assertEquals("접근이 거부되었습니다", response.body!!.error) {
                "403에 대한 한글 마스킹 메시지가 '접근이 거부되었습니다'여야 한다"
            }
        }

        @Test
        fun `ResponseStatusException 409는 올바른 한글 메시지를 반환해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.CONFLICT, "conflict")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "409 상태 코드가 보존되어야 한다"
            }
            assertEquals("요청이 충돌합니다", response.body!!.error) {
                "409에 대한 한글 마스킹 메시지가 '요청이 충돌합니다'여야 한다"
            }
        }

        @Test
        fun `ResponseStatusException 429는 올바른 한글 메시지를 반환해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limited")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode) {
                "429 상태 코드가 보존되어야 한다"
            }
            assertEquals("요청이 너무 많습니다", response.body!!.error) {
                "429에 대한 한글 마스킹 메시지가 '요청이 너무 많습니다'여야 한다"
            }
        }

        @Test
        fun `ResponseStatusException 5xx는 서버 오류 한글 메시지를 반환해야 한다`() {
            val ex = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "db error")

            val response = handler.handleResponseStatusException(ex)

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode) {
                "500 상태 코드가 보존되어야 한다"
            }
            assertEquals("서버 오류가 발생했습니다", response.body!!.error) {
                "5xx에 대한 한글 마스킹 메시지가 '서버 오류가 발생했습니다'여야 한다"
            }
        }
    }
}
