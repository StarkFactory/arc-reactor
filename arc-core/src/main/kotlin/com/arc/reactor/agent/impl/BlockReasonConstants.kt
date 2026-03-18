package com.arc.reactor.agent.impl

/**
 * 에이전트 응답 차단 사유를 정의하는 상수.
 * Guard, OutputGuard, 정책 등에서 사용.
 */
internal object BlockReasonConstants {
    const val POLICY_DENIED = "policy_denied"
    const val READ_ONLY_MUTATION = "read_only_mutation"
    const val IDENTITY_UNRESOLVED = "identity_unresolved"
    const val UPSTREAM_AUTH_FAILED = "upstream_auth_failed"
    const val UPSTREAM_PERMISSION_DENIED = "upstream_permission_denied"
    const val UPSTREAM_RATE_LIMITED = "upstream_rate_limited"
    const val UNVERIFIED_SOURCES = "unverified_sources"
    const val OUTPUT_TOO_SHORT = "output_too_short"
}
