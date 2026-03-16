package com.arc.reactor.policy.tool

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 메모리 기반 도구 정책 저장소
 *
 * [AtomicReference]를 사용하여 스레드 안전하게 단일 정책을 관리한다.
 * 서버 재시작 시 데이터가 유실되므로 개발 환경이나 단일 인스턴스 배포에 적합하다.
 *
 * @param initial 초기 정책 (선택사항)
 *
 * @see ToolPolicyStore 저장소 인터페이스
 * @see JdbcToolPolicyStore 영구 저장이 필요한 프로덕션 환경용
 */
class InMemoryToolPolicyStore(
    initial: ToolPolicy? = null
) : ToolPolicyStore {

    private val ref = AtomicReference<ToolPolicy?>(initial)

    override fun getOrNull(): ToolPolicy? = ref.get()

    override fun save(policy: ToolPolicy): ToolPolicy {
        val now = Instant.now()
        val current = ref.get()
        // 기존 정책의 createdAt을 유지하고 updatedAt만 갱신
        val saved = policy.copy(
            createdAt = current?.createdAt ?: now,
            updatedAt = now
        )
        ref.set(saved)
        return saved
    }

    override fun delete(): Boolean {
        val existed = ref.getAndSet(null) != null
        return existed
    }
}
