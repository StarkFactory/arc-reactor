package com.arc.reactor.slack.tools.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolExposureResolverTest {

    @Test
    fun `returns all tools when scope-aware exposure is disabled`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(scopeAwareEnabled = false)
            ),
            slackScopeProvider = scopeProvider
        )

        val resolved = resolver.resolveToolObjects(sampleCandidates())

        assertEquals(listOf("send", "read"), resolved)
    }

    @Test
    fun `filters tools by granted scopes when scope-aware exposure is enabled`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        every { scopeProvider.resolveGrantedScopes() } returns setOf("chat:write")
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(scopeAwareEnabled = true)
            ),
            slackScopeProvider = scopeProvider
        )

        val resolved = resolver.resolveToolObjects(sampleCandidates())

        assertEquals(listOf("send"), resolved)
    }

    @Test
    fun `returns all tools on scope resolution error when fail-open is enabled`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        every { scopeProvider.resolveGrantedScopes() } throws RuntimeException("network error")
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(
                    scopeAwareEnabled = true,
                    failOpenOnScopeResolutionError = true
                )
            ),
            slackScopeProvider = scopeProvider
        )

        val resolved = resolver.resolveToolObjects(sampleCandidates())

        assertEquals(listOf("send", "read"), resolved)
    }

    @Test
    fun `returns no tools on scope resolution error when fail-open is disabled`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        every { scopeProvider.resolveGrantedScopes() } throws RuntimeException("network error")
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(
                    scopeAwareEnabled = true,
                    failOpenOnScopeResolutionError = false
                )
            ),
            slackScopeProvider = scopeProvider
        )

        val resolved = resolver.resolveToolObjects(sampleCandidates())

        assertEquals(emptyList<String>(), resolved)
    }

    @Test
    fun `returns no tools when scopes are empty and fail-open is disabled`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        every { scopeProvider.resolveGrantedScopes() } returns emptySet()
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(
                    scopeAwareEnabled = true,
                    failOpenOnScopeResolutionError = false
                )
            ),
            slackScopeProvider = scopeProvider
        )

        val resolved = resolver.resolveToolObjects(sampleCandidates())

        assertEquals(emptyList<String>(), resolved)
    }

    private fun sampleCandidates(): List<ToolCandidate> = listOf(
        ToolCandidate(
            name = "send_message",
            requiredScopes = setOf("chat:write"),
            toolObject = "send"
        ),
        ToolCandidate(
            name = "read_messages",
            requiredScopes = setOf("channels:history"),
            toolObject = "read"
        )
    )
}
