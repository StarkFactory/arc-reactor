package com.arc.reactor.guard.output.policy

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import java.util.UUID

/**
 * 동적 출력 Guard 규칙 데이터 클래스
 *
 * 관리자 API를 통해 런타임에 생성/수정/삭제할 수 있는 출력 Guard 규칙이다.
 *
 * @property id 규칙 고유 ID (UUID)
 * @property name 규칙 이름 (관리 및 로깅용)
 * @property pattern 정규식 패턴 문자열
 * @property action 매칭 시 수행할 동작 (MASK 또는 REJECT)
 * @property replacement MASK 동작 시 치환 문자열 (기본값: "[REDACTED]")
 * @property priority 실행 우선순위 (낮은 값이 먼저 평가됨)
 * @property enabled 활성화 여부
 * @property createdAt 생성 시각
 * @property updatedAt 마지막 수정 시각
 *
 * @see OutputGuardRuleStore 규칙 저장소 인터페이스
 * @see com.arc.reactor.guard.output.impl.DynamicRuleOutputGuard 이 규칙을 사용하는 Guard
 */
data class OutputGuardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val action: OutputGuardRuleAction = OutputGuardRuleAction.MASK,
    val replacement: String = DEFAULT_REPLACEMENT,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        /** MASK 동작의 기본 치환 문자열 */
        const val DEFAULT_REPLACEMENT = "[REDACTED]"
    }
}

/**
 * 출력 Guard 규칙 동작 열거형
 */
enum class OutputGuardRuleAction {
    /** 매칭된 텍스트를 규칙의 replacement 문자열로 치환 (기본값: "[REDACTED]") */
    MASK,
    /** 응답 전체를 차단 */
    REJECT
}

/**
 * 동적 출력 Guard 규칙 저장소 인터페이스
 *
 * 규칙의 CRUD 작업을 정의한다. 구현체는 메모리 또는 JDBC를 사용한다.
 *
 * @see InMemoryOutputGuardRuleStore 메모리 기반 구현체 (단일 인스턴스용)
 * @see com.arc.reactor.guard.output.policy.JdbcOutputGuardRuleStore JDBC 기반 구현체 (다중 인스턴스용)
 */
interface OutputGuardRuleStore {
    /** 모든 규칙을 조회한다 (우선순위 순). */
    fun list(): List<OutputGuardRule>

    /** ID로 규칙을 조회한다. */
    fun findById(id: String): OutputGuardRule?

    /** 새 규칙을 저장한다. */
    fun save(rule: OutputGuardRule): OutputGuardRule

    /** 기존 규칙을 수정한다. 존재하지 않으면 null 반환. */
    fun update(id: String, rule: OutputGuardRule): OutputGuardRule?

    /** 규칙을 삭제한다. */
    fun delete(id: String)
}

/**
 * 메모리 기반 출력 Guard 규칙 저장소
 *
 * Caffeine bounded cache로 스레드 안전하게 규칙을 관리한다.
 * 서버 재시작 시 데이터가 유실되므로 단일 인스턴스 또는 개발 환경에 적합하다.
 *
 * R315 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 구현은 REST API로
 * 규칙이 반복 등록되면 무제한 성장 가능성이 있었다. 이제 [maxRules] 상한(기본 1000)을
 * 넘으면 W-TinyLFU 정책으로 evict.
 *
 * @see JdbcOutputGuardRuleStore 영구 저장이 필요한 프로덕션 환경용
 */
class InMemoryOutputGuardRuleStore(
    maxRules: Long = DEFAULT_MAX_RULES
) : OutputGuardRuleStore {

    private val rules: Cache<String, OutputGuardRule> = Caffeine.newBuilder()
        .maximumSize(maxRules)
        .build()

    override fun list(): List<OutputGuardRule> {
        return rules.asMap().values.sortedWith(
            compareBy<OutputGuardRule> { it.priority }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )
    }

    override fun findById(id: String): OutputGuardRule? = rules.getIfPresent(id)

    override fun save(rule: OutputGuardRule): OutputGuardRule {
        val now = Instant.now()
        val toSave = rule.copy(createdAt = now, updatedAt = now)
        rules.put(toSave.id, toSave)
        return toSave
    }

    override fun update(id: String, rule: OutputGuardRule): OutputGuardRule? {
        val existing = rules.getIfPresent(id) ?: return null
        val updated = rule.copy(
            id = id,
            createdAt = existing.createdAt,
            updatedAt = Instant.now()
        )
        rules.put(id, updated)
        return updated
    }

    override fun delete(id: String) {
        rules.invalidate(id)
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        rules.cleanUp()
    }

    companion object {
        /** 기본 출력 Guard 규칙 상한. 초과 시 W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_RULES: Long = 1_000L
    }
}
