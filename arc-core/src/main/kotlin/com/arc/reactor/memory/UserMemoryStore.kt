package com.arc.reactor.memory

import com.arc.reactor.memory.model.UserMemory

/**
 * 사용자별 장기 기억의 영속 저장소.
 *
 * 구현체는 여러 코루틴에서의 동시 접근에 스레드 안전해야 한다.
 * 기본 구현체는 [impl.InMemoryUserMemoryStore]이다.
 * DataSource가 사용 가능하면 [impl.JdbcUserMemoryStore]가 @Primary로 자동 구성된다.
 */
interface UserMemoryStore {

    /** 주어진 사용자의 저장된 [UserMemory]를 반환한다. 없으면 null. */
    suspend fun get(userId: String): UserMemory?

    /** 주어진 사용자의 [UserMemory]를 저장(생성 또는 교체)한다. */
    suspend fun save(userId: String, memory: UserMemory)

    /** 주어진 사용자의 모든 저장된 기억을 제거한다. 멱등(idempotent). */
    suspend fun delete(userId: String)

    /**
     * 사용자의 단일 팩트 항목을 upsert한다.
     * 기억 레코드가 아직 없으면 이 팩트만 가진 새 레코드를 생성한다.
     */
    suspend fun updateFact(userId: String, key: String, value: String)

    /**
     * 사용자의 단일 선호도 항목을 upsert한다.
     * 기억 레코드가 아직 없으면 이 선호도만 가진 새 레코드를 생성한다.
     */
    suspend fun updatePreference(userId: String, key: String, value: String)

    /**
     * 사용자의 최근 토픽 목록에 [topic]을 추가한다.
     * 목록은 [maxTopics]으로 제한되며, 한도에 도달하면 가장 오래된 항목이 제거된다.
     */
    suspend fun addRecentTopic(userId: String, topic: String, maxTopics: Int = 10)
}
