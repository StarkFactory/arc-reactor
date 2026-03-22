package com.arc.reactor.agent.multiagent

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 간 공유 컨텍스트 인터페이스.
 *
 * 멀티 에이전트 실행 중 에이전트들이 읽고 쓸 수 있는 공유 상태이다.
 * [HookContext][com.arc.reactor.hook.model.HookContext]의 metadata와 달리,
 * 에이전트 실행 간에 명시적으로 공유되는 데이터를 관리한다.
 *
 * ## 사용 예시
 * ```kotlin
 * // Agent A가 결과를 공유 컨텍스트에 저장
 * context.put("jira-issues", listOf("JAR-36", "JAR-42"))
 *
 * // Agent B가 공유 컨텍스트에서 이전 에이전트의 결과를 조회
 * val issues = context.getTyped("jira-issues", List::class.java)
 * ```
 *
 * @see DefaultSharedAgentContext ConcurrentHashMap 기반 기본 구현
 * @see DefaultSupervisorAgent 공유 컨텍스트를 에이전트 위임에 활용
 */
interface SharedAgentContext {

    /**
     * 키-값 쌍을 저장한다.
     * 동일 키가 이미 존재하면 덮어쓴다.
     *
     * @param key 저장 키
     * @param value 저장 값
     */
    fun put(key: String, value: Any)

    /**
     * 키로 값을 조회한다.
     *
     * @param key 조회 키
     * @return 저장된 값 (없으면 null)
     */
    fun get(key: String): Any?

    /**
     * 키로 값을 타입 안전하게 조회한다.
     *
     * @param T 기대 타입
     * @param key 조회 키
     * @param type 기대 타입의 Class 객체
     * @return 타입 캐스팅된 값 (없거나 타입 불일치 시 null)
     */
    fun <T> getTyped(key: String, type: Class<T>): T?

    /**
     * 저장된 모든 키-값 쌍을 반환한다.
     *
     * @return 전체 컨텍스트의 불변 스냅샷
     */
    fun getAll(): Map<String, Any>
}

/**
 * [ConcurrentHashMap] 기반 공유 컨텍스트 기본 구현.
 *
 * 스레드 안전하며, 단일 Supervisor 실행 범위에 한정된다.
 *
 * @see SharedAgentContext 인터페이스 정의
 */
class DefaultSharedAgentContext : SharedAgentContext {

    private val store = ConcurrentHashMap<String, Any>()

    override fun put(key: String, value: Any) {
        store[key] = value
        logger.debug { "공유 컨텍스트 저장: key=$key" }
    }

    override fun get(key: String): Any? = store[key]

    override fun <T> getTyped(key: String, type: Class<T>): T? {
        val value = store[key] ?: return null
        return if (type.isInstance(value)) {
            type.cast(value)
        } else {
            logger.warn {
                "공유 컨텍스트 타입 불일치: key=$key, " +
                    "expected=${type.simpleName}, " +
                    "actual=${value::class.simpleName}"
            }
            null
        }
    }

    override fun getAll(): Map<String, Any> = store.toMap()
}
