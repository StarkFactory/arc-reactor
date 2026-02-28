package com.arc.reactor.slack.tools.config

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SlackAuthTestScopeProviderTest {

    private val methodsClient = mockk<MethodsClient>()
    private val provider = SlackAuthTestScopeProvider(methodsClient)

    @Test
    fun `parses granted scopes from auth test response headers`() {
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = mapOf("x-oauth-scopes" to listOf("chat:write, channels:read, users:read"))
        )

        val scopes = provider.resolveGrantedScopes()

        assertEquals(setOf("chat:write", "channels:read", "users:read"), scopes)
    }

    @Test
    fun `throws when auth test response is not ok`() {
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = false,
            error = "invalid_auth"
        )

        assertThrows(IllegalStateException::class.java) {
            provider.resolveGrantedScopes()
        }
    }

    @Test
    fun `returns empty set when scope header is missing`() {
        every { methodsClient.authTest(any<AuthTestRequest>()) } returns authResponse(
            ok = true,
            headers = emptyMap()
        )

        val scopes = provider.resolveGrantedScopes()

        assertEquals(emptySet<String>(), scopes)
    }

    private fun authResponse(
        ok: Boolean,
        error: String? = null,
        headers: Map<String, List<String>> = emptyMap()
    ): AuthTestResponse {
        return AuthTestResponse().apply {
            setOk(ok)
            setError(error)
            setHttpResponseHeaders(headers)
        }
    }
}
