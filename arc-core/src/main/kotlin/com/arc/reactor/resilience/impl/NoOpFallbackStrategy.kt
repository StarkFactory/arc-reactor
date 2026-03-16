package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.resilience.FallbackStrategy

/**
 * 실패에서 복구하지 않는 No-op 폴백 전략.
 *
 * 폴백이 설정되지 않았을 때 기본값으로 사용된다.
 */
class NoOpFallbackStrategy : FallbackStrategy {

    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? = null
}
