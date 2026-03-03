package com.arc.reactor.slack.tools.health

import com.arc.reactor.slack.tools.config.CanvasToolsProperties
import com.arc.reactor.slack.tools.config.ConversationScopeMode
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.arc.reactor.slack.tools.config.ToolExposureProperties
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlackApiHealthIndicatorTest {

    private val methodsClient = mockk<MethodsClient>()

    @Test
    fun `health is up when auth test succeeds and required scopes exist`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf(
                "x-oauth-scopes" to listOf(
                    "chat:write, channels:read, channels:history, users:read, users:read.email, reactions:write, files:write, search:read"
                )
            )
        )

        val health = indicator.health()

        assertEquals("UP", health.status.code)
        assertTrue((health.details["scopeCount"] as Int) >= 8)
    }

    @Test
    fun `health is down when required scopes are missing`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf("x-oauth-scopes" to listOf("chat:write,channels:read"))
        )

        val health = indicator.health()

        assertEquals("DOWN", health.status.code)
        assertEquals("missing_scopes", health.details["error"])
    }

    @Test
    fun `health is down when auth test fails`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = false,
            error = "invalid_auth",
            needed = "chat:write",
            provided = "none"
        )

        val health = indicator.health()

        assertEquals("DOWN", health.status.code)
        assertEquals("invalid_auth", health.details["error"])
    }

    @Test
    fun `health is down on auth test exception`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } throws RuntimeException("network error")

        val health = indicator.health()

        assertEquals("DOWN", health.status.code)
        assertEquals("auth_test_exception", health.details["error"])
    }

    @Test
    fun `health is up with private or dm scopes when configured`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties(
                toolExposure = ToolExposureProperties(
                    conversationScopeMode = ConversationScopeMode.INCLUDE_PRIVATE_AND_DM
                )
            )
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf(
                "x-oauth-scopes" to listOf(
                    "chat:write, groups:read, groups:history, users:read, users:read.email, " +
                        "reactions:write, files:write, search:read"
                )
            )
        )

        val health = indicator.health()

        assertEquals("UP", health.status.code)
    }

    @Test
    fun `health requires canvases scope when canvas tools are enabled`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties(
                canvas = CanvasToolsProperties(enabled = true)
            )
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf(
                "x-oauth-scopes" to listOf(
                    "chat:write, channels:read, channels:history, users:read, users:read.email, " +
                        "reactions:write, files:write, search:read"
                )
            )
        )

        val health = indicator.health()

        assertEquals("DOWN", health.status.code)
        assertEquals("missing_scopes", health.details["error"])
    }

    private fun authResponse(
        ok: Boolean,
        error: String? = null,
        needed: String? = null,
        provided: String? = null,
        headers: Map<String, List<String>> = emptyMap()
    ): AuthTestResponse {
        return AuthTestResponse().apply {
            setOk(ok)
            setError(error)
            setNeeded(needed)
            setProvided(provided)
            setTeamId("T123")
            setBotId("B123")
            setHttpResponseHeaders(headers)
        }
    }
}
