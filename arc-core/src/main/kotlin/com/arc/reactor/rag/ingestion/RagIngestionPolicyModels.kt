package com.arc.reactor.rag.ingestion

import com.arc.reactor.agent.config.RagIngestionProperties
import java.time.Instant

/**
 * Effective RAG ingestion policy (global, admin-managed).
 */
data class RagIngestionPolicy(
    val enabled: Boolean,
    val requireReview: Boolean,
    val allowedChannels: Set<String>,
    val minQueryChars: Int,
    val minResponseChars: Int,
    val blockedPatterns: Set<String>,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        fun fromProperties(props: RagIngestionProperties): RagIngestionPolicy = RagIngestionPolicy(
            enabled = props.enabled,
            requireReview = props.requireReview,
            allowedChannels = props.allowedChannels,
            minQueryChars = props.minQueryChars,
            minResponseChars = props.minResponseChars,
            blockedPatterns = props.blockedPatterns
        )
    }
}

interface RagIngestionPolicyStore {
    fun getOrNull(): RagIngestionPolicy?
    fun save(policy: RagIngestionPolicy): RagIngestionPolicy
    fun delete(): Boolean
}

