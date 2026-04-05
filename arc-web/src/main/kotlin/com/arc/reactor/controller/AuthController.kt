package com.arc.reactor.controller

import com.arc.reactor.auth.AuthProvider
import com.arc.reactor.auth.AuthProperties
import com.arc.reactor.auth.IamTokenExchangeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.TokenRevocationStore
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserStore
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/**
 * 인증 컨트롤러.
 *
 * 사용자 등록, 로그인, 프로필 조회, 비밀번호 변경, 로그아웃을 제공합니다.
 *
 * ## 엔드포인트
 * - POST /api/auth/register        : 새 계정 생성 및 JWT 발급
 * - POST /api/auth/login           : 인증 후 JWT 발급
 * - GET  /api/auth/me              : 현재 사용자 프로필 조회 (JWT 필요)
 * - POST /api/auth/change-password : 비밀번호 변경 (JWT 필요)
 * - POST /api/auth/logout          : 현재 JWT 폐기
 *
 * @see AuthProvider
 * @see JwtTokenProvider
 */
@Tag(name = "Authentication", description = "JWT authentication")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authProvider: AuthProvider,
    private val userStore: UserStore,
    private val jwtTokenProvider: JwtTokenProvider,
    private val authProperties: AuthProperties,
    private val tokenRevocationStore: TokenRevocationStore,
    private val iamTokenExchangeService: IamTokenExchangeService?
) {

    /** 새 사용자 계정을 등록하고 JWT를 발급한다. */
    @Operation(summary = "새 사용자 등록 및 JWT 발급")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "User registered, JWT returned"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "403", description = "Self-registration disabled"),
        ApiResponse(responseCode = "409", description = "Email already registered")
    ])
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<AuthResponse> {
        if (!authProperties.selfRegistrationEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                AuthResponse(
                    token = "",
                    user = null,
                    error = "Self-registration is disabled. Contact an administrator."
                )
            )
        }
        if (userStore.existsByEmail(request.email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(AuthResponse(token = "", user = null, error = "Email already registered"))
        }

        val passwordHash = when (authProvider) {
            is DefaultAuthProvider -> authProvider.hashPassword(request.password)
            else -> {
                return ResponseEntity.badRequest().body(
                    AuthResponse(
                        token = "",
                        user = null,
                        error = "Self-registration is not supported with the configured AuthProvider."
                    )
                )
            }
        }

        val user = User(
            id = UUID.randomUUID().toString(),
            email = request.email,
            name = request.name,
            passwordHash = passwordHash
        )
        userStore.save(user)

        val token = jwtTokenProvider.createToken(user)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(AuthResponse(token = token, user = user.toResponse()))
    }

    /** 이메일과 비밀번호로 로그인하고 JWT를 발급한다. */
    @Operation(summary = "이메일/비밀번호 인증 후 JWT 발급")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "JWT token returned"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "401", description = "Invalid credentials")
    ])
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<AuthResponse> {
        val user = authProvider.authenticate(request.email, request.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(AuthResponse(token = "", user = null, error = "Invalid email or password"))

        val token = jwtTokenProvider.createToken(user)
        return ResponseEntity.ok(AuthResponse(token = token, user = user.toResponse()))
    }

    /** 현재 인증된 사용자의 프로필을 조회한다. */
    @Operation(summary = "현재 사용자 프로필 조회 (JWT 필요)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Current user profile"),
        ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
        ApiResponse(responseCode = "404", description = "User not found")
    ])
    @GetMapping("/me")
    fun me(exchange: ServerWebExchange): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val user = authProvider.getUserById(userId)
            ?: return notFoundResponse("User not found")

        return ResponseEntity.ok(user.toResponse())
    }

    /** 현재 사용자의 비밀번호를 변경한다. */
    @Operation(summary = "현재 사용자 비밀번호 변경 (JWT 필요)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Password changed successfully"),
        ApiResponse(responseCode = "400", description = "Current password incorrect or unsupported auth provider"),
        ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
        ApiResponse(responseCode = "404", description = "User not found")
    ])
    @PostMapping("/change-password")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val user = authProvider.getUserById(userId)
            ?: return notFoundResponse("User not found")

        // WHY: 현재 비밀번호를 먼저 검증하여 비인가 비밀번호 변경을 방지한다
        if (authProvider.authenticate(user.email, request.currentPassword) == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        error = "Current password is incorrect",
                        timestamp = java.time.Instant.now().toString()
                    )
                )
        }

        if (authProvider !is DefaultAuthProvider) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        error = "Password change not supported with custom AuthProvider",
                        timestamp = java.time.Instant.now().toString()
                    )
                )
        }

        val newHash = authProvider.hashPassword(request.newPassword)
        val updatedUser = user.copy(passwordHash = newHash)
        userStore.update(updatedUser)

        return ResponseEntity.ok(mapOf("message" to "Password changed successfully"))
    }

    /** 현재 JWT를 폐기하여 로그아웃한다. */
    @Operation(summary = "현재 JWT 폐기로 로그아웃")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Logout succeeded"),
        ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    ])
    @PostMapping("/logout")
    fun logout(exchange: ServerWebExchange): ResponseEntity<Any> {
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val token = authHeader.substring(7)
        val tokenId = jwtTokenProvider.extractTokenId(token)
        val expiresAt = jwtTokenProvider.extractExpiration(token)
        if (tokenId != null && expiresAt != null) {
            tokenRevocationStore.revoke(tokenId, expiresAt)
        }
        return ResponseEntity.ok(mapOf("message" to "Logged out"))
    }

    /** aslan-iam RS256 토큰을 arc-reactor HS256 토큰으로 교환한다. */
    @Operation(summary = "IAM 토큰을 arc-reactor 토큰으로 교환")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Token exchanged, arc-reactor JWT returned"),
        ApiResponse(responseCode = "400", description = "Missing or invalid IAM token"),
        ApiResponse(responseCode = "404", description = "IAM token exchange not enabled"),
        ApiResponse(responseCode = "401", description = "IAM token verification failed")
    ])
    @PostMapping("/exchange")
    fun exchange(@Valid @RequestBody request: TokenExchangeRequest): ResponseEntity<AuthResponse> {
        if (iamTokenExchangeService == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                AuthResponse(token = "", user = null, error = "IAM token exchange is not enabled")
            )
        }

        val result = iamTokenExchangeService.exchange(request.token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                AuthResponse(token = "", user = null, error = "IAM token verification failed")
            )

        return ResponseEntity.ok(AuthResponse(token = result.token, user = result.user.toResponse()))
    }
}

// --- 요청 DTO ---

data class RegisterRequest(
    @field:Email(message = "Invalid email format")
    @field:NotBlank(message = "Email must not be blank")
    val email: String,

    @field:Size(min = 8, message = "Password must be at least 8 characters")
    @field:NotBlank(message = "Password must not be blank")
    val password: String,

    @field:NotBlank(message = "Name must not be blank")
    val name: String
)

data class LoginRequest(
    @field:NotBlank(message = "Email must not be blank")
    val email: String,

    @field:NotBlank(message = "Password must not be blank")
    val password: String
)

data class TokenExchangeRequest(
    @field:NotBlank(message = "IAM token must not be blank")
    val token: String
)

data class ChangePasswordRequest(
    @field:NotBlank(message = "Current password must not be blank")
    val currentPassword: String,

    @field:Size(min = 8, message = "New password must be at least 8 characters")
    @field:NotBlank(message = "New password must not be blank")
    val newPassword: String
)

// --- 응답 DTO ---

data class AuthResponse(
    val token: String,
    val user: UserResponse?,
    val error: String? = null
)

data class UserResponse(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val adminScope: String?
)

// --- 매핑 ---

private fun User.toResponse() = UserResponse(
    id = id,
    email = email,
    name = name,
    role = role.name,
    adminScope = role.adminScope()?.name
)
