package com.arc.reactor.policy.tool

import com.arc.reactor.approval.ToolApprovalPolicy

/**
 * 동적 도구 승인 정책
 *
 * 정적 설정의 도구 이름과 동적 쓰기 도구 정책을 결합하여
 * 도구 호출에 사람의 승인이 필요한지 결정한다.
 *
 * ## 판단 흐름
 * 1. 정적 도구 이름 목록([staticToolNames])에 포함 → 승인 필요
 * 2. [ToolExecutionPolicyEngine]에서 쓰기 도구로 판단 → 승인 필요
 * 3. 둘 다 아님 → 승인 불필요
 *
 * 왜 두 가지를 결합하는가: 정적 목록은 배포 시 확정된 도구에,
 * 동적 정책은 운영 중 추가/변경되는 쓰기 도구에 사용한다.
 *
 * @param staticToolNames 설정에서 고정된 승인 필요 도구 이름 집합
 * @param toolExecutionPolicyEngine 동적 쓰기 도구 판단 엔진
 *
 * @see com.arc.reactor.approval.ToolApprovalPolicy 승인 정책 인터페이스
 * @see ToolExecutionPolicyEngine 도구 실행 정책 엔진
 */
class DynamicToolApprovalPolicy(
    private val staticToolNames: Set<String>,
    private val toolExecutionPolicyEngine: ToolExecutionPolicyEngine
) : ToolApprovalPolicy {

    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        // 정적 목록에 포함된 도구는 항상 승인 필요
        if (toolName in staticToolNames) return true
        // 동적 정책에서 쓰기 도구로 판단되면 승인 필요
        return toolExecutionPolicyEngine.isWriteTool(toolName, arguments)
    }
}
