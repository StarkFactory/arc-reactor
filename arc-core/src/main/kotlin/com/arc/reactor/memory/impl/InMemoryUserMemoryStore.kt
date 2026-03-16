package com.arc.reactor.memory.impl

import com.arc.reactor.memory.UserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * [UserMemoryStore]의 인메모리 구현체.
 *
 * [ConcurrentHashMap]으로 외부 의존성 없이 스레드 안전한 접근을 제공한다.
 * 서버 재시작 시 데이터가 유실된다 — 영속성이 필요하면 [JdbcUserMemoryStore]를 사용하라.
 *
 * DataSource가 사용 불가할 때 자동 구성되는 기본 구현체이다.
 */
class InMemoryUserMemoryStore : UserMemoryStore {

    private val store = ConcurrentHashMap<String, UserMemory>()

    override suspend fun get(userId: String): UserMemory? = store[userId]

    override suspend fun save(userId: String, memory: UserMemory) {
        store[userId] = memory
    }

    override suspend fun delete(userId: String) {
        store.remove(userId)
    }

    /**
     * 단일 팩트를 원자적으로 upsert한다.
     * compute()로 읽기-수정-쓰기를 원자적으로 수행하여 경쟁 조건을 방지한다.
     */
    override suspend fun updateFact(userId: String, key: String, value: String) {
        store.compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            base.copy(facts = base.facts + (key to value), updatedAt = Instant.now())
        }
    }

    /** 단일 선호도를 원자적으로 upsert한다. */
    override suspend fun updatePreference(userId: String, key: String, value: String) {
        store.compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            base.copy(preferences = base.preferences + (key to value), updatedAt = Instant.now())
        }
    }

    /**
     * 최근 토픽을 추가한다. takeLast로 최대 수를 제한하여 슬라이딩 윈도우를 유지한다.
     */
    override suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int) {
        store.compute(userId) { _, existing ->
            val base = existing ?: UserMemory(userId = userId)
            val updated = (base.recentTopics + topic).takeLast(maxTopics)
            base.copy(recentTopics = updated, updatedAt = Instant.now())
        }
    }
}
