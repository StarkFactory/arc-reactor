package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.CanvasToolsProperties

interface CanvasOwnershipPolicyService {
    fun registerOwned(canvasId: String)
    fun canEdit(canvasId: String): Boolean
}

object AllowAllCanvasOwnershipPolicyService : CanvasOwnershipPolicyService {
    override fun registerOwned(canvasId: String) = Unit

    override fun canEdit(canvasId: String): Boolean = true
}

class InMemoryCanvasOwnershipPolicyService(
    private val properties: CanvasToolsProperties
) : CanvasOwnershipPolicyService {

    private val ownedCanvasIds = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > properties.maxOwnedCanvasIds
        }
    }

    override fun registerOwned(canvasId: String) {
        val normalized = canvasId.trim()
        if (normalized.isBlank()) return
        synchronized(ownedCanvasIds) {
            ownedCanvasIds[normalized] = System.currentTimeMillis()
        }
    }

    override fun canEdit(canvasId: String): Boolean {
        if (!properties.allowlistEnforced) return true
        val normalized = canvasId.trim()
        if (normalized.isBlank()) return false
        return synchronized(ownedCanvasIds) {
            ownedCanvasIds.containsKey(normalized)
        }
    }
}
