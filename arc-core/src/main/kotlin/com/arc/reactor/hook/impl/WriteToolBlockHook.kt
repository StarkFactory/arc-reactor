package com.arc.reactor.hook.impl

import com.arc.reactor.policy.tool.ToolExecutionDecision
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 쓰기 도구 차단 Hook
 *
 * 특정 채널(예: Slack)에서 부작용이 있는("쓰기") 도구를 차단한다.
 *
 * ## 왜 이 Hook이 필요한가
 * 채팅 우선 채널에서 실수로 파괴적인 작업이 실행되는 것을 방지하는
 * 방어-심층(defense-in-depth) 안전 계층이다.
 * 웹에서는 HITL 승인을 통해 쓰기 도구를 허용할 수 있지만,
 * Slack 같은 채널에서는 추가 보호가 필요하다.
 *
 * ## 동작 방식
 * [ToolExecutionPolicyEngine]을 사용하여 채널, 도구명, 인수를 기반으로
 * 실행 허용 여부를 판단한다. 정책에 의해 거부되면 [HookResult.Reject]를 반환하여
 * 해당 도구 호출만 차단한다 (에이전트 실행 자체는 계속).
 *
 * ## failOnError가 true인 이유
 * 정책 엔진 오류 시 안전한 쪽(차단)으로 처리하기 위함이다.
 * 정책 확인 실패가 쓰기 도구 실행을 허용하면 안 된다.
 *
 * 채널 정보는 [com.arc.reactor.hook.model.HookContext.channel]에서 가져온다.
 *
 * @param toolExecutionPolicyEngine 도구 실행 정책 엔진
 *
 * @see com.arc.reactor.hook.BeforeToolCallHook 도구 호출 전 Hook 인터페이스
 * @see com.arc.reactor.policy.tool.ToolExecutionPolicyEngine 도구 실행 정책 엔진
 */
class WriteToolBlockHook(
    private val toolExecutionPolicyEngine: ToolExecutionPolicyEngine
) : BeforeToolCallHook {

    override val order: Int = 10
    override val failOnError: Boolean = true

    override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
        return when (
            val decision = toolExecutionPolicyEngine.evaluate(
                channel = context.agentContext.channel,
                toolName = context.toolName,
                arguments = context.toolParams
            )
        ) {
            is ToolExecutionDecision.Allow -> HookResult.Continue
            is ToolExecutionDecision.Deny -> {
                logger.info {
                    "Write tool blocked by policy: tool=${context.toolName} " +
                        "channel=${context.agentContext.channel} userId=${context.agentContext.userId}"
                }
                HookResult.Reject(decision.reason)
            }
        }
    }
}
