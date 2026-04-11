package com.arc.reactor.memory.impl

import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant

/**
 * [UserMemoryStore]의 인메모리 구현체.
 *
 * Caffeine bounded cache로 외부 의존성 없이 스레드 안전한 접근을 제공한다.
 * 서버 재시작 시 데이터가 유실된다 — 영속성이 필요하면 [JdbcUserMemoryStore]를 사용하라.
 *
 * DataSource가 사용 불가할 때 자동 구성되는 기본 구현체이다.
 *
 * R312 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 구현은 userId별
 * 메모리가 무제한 누적될 수 있어 사용자 폭증 시 OOM 위험이 있었다. 이제
 * [maxUsers] 상한(기본 10,000)을 넘으면 W-TinyLFU 정책으로 evict. compute 연산은
 * Caffeine `asMap()`이 반환하는 `ConcurrentMap` 뷰를 통해 원자성을 유지한다.
 */
class InMemoryUserMemoryStore(
    maxUsers: Long = DEFAULT_MAX_USERS
) : UserMemoryStore {

    private val store: Cache<String, UserMemory> = Caffeine.newBuilder()
        .maximumSize(maxUsers)
        .build()

    override suspend fun get(userId: String): UserMemory? = store.getIfPresent(userId)

    override suspend fun save(userId: String, memory: UserMemory) {
        store.put(userId, memory)
    }

    override suspend fun delete(userId: String) {
        store.invalidate(userId)
    }

    /**
     * 단일 팩트를 원자적으로 upsert한다.
     * Caffeine asMap()은 ConcurrentMap 뷰를 제공하므로 compute()는 여전히 원자적이다.
     */
    override suspend fun updateFact(userId: String, key: String, value: String) {
        store.asMap().compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            base.copy(facts = base.facts + (key to value), updatedAt = Instant.now())
        }
    }

    /** 단일 선호도를 원자적으로 upsert한다. */
    override suspend fun updatePreference(userId: String, key: String, value: String) {
        store.asMap().compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            base.copy(preferences = base.preferences + (key to value), updatedAt = Instant.now())
        }
    }

    /**
     * 최근 토픽을 추가한다. takeLast로 최대 수를 제한하여 슬라이딩 윈도우를 유지한다.
     */
    override suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int) {
        store.asMap().compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            val updated = (base.recentTopics + topic).takeLast(maxTopics)
            base.copy(recentTopics = updated, updatedAt = Instant.now())
        }
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        store.cleanUp()
    }

    companion object {
        /** 기본 사용자 메모리 상한. 초과 시 Caffeine W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_USERS: Long = 10_000L
    }
}
