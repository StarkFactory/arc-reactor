package com.arc.reactor.rag.ingestion

import com.arc.reactor.agent.config.RagIngestionDynamicProperties
import com.arc.reactor.agent.config.RagIngestionProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RagIngestionPolicyProviderTest {

    @Test
    fun `master disabled forces policy disabled`() {
        val props = RagIngestionProperties(
            enabled = false,
            dynamic = RagIngestionDynamicProperties(enabled = true)
        )
        val store = InMemoryRagIngestionPolicyStore(
            RagIngestionPolicy(
                enabled = true,
                requireReview = false,
                allowedChannels = setOf("slack"),
                minQueryChars = 1,
                minResponseChars = 1,
                blockedPatterns = emptySet()
            )
        )

        val provider = RagIngestionPolicyProvider(props, store)
        val current = provider.current()

        assertFalse(current.enabled, "Policy should be disabled when master switch is off")
        assertEquals(emptySet<String>(), current.allowedChannels)
    }

    @Test
    fun `dynamic mode loads stored policy and normalizes fields`() {
        val props = RagIngestionProperties(
            enabled = true,
            dynamic = RagIngestionDynamicProperties(enabled = true, refreshMs = 600_000)
        )
        val store = InMemoryRagIngestionPolicyStore(
            RagIngestionPolicy(
                enabled = true,
                requireReview = true,
                allowedChannels = setOf("  SLACK  ", " "),
                minQueryChars = 0,
                minResponseChars = -1,
                blockedPatterns = setOf("  secret  ", " ")
            )
        )

        val provider = RagIngestionPolicyProvider(props, store)
        val current = provider.current()

        assertTrue(current.enabled, "Policy should be enabled when both master and dynamic switches are on")
        assertEquals(setOf("slack"), current.allowedChannels)
        assertEquals(1, current.minQueryChars)
        assertEquals(1, current.minResponseChars)
        assertEquals(setOf("secret"), current.blockedPatterns)
    }
}
