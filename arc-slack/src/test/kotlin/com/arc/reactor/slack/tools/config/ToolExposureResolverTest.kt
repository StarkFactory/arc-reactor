package com.arc.reactor.slack.tools.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolExposureResolverTest {

    @Test
    fun `scope-aware exposure is disabled일 때 all tools를 반환한다`() {
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
    fun `filters tools by granted scopes when scope-aware exposure은(는) enabled이다`() {
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
    fun `fail-open is enabled일 때 all tools on scope resolution error를 반환한다`() {
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
    fun `fail-open is disabled일 때 no tools on scope resolution error를 반환한다`() {
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
    fun `scopes are empty and fail-open is disabled일 때 no tools를 반환한다`() {
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

    @Test
    fun `required any scopes allow tool when one scope은(는) granted이다`() {
        val scopeProvider = mockk<SlackScopeProvider>()
        every { scopeProvider.resolveGrantedScopes() } returns setOf("groups:history")
        val resolver = ToolExposureResolver(
            properties = SlackToolsProperties(
                botToken = "xoxb-test",
                toolExposure = ToolExposureProperties(scopeAwareEnabled = true)
            ),
            slackScopeProvider = scopeProvider
        )
        val candidates = listOf(
            ToolCandidate(
                name = "read_messages",
                requiredScopes = emptySet(),
                requiredAnyScopes = setOf("channels:history", "groups:history"),
                toolObject = "read"
            )
        )

        val resolved = resolver.resolveToolObjects(candidates)

        assertEquals(listOf("read"), resolved)
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
