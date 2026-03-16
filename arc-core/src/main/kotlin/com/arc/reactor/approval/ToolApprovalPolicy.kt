package com.arc.reactor.approval

/**
 * 도구 승인 정책 인터페이스
 *
 * 도구 호출 실행 전에 사람의 승인(HITL: Human-In-The-Loop)이
 * 필요한지 결정한다. 이 인터페이스를 구현하여 커스텀 승인 규칙을 정의할 수 있다.
 *
 * ## 예제: 파괴적 작업 승인
 * ```kotlin
 * @Component
 * class DestructiveToolPolicy : ToolApprovalPolicy {
 *     private val destructiveTools = setOf("delete_order", "process_refund", "cancel_subscription")
 *
 *     override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
 *         return toolName in destructiveTools
 *     }
 * }
 * ```
 *
 * ## 예제: 금액 임계값
 * ```kotlin
 * class AmountPolicy : ToolApprovalPolicy {
 *     override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
 *         val amount = (arguments["amount"] as? Number)?.toDouble() ?: return false
 *         return amount > 10_000
 *     }
 * }
 * ```
 *
 * @see AlwaysApprovePolicy 자동 승인 (HITL 비활성화)
 * @see ToolNameApprovalPolicy 이름 기반 필터링
 * @see com.arc.reactor.policy.tool.DynamicToolApprovalPolicy 동적 정책과 결합
 */
interface ToolApprovalPolicy {

    /**
     * 도구 호출에 사람의 승인이 필요한지 확인한다.
     *
     * @param toolName 호출 대상 도구 이름
     * @param arguments LLM이 생성한 도구 호출 인수
     * @return 실행 전 승인이 필요하면 true
     */
    fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean
}

/**
 * 모든 도구 호출을 자동 승인하는 정책 (HITL 비활성화)
 *
 * 이것이 기본 정책이다. 승인 기능을 사용하지 않으면 이 정책이 적용된다.
 */
class AlwaysApprovePolicy : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean = false
}

/**
 * 이름 기반 승인 정책
 *
 * 도구 이름이 주어진 집합에 포함되면 사람의 승인을 요구한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       enabled: true
 *       tool-names:
 *         - delete_order
 *         - process_refund
 * ```
 *
 * @param toolNames 승인이 필요한 도구 이름 집합
 */
class ToolNameApprovalPolicy(
    private val toolNames: Set<String>
) : ToolApprovalPolicy {
    override fun requiresApproval(toolName: String, arguments: Map<String, Any?>): Boolean {
        return toolName in toolNames
    }
}
