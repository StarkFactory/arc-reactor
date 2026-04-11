package com.arc.reactor.intent

import com.arc.reactor.intent.model.IntentDefinition
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant

/**
 * 인텐트 레지스트리 인터페이스
 *
 * 인텐트 정의의 CRUD 작업을 관리한다.
 * 인텐트 정의는 시스템이 처리할 수 있는 사용자 요청 유형과
 * 각 유형에 적용할 파이프라인 설정을 기술한다.
 *
 * WHY: 인텐트 정의를 레지스트리로 관리하여 런타임에 동적으로
 * 인텐트를 추가/수정/삭제할 수 있게 한다. REST API를 통한 관리도 가능하다.
 *
 * @see InMemoryIntentRegistry 기본 인메모리 구현
 * @see com.arc.reactor.intent.impl.JdbcIntentRegistry JDBC 영속 구현
 */
interface IntentRegistry {

    /**
     * 모든 인텐트 정의를 이름 순으로 조회한다.
     *
     * @return 인텐트 정의 목록
     */
    fun list(): List<IntentDefinition>

    /**
     * 활성화된 인텐트 정의만 조회한다.
     *
     * @return 활성 인텐트 정의 목록
     */
    fun listEnabled(): List<IntentDefinition>

    /**
     * 이름으로 인텐트 정의를 조회한다.
     *
     * @param intentName 인텐트 이름
     * @return 인텐트 정의가 존재하면 반환, 없으면 null
     */
    fun get(intentName: String): IntentDefinition?

    /**
     * 인텐트 정의를 등록하거나 갱신한다.
     *
     * @param intent 저장할 인텐트 정의
     * @return 저장된 인텐트 정의
     */
    fun save(intent: IntentDefinition): IntentDefinition

    /**
     * 이름으로 인텐트 정의를 삭제한다. 멱등성.
     *
     * @param intentName 삭제할 인텐트 이름
     */
    fun delete(intentName: String)
}

/**
 * 인메모리 인텐트 레지스트리
 *
 * Caffeine [Cache]를 사용한 bounded 스레드 안전 구현.
 * 영속적이지 않음 — 서버 재시작 시 데이터가 소실된다.
 *
 * WHY: DB 없이도 기본 동작을 보장하기 위한 기본 구현.
 * 운영 환경에서는 JdbcIntentRegistry로 대체한다.
 *
 * R305 fix: ConcurrentHashMap → Caffeine bounded cache. REST API 등록이
 * 반복되면 무제한 성장하여 OOM 위험이 있어 [DEFAULT_MAX_INTENTS] 상한을
 * 적용한다.
 *
 * @see com.arc.reactor.intent.impl.JdbcIntentRegistry 운영 환경용 JDBC 구현
 */
class InMemoryIntentRegistry(
    maxEntries: Long = DEFAULT_MAX_INTENTS
) : IntentRegistry {

    /** 인텐트 이름을 키로 하는 bounded Caffeine 캐시 */
    private val intents: Cache<String, IntentDefinition> = Caffeine.newBuilder()
        .maximumSize(maxEntries)
        .build()

    override fun list(): List<IntentDefinition> {
        return intents.asMap().values.sortedBy { it.name }
    }

    override fun listEnabled(): List<IntentDefinition> {
        return intents.asMap().values.filter { it.enabled }.sortedBy { it.name }
    }

    override fun get(intentName: String): IntentDefinition? = intents.getIfPresent(intentName)

    /**
     * 인텐트를 저장한다.
     * 기존 인텐트가 있으면 createdAt은 보존하고 updatedAt만 갱신한다.
     */
    override fun save(intent: IntentDefinition): IntentDefinition {
        val existing = intents.getIfPresent(intent.name)
        val toSave = if (existing != null) {
            intent.copy(createdAt = existing.createdAt, updatedAt = Instant.now())
        } else {
            intent
        }
        intents.put(toSave.name, toSave)
        return toSave
    }

    override fun delete(intentName: String) {
        intents.invalidate(intentName)
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        intents.cleanUp()
    }

    companion object {
        /** InMemory 구현 기본 상한. 초과 시 Caffeine W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_INTENTS: Long = 10_000L
    }
}
