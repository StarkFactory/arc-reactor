package com.arc.reactor.policy.tool

/**
 * 도구 정책 저장소 인터페이스
 *
 * `arc.reactor.tool-policy.dynamic.enabled=true`이고 JDBC가 설정되면
 * DB에 의해 백업된다.
 *
 * @see InMemoryToolPolicyStore 메모리 기반 구현체
 * @see JdbcToolPolicyStore JDBC 기반 구현체
 * @see ToolPolicyProvider 이 저장소를 사용하는 정책 제공자
 */
interface ToolPolicyStore {
    /** 저장된 정책을 조회한다. 없으면 null 반환. */
    fun getOrNull(): ToolPolicy?

    /** 정책을 저장(또는 업데이트)한다. */
    fun save(policy: ToolPolicy): ToolPolicy

    /** 정책을 삭제한다. 삭제되었으면 true 반환. */
    fun delete(): Boolean
}
