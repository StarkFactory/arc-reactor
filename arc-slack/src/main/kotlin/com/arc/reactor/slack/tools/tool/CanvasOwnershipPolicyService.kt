package com.arc.reactor.slack.tools.tool

import com.arc.reactor.slack.tools.config.CanvasToolsProperties

/**
 * Canvas 소유권 정책 서비스.
 *
 * 봇이 생성한 Canvas만 편집을 허용하여 타인의 Canvas를 무단 수정하는 것을 방지한다.
 *
 * @see InMemoryCanvasOwnershipPolicyService
 * @see AllowAllCanvasOwnershipPolicyService
 */
interface CanvasOwnershipPolicyService {
    fun registerOwned(canvasId: String)
    fun canEdit(canvasId: String): Boolean
}

/** 모든 Canvas에 대해 편집을 허용하는 No-op 정책. 허용 목록 미사용 시 기본값. */
object AllowAllCanvasOwnershipPolicyService : CanvasOwnershipPolicyService {
    override fun registerOwned(canvasId: String) = Unit

    override fun canEdit(canvasId: String): Boolean = true
}

/** 인메모리 Canvas 소유권 정책. LRU 방식으로 최대 엔트리 수를 관리한다. */
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
