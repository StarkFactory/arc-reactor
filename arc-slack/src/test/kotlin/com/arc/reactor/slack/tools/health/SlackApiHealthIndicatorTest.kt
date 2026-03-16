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
import org.springframework.boot.actuate.health.SimpleStatusAggregator
import org.springframework.boot.actuate.health.Status

class SlackApiHealthIndicatorTest {

    private val methodsClient = mockk<MethodsClient>()

    @Test
    fun `auth test succeeds and required scopes exist일 때 health은(는) up이다`() {
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
    fun `required scopes are missing일 때 health은(는) unknown이다`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf("x-oauth-scopes" to listOf("chat:write,channels:read"))
        )

        val health = indicator.health()

        assertEquals("UNKNOWN", health.status.code, "Missing Slack scopes should degrade optional health to UNKNOWN")
        assertEquals("missing_scopes", health.details["error"], "Health details should expose missing scope failure")
    }

    @Test
    fun `auth test fails일 때 health은(는) unknown이다`() {
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

        assertEquals("UNKNOWN", health.status.code, "Slack auth failure should not force overall app health DOWN")
        assertEquals("invalid_auth", health.details["error"], "Health details should surface Slack auth failure")
    }

    @Test
    fun `health은(는) unknown on auth test exception이다`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } throws RuntimeException("network error")

        val health = indicator.health()

        assertEquals("UNKNOWN", health.status.code, "Slack auth exceptions should degrade optional health to UNKNOWN")
        assertEquals("auth_test_exception", health.details["error"], "Health details should capture auth test exception")
    }

    @Test
    fun `configured일 때 health은(는) up with private or dm scopes이다`() {
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
    fun `canvas scope is missing일 때 health은(는) unknown이다`() {
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

        assertEquals("UNKNOWN", health.status.code, "Missing optional canvas scope should not force health DOWN")
        assertEquals("missing_scopes", health.details["error"], "Health details should expose missing canvas scope")
    }

    @Test
    fun `unknown slack api health keeps aggregate status up when core health은(는) up이다`() {
        val indicator = SlackApiHealthIndicator(
            methodsClient = methodsClient,
            properties = SlackToolsProperties()
        )
        every { methodsClient.authTest(any<AuthTestRequest>()) } throws RuntimeException("network error")

        val health = indicator.health()
        val aggregateStatus = SimpleStatusAggregator().getAggregateStatus(setOf(Status.UP, health.status))

        assertEquals("UNKNOWN", health.status.code, "Slack API failure should degrade to UNKNOWN before aggregation")
        assertEquals(Status.UP, aggregateStatus, "Actuator aggregate should stay UP when only optional Slack health is UNKNOWN")
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
