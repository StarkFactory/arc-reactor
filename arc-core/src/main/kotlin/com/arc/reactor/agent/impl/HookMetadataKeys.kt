package com.arc.reactor.agent.impl

/**
 * hookContext.metadata에 사용되는 공통 메타데이터 키 상수.
 */
internal object HookMetadataKeys {
    const val GUARD_DURATION_MS = "guardDurationMs"
    const val QUEUE_WAIT_MS = "queueWaitMs"
    const val FALLBACK_USED = "fallbackUsed"
    const val INTENT_CATEGORY = "intentCategory"
    const val HISTORY_MESSAGE_COUNT = "historyMessageCount"
}
