package com.arc.reactor.hook.model

import com.arc.reactor.response.VerifiedSource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hook 실행 결과 (sealed class)
 *
 * Before Hook이 반환하는 결과로, 에이전트/도구 실행의 진행 여부를 결정한다.
 *
 * @see com.arc.reactor.hook.BeforeAgentStartHook 에이전트 시작 전 Hook
 * @see com.arc.reactor.hook.BeforeToolCallHook 도구 호출 전 Hook
 */
sealed class HookResult {
    /** 실행 진행 — 다음 Hook 또는 에이전트 실행으로 계속한다 */
    data object Continue : HookResult()

    /**
     * 실행 거부 — 에이전트/도구 실행을 중단한다
     *
     * @property reason 거부 사유 (사용자에게 표시됨)
     */
    data class Reject(val reason: String) : HookResult()
}

/**
 * 에이전트 실행 Hook 컨텍스트
 *
 * 에이전트 실행 전체에 걸쳐 공유되는 컨텍스트 정보이다.
 * [toolsUsed], [verifiedSources], [metadata]는 가변이며
 * Hook과 에이전트 루프에서 실행 중에 업데이트된다.
 *
 * @property runId 실행 고유 ID (추적 및 상관 관계용)
 * @property userId 요청한 사용자 ID
 * @property userEmail 사용자 이메일 (선택사항, 알림용)
 * @property userPrompt 사용자 입력 프롬프트
 * @property channel 요청 채널 (예: "slack", "web")
 * @property startedAt 실행 시작 시각
 * @property toolsUsed 사용된 도구 이름 목록 (읽기 전용 뷰 — 내부에서만 추가 가능)
 * @property verifiedSources 검증된 출처 목록 (읽기 전용 뷰 — 내부에서만 추가 가능)
 * @property metadata 확장 메타데이터 (ConcurrentHashMap — 내부에서 직접 수정)
 */
data class HookContext(
    val runId: String,
    val userId: String,
    val userEmail: String? = null,
    val userPrompt: String,
    val channel: String? = null,
    val startedAt: Instant = Instant.now(),
    val toolsUsed: List<String> = CopyOnWriteArrayList(),
    val verifiedSources: List<VerifiedSource> = CopyOnWriteArrayList(),
    /** R192: 도구 응답에서 추출한 insights 항목. LLM 응답이 비어있을 때 fallback으로 사용. */
    val toolInsights: List<String> = CopyOnWriteArrayList(),
    val metadata: MutableMap<String, Any> = ConcurrentHashMap()
) {
    /** 실행 시작 이후 경과 시간 (밀리초) */
    fun durationMs(): Long = Instant.now().toEpochMilli() - startedAt.toEpochMilli()

    // ── 내부 전용 mutation 메서드 (external Hook 작성자에게 노출되지 않음) ──

    /** 사용된 도구 이름을 추가한다. CopyOnWriteArrayList 백킹이므로 스레드 안전. */
    @Suppress("UNCHECKED_CAST")
    internal fun addToolUsed(name: String) {
        (toolsUsed as MutableList<String>).add(name)
    }

    /** 검증된 출처를 추가한다. CopyOnWriteArrayList 백킹이므로 스레드 안전. */
    @Suppress("UNCHECKED_CAST")
    internal fun addVerifiedSource(source: VerifiedSource) {
        (verifiedSources as MutableList<VerifiedSource>).add(source)
    }

    /** R192: 도구 응답에서 추출한 insights 항목을 추가한다. CopyOnWriteArrayList 백킹이므로 스레드 안전. */
    @Suppress("UNCHECKED_CAST")
    internal fun addToolInsights(insights: List<String>) {
        if (insights.isEmpty()) return
        (toolInsights as MutableList<String>).addAll(insights)
    }
}

/**
 * 도구 호출 Hook 컨텍스트
 *
 * 개별 도구 호출에 대한 컨텍스트 정보이다.
 * [agentContext]를 통해 전체 에이전트 실행 컨텍스트에도 접근할 수 있다.
 *
 * @property agentContext 상위 에이전트 실행 컨텍스트
 * @property toolName 호출 대상 도구 이름
 * @property toolParams 도구 호출 파라미터
 * @property callIndex 현재 실행의 도구 호출 순번 (0부터 시작)
 */
data class ToolCallContext(
    val agentContext: HookContext,
    val toolName: String,
    val toolParams: Map<String, Any?>,
    val callIndex: Int
) {
    /**
     * 민감한 파라미터를 마스킹하여 반환한다.
     * password, token, secret, key, credential, apikey 등의 키를 "***"로 대체한다.
     * 왜: 감사 로그나 외부 시스템에 파라미터를 전송할 때 비밀 정보 노출을 방지한다.
     */
    fun maskedParams(): Map<String, Any?> {
        return toolParams.mapValues { (key, value) ->
            if (SENSITIVE_KEY_PATTERN.containsMatchIn(key)) "***" else value
        }
    }

    companion object {
        /** 민감한 파라미터 키를 탐지하는 정규식 (companion object에서 미리 컴파일) */
        private val SENSITIVE_KEY_PATTERN = Regex(
            "(^|[_\\-.])(password|token|secret|key|credential|apikey)([_\\-.]|\$)",
            RegexOption.IGNORE_CASE
        )
    }
}

/**
 * 도구 호출 결과
 *
 * @property success 도구 호출 성공 여부
 * @property output 도구 출력 (성공 시)
 * @property errorMessage 오류 메시지 (실패 시)
 * @property durationMs 도구 실행 소요 시간 (밀리초)
 */
data class ToolCallResult(
    val success: Boolean,
    val output: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0
)

/**
 * 에이전트 응답 결과
 *
 * 에이전트 실행의 최종 결과를 나타낸다.
 * [AfterAgentCompleteHook][com.arc.reactor.hook.AfterAgentCompleteHook]에 전달된다.
 *
 * @property success 실행 성공 여부
 * @property response 에이전트 응답 텍스트 (성공 시)
 * @property errorMessage 오류 메시지 (실패 시)
 * @property toolsUsed 사용된 도구 이름 목록
 * @property totalDurationMs 전체 실행 소요 시간 (밀리초)
 * @property errorCode 오류 코드 (OUTPUT_GUARD_REJECTED, OUTPUT_TOO_SHORT 등)
 */
data class AgentResponse(
    val success: Boolean,
    val response: String? = null,
    val errorMessage: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val totalDurationMs: Long = 0,
    val errorCode: String? = null
)
