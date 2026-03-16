package com.arc.reactor.cache.impl

import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache

/**
 * No-op 응답 캐시 구현체.
 *
 * 캐싱이 비활성화되었을 때 사용된다. 모든 작업이 no-op이다.
 */
class NoOpResponseCache : ResponseCache {

    override suspend fun get(key: String): CachedResponse? = null

    override suspend fun put(key: String, response: CachedResponse) {
        // No-op: 아무것도 저장하지 않는다
    }

    override fun invalidateAll() {
        // No-op: 저장된 항목이 없으므로 무효화할 것도 없다
    }
}
