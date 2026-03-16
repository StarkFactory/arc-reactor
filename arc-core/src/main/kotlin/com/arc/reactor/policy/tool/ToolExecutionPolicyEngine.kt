package com.arc.reactor.policy.tool

/**
 * 도구 실행 정책 엔진
 *
 * 도구 실행 허용 여부를 판단하는 중앙 정책 엔진이다.
 * 정책 평가 로직을 한 곳에서 관리하여 Hook/컨트롤러 등
 * 전송/채널 코드와 분리한다.
 *
 * ## 왜 별도의 엔진인가
 * Hook([WriteToolBlockHook][com.arc.reactor.hook.impl.WriteToolBlockHook])과
 * 컨트롤러 양쪽에서 동일한 정책 판단이 필요하다.
 * 로직을 중복하지 않고 한 곳에서 관리하기 위해 엔진을 분리한다.
 *
 * @param toolPolicyProvider 현재 유효한 도구 정책을 제공하는 Provider
 *
 * @see ToolPolicyProvider 도구 정책 제공자
 * @see ToolPolicy 도구 정책 데이터 클래스
 * @see com.arc.reactor.hook.impl.WriteToolBlockHook 이 엔진을 사용하는 Hook
 */
class ToolExecutionPolicyEngine(
    private val toolPolicyProvider: ToolPolicyProvider
) {

    /**
     * 현재 정책에서 해당 도구가 "쓰기" 도구인지 확인한다.
     * 읽기 전용 미리보기(dryRun 등)는 쓰기로 취급하지 않는다.
     */
    fun isWriteTool(toolName: String, arguments: Map<String, Any?> = emptyMap()): Boolean {
        // 읽기 전용 미리보기는 쓰기가 아님
        if (isReadOnlyPreview(toolName, arguments)) return false
        val policy = toolPolicyProvider.current()
        if (!policy.enabled) return false
        return toolName in policy.writeToolNames
    }

    /**
     * 주어진 채널에서 도구 호출이 허용되는지 판단한다.
     *
     * ## 판단 흐름
     * 1. 정책 비활성화 또는 쓰기 도구 목록 비어있음 → 허용
     * 2. 채널이 없거나 거부 채널 목록에 없음 → 허용
     * 3. 해당 도구가 쓰기 도구가 아님 → 허용
     * 4. 거부 채널에서도 허용되는 도구 목록에 포함 → 허용
     * 5. 채널별 허용 도구 목록에 포함 → 허용
     * 6. 그 외 → 거부
     */
    fun evaluate(channel: String?, toolName: String, arguments: Map<String, Any?> = emptyMap()): ToolExecutionDecision {
        val policy = toolPolicyProvider.current()
        if (!policy.enabled || policy.writeToolNames.isEmpty()) return ToolExecutionDecision.Allow

        val normalizedChannel = channel?.trim()?.lowercase()
        if (normalizedChannel.isNullOrBlank()) return ToolExecutionDecision.Allow
        if (normalizedChannel !in policy.denyWriteChannels) return ToolExecutionDecision.Allow
        if (!isWriteTool(toolName, arguments)) return ToolExecutionDecision.Allow
        // 거부 채널에서도 허용되는 전역 예외 도구
        if (toolName in policy.allowWriteToolNamesInDenyChannels) return ToolExecutionDecision.Allow

        // 채널별 허용 도구 확인
        val allowForChannel = policy.allowWriteToolNamesByChannel[normalizedChannel] ?: emptySet()
        if (toolName in allowForChannel) return ToolExecutionDecision.Allow

        return ToolExecutionDecision.Deny(policy.denyWriteMessage)
    }

    /**
     * 읽기 전용 미리보기 모드인지 확인한다.
     * dryRun 파라미터가 true이거나 autoExecute가 false인 경우 읽기 전용으로 판단한다.
     */
    private fun isReadOnlyPreview(toolName: String, arguments: Map<String, Any?>): Boolean {
        return when (toolName) {
            "work_action_items_to_jira" -> arguments["dryRun"] == true
            "work_release_readiness_pack" -> {
                val autoExecute = arguments["autoExecuteActionItems"] == true
                val dryRun = arguments["dryRunActionItems"]
                !autoExecute && dryRun != false
            }
            else -> false
        }
    }
}

/**
 * 도구 실행 결정 (sealed class)
 *
 * @see ToolExecutionDecision.Allow 실행 허용
 * @see ToolExecutionDecision.Deny 실행 거부 (사유 포함)
 */
sealed class ToolExecutionDecision {
    /** 도구 실행 허용 */
    data object Allow : ToolExecutionDecision()
    /** 도구 실행 거부 */
    data class Deny(val reason: String) : ToolExecutionDecision()
}
