package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.model.HookContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

/**
 * 에이전트 실행 단위(run)의 식별 정보 컨테이너.
 *
 * @param runId 실행 단위 고유 ID (UUID)
 * @param hookContext 훅 실행에 사용할 컨텍스트
 */
internal data class AgentRunContext(
    val runId: String,
    val hookContext: HookContext
)

/**
 * 에이전트 실행 단위의 MDC 컨텍스트와 [HookContext]를 관리한다.
 *
 * 각 에이전트 실행마다 고유한 runId를 생성하고, 로깅에 필요한 MDC 값
 * (runId, userId, userEmail, sessionId)을 설정한다.
 * 실행 종료 시 반드시 [close]를 호출하여 MDC를 정리해야 한다.
 *
 * **주의**: MDC 맵은 명시적으로 생성하여 MDCContext(map)에 전달한다.
 * thread-local MDC에 의존하면 동일 스레드의 동시 코루틴이 서로의 MDC를 덮어쓰는 경합이 발생한다.
 *
 * @see SpringAiAgentExecutor 실행 시작 시 open(), 종료 시 close() 호출
 * @see HookContext 훅에 전달되는 컨텍스트
 */
internal class AgentRunContextManager(
    private val runIdSupplier: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * 새 실행 컨텍스트를 열고 MDC를 설정한다.
     *
     * @param command 에이전트 명령 (userId, metadata 등 추출)
     * @param toolsUsed 실행 중 사용된 도구 이름을 추적할 가변 리스트
     * @return 생성된 실행 컨텍스트
     */
    suspend fun open(command: AgentCommand, toolsUsed: MutableList<String>): AgentRunContext {
        val runId = runIdSupplier()
        val userId = command.userId ?: "anonymous"
        val userEmail = resolveUserEmail(command.metadata)

        // ── 단계 1: MDC 맵을 명시적으로 구성 ──
        // MDCContext()로 thread-local 상태를 캡처하는 대신 명시적 맵을 전달하여,
        // 동일 스레드 내 동시 코루틴 간 MDC 경합을 방지한다.
        val mdcMap = buildMap {
            put("runId", runId)
            put("userId", userId)
            userEmail?.let { put("userEmail", it) }
            command.metadata["sessionId"]?.toString()?.let { put("sessionId", it) }
        }
        // 다음 suspend 지점 이전에 로깅을 위해 thread-local MDC도 설정
        mdcMap.forEach { (k, v) -> MDC.put(k, v) }

        // ── 단계 2: HookContext 구성 ──
        val hookContext = HookContext(
            runId = runId,
            userId = userId,
            userEmail = userEmail,
            userPrompt = command.userPrompt,
            channel = command.metadata["channel"]?.toString(),
            toolsUsed = toolsUsed
        )
        hookContext.metadata.putAll(command.metadata)
        hookContext.metadata["runId"] = runId

        return withContext(MDCContext(mdcMap)) {
            AgentRunContext(runId = runId, hookContext = hookContext)
        }
    }

    /** 실행 종료 시 MDC에서 실행 관련 키를 제거한다. */
    fun close() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("userEmail")
        MDC.remove("sessionId")
    }

    /**
     * metadata에서 사용자 이메일을 추출한다.
     * 여러 후보 키를 우선순위 순으로 시도하여 첫 번째 유효한 값을 반환한다.
     */
    private fun resolveUserEmail(metadata: Map<String, Any>): String? {
        val candidates = listOf(
            "requesterEmail",
            "slackUserEmail",
            "userEmail",
            "requesterAccountId",
            "accountId"
        )
        return candidates.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }
}
