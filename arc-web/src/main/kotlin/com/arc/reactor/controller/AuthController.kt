package com.arc.reactor.controller

import com.arc.reactor.auth.AuthProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import com.arc.reactor.auth.DefaultAuthProvider
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserStore
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/**
 * Authentication API Controller
 *
 * Provides user registration, login, and profile endpoints.
 * Only registered as a bean when `arc.reactor.auth.enabled=true`.
 *
 * ## Endpoints
 * - POST /api/auth/register : Create a new account and receive JWT
 * - POST /api/auth/login    : Authenticate and receive JWT
 * - GET  /api/auth/me       : Get current user profile (requires token)
 */
@Tag(name = "Authentication", description = "JWT authentication (requires arc.reactor.auth.enabled=true)")
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "arc.reactor.auth", name = ["enabled"], havingValue = "true")
class AuthController(
    private val authProvider: AuthProvider,
    private val userStore: UserStore,
    private val jwtTokenProvider: JwtTokenProvider
) {

    /**
     * Register a new user account.
     */
    @Operation(summary = "Register a new user account and receive JWT")
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<AuthResponse> {
        if (userStore.existsByEmail(request.email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(AuthResponse(token = "", user = null, error = "Email already registered"))
        }

        val passwordHash = when (authProvider) {
            is DefaultAuthProvider -> authProvider.hashPassword(request.password)
            else -> throw UnsupportedOperationException(
                "Custom AuthProvider does not support registration via this endpoint"
            )
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

    /**
     * Login with email and password.
     */
    @Operation(summary = "Authenticate with email and password, receive JWT")
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<AuthResponse> {
        val user = authProvider.authenticate(request.email, request.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(AuthResponse(token = "", user = null, error = "Invalid email or password"))

        val token = jwtTokenProvider.createToken(user)
        return ResponseEntity.ok(AuthResponse(token = token, user = user.toResponse()))
    }

    /**
     * Get the current authenticated user's profile.
     */
    @Operation(summary = "Get current user profile (requires JWT)")
    @GetMapping("/me")
    fun me(exchange: ServerWebExchange): ResponseEntity<UserResponse> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val user = authProvider.getUserById(userId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(user.toResponse())
    }

    /**
     * Change the current user's password.
     */
    @Operation(summary = "Change password for the current user (requires JWT)")
    @PostMapping("/change-password")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        val userId = exchange.attributes[JwtAuthWebFilter.USER_ID_ATTRIBUTE] as? String
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val user = authProvider.getUserById(userId)
            ?: return ResponseEntity.notFound().build()

        // Verify current password
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
}

// --- Request DTOs ---

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

data class ChangePasswordRequest(
    @field:NotBlank(message = "Current password must not be blank")
    val currentPassword: String,

    @field:Size(min = 8, message = "New password must be at least 8 characters")
    @field:NotBlank(message = "New password must not be blank")
    val newPassword: String
)

// --- Response DTOs ---

data class AuthResponse(
    val token: String,
    val user: UserResponse?,
    val error: String? = null
)

data class UserResponse(
    val id: String,
    val email: String,
    val name: String,
    val role: String
)

// --- Mapping ---

private fun User.toResponse() = UserResponse(
    id = id,
    email = email,
    name = name,
    role = role.name
)
