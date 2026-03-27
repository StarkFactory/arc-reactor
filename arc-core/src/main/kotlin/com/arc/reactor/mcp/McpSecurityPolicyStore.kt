package com.arc.reactor.mcp

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 관리자 관리형 MCP 보안 정책.
 *
 * 정적 설정(application.yml)에 오버레이하여 운영자가
 * 재배포 없이 허용 목록을 조정할 수 있게 한다.
 *
 * WHY: 운영 중 MCP 서버 허용 목록 변경이 필요한 경우,
 * 애플리케이션 재배포 대신 REST API를 통해 즉시 반영할 수 있다.
 *
 * @param allowedServerNames 허용된 MCP 서버 이름 집합 (빈 집합 = 모두 허용)
 * @param maxToolOutputLength 도구 출력 최대 길이 (문자 수)
 * @param allowedStdioCommands STDIO 전송에 허용된 명령어 집합
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see McpSecurityConfig 정적 보안 설정
 * @see McpSecurityPolicyProvider 정적/동적 정책 통합 제공자
 */
data class McpSecurityPolicy(
    val allowedServerNames: Set<String> = emptySet(),
    val maxToolOutputLength: Int = McpSecurityConfig.DEFAULT_MAX_TOOL_OUTPUT_LENGTH,
    val allowedStdioCommands: Set<String> = McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
) {
    /** McpSecurityConfig로 변환한다 */
    fun toConfig(): McpSecurityConfig = McpSecurityConfig(
        allowedServerNames = allowedServerNames,
        maxToolOutputLength = maxToolOutputLength,
        allowedStdioCommands = allowedStdioCommands
    )

    companion object {
        /** McpSecurityConfig에서 생성한다 */
        fun fromConfig(config: McpSecurityConfig): McpSecurityPolicy = McpSecurityPolicy(
            allowedServerNames = config.allowedServerNames,
            maxToolOutputLength = config.maxToolOutputLength,
            allowedStdioCommands = config.allowedStdioCommands
        )
    }
}

/**
 * MCP 보안 정책 저장소 인터페이스.
 *
 * @see InMemoryMcpSecurityPolicyStore 인메모리 구현
 * @see JdbcMcpSecurityPolicyStore JDBC 영속 구현
 */
interface McpSecurityPolicyStore {
    /** 현재 정책을 반환한다. 설정되지 않았으면 null. */
    fun getOrNull(): McpSecurityPolicy?
    /** 정책을 저장한다. 기존 정책이 있으면 갱신한다. */
    fun save(policy: McpSecurityPolicy): McpSecurityPolicy
    /** 정책을 삭제한다. 삭제되었으면 true 반환. */
    fun delete(): Boolean
}

/**
 * 인메모리 MCP 보안 정책 저장소.
 *
 * [AtomicReference]를 사용하여 스레드 안전하게 정책을 관리한다.
 * 서버 재시작 시 데이터가 소실된다.
 *
 * @param initial 초기 정책 (선택)
 */
class InMemoryMcpSecurityPolicyStore(
    initial: McpSecurityPolicy? = null
) : McpSecurityPolicyStore {

    private val ref = AtomicReference<McpSecurityPolicy?>(initial)

    override fun getOrNull(): McpSecurityPolicy? = ref.get()

    override fun save(policy: McpSecurityPolicy): McpSecurityPolicy {
        val now = Instant.now()
        val current = ref.get()
        val saved = policy.copy(
            createdAt = current?.createdAt ?: now,
            updatedAt = now
        )
        ref.set(saved)
        return saved
    }

    override fun delete(): Boolean = ref.getAndSet(null) != null
}

/**
 * MCP 보안 정책 제공자 — 정적 설정과 동적 정책을 통합한다.
 *
 * 스토어에 저장된 동적 정책이 있으면 사용하고, 없으면 정적 기본 설정을 사용한다.
 * 정책 값을 정규화하여 공백/범위 오류를 방지한다.
 *
 * WHY: 정적 설정(application.yml)과 동적 정책(REST API)을 투명하게 통합하여
 * 호출자가 정책의 출처를 알 필요 없이 항상 유효한 보안 설정을 받을 수 있게 한다.
 *
 * @param defaultConfig 정적 기본 보안 설정
 * @param store 동적 정책 저장소
 */
class McpSecurityPolicyProvider(
    private val defaultConfig: McpSecurityConfig,
    private val store: McpSecurityPolicyStore
) {

    /** 현재 유효한 정책을 반환한다 (스토어 우선, 없으면 기본 설정) */
    fun currentPolicy(): McpSecurityPolicy {
        val stored = store.getOrNull()
        return normalize(stored ?: McpSecurityPolicy.fromConfig(defaultConfig))
    }

    /** 현재 유효한 보안 설정을 반환한다 */
    fun currentConfig(): McpSecurityConfig = currentPolicy().toConfig()

    /** 다른 동적 정책 제공자와의 일관성을 위한 무효화 메서드 (현재 예약됨) */
    fun invalidate() {
        // 다른 동적 정책 제공자와의 패리티를 위해 예약됨.
    }

    /**
     * 정책 값을 정규화한다.
     * - 서버 이름 공백 제거 및 빈 값 필터링
     * - 도구 출력 길이를 유효 범위로 제한
     */
    private fun normalize(policy: McpSecurityPolicy): McpSecurityPolicy {
        return policy.copy(
            allowedServerNames = policy.allowedServerNames
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            maxToolOutputLength = policy.maxToolOutputLength.coerceIn(MIN_TOOL_OUTPUT_LENGTH, MAX_TOOL_OUTPUT_LENGTH)
        )
    }

    companion object {
        /** 도구 출력 최소 길이 (바이트) */
        const val MIN_TOOL_OUTPUT_LENGTH = 1_024
        /** 도구 출력 최대 길이 (바이트) */
        const val MAX_TOOL_OUTPUT_LENGTH = 500_000
    }
}
