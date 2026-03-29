package com.arc.reactor.hook.impl

import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 사용자별 장기 메모리 주입 Hook
 *
 * [injectIntoPrompt]가 `true`이고 비어있지 않은 userId가 [HookContext]에 있으면:
 * 1. [UserMemoryManager.getContextPrompt]로 사용자 메모리를 로드한다
 * 2. 결과를 [HookContext.metadata]의 `userMemoryContext` 키에 저장한다
 *
 * 에이전트 실행기가 이 키를 읽어 시스템 프롬프트 끝에 추가한다.
 * Fail-Open: 오류는 로깅만 하고 절대 전파하지 않는다.
 *
 * ## 왜 Hook으로 구현하는가
 * 메모리 로드는 에이전트 실행 전에 이루어져야 하며,
 * 코어 에이전트 코드를 수정하지 않고 시스템 프롬프트를 보강할 수 있도록
 * BeforeAgentStartHook으로 구현한다.
 *
 * ## 활성화 조건
 * `arc.reactor.memory.user.enabled=true` AND
 * `arc.reactor.memory.user.inject-into-prompt=true`
 *
 * ## 시스템 프롬프트에 추가되는 형태 예시
 * ```
 * [User Context]
 * Facts: team=backend, role=senior engineer
 * Preferences: language=Korean, detail_level=brief
 * ```
 *
 * @param memoryManager 사용자 메모리 매니저
 * @param injectIntoPrompt 메모리 주입 활성화 여부
 *
 * @see com.arc.reactor.hook.BeforeAgentStartHook 에이전트 시작 전 Hook 인터페이스
 * @see com.arc.reactor.memory.UserMemoryManager 사용자 메모리 관리자
 */
class UserMemoryInjectionHook(
    private val memoryManager: UserMemoryManager,
    private val injectIntoPrompt: Boolean = false
) : BeforeAgentStartHook {

    /** Order 5: 초기에 실행하여 후속 모든 Hook에서 메모리 컨텍스트를 사용할 수 있도록 한다 */
    override val order: Int = 5

    /** Fail-Open: 메모리 주입 실패가 요청 처리를 차단하면 안 된다 */
    override val failOnError: Boolean = false

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        if (!injectIntoPrompt) return HookResult.Continue

        // "anonymous" 사용자는 메모리가 없으므로 건너뜀
        val userId = context.userId.takeIf { it.isNotBlank() && it != "anonymous" }
            ?: return HookResult.Continue

        try {
            val contextPrompt = memoryManager.getContextPrompt(userId)
            if (contextPrompt.isNotBlank()) {
                context.metadata[USER_MEMORY_CONTEXT_KEY] = contextPrompt
                logger.debug { "사용자 메모리 컨텍스트 주입 완료: userId=$userId" }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "사용자 메모리 로드 실패: userId=$userId, 컨텍스트 없이 계속" }
        }

        return HookResult.Continue
    }

    companion object {
        /** 시스템 프롬프트 빌더에 사용자 메모리 컨텍스트를 전달하는 메타데이터 키 */
        const val USER_MEMORY_CONTEXT_KEY = "userMemoryContext"
    }
}
