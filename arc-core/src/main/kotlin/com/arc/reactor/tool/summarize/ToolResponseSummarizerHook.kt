package com.arc.reactor.tool.summarize

import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [ToolResponseSummarizer]를 에이전트 라이프사이클에 연결하는 어댑터 Hook.
 *
 * `AfterToolCallHook`로 등록되어 각 도구 호출 완료 시 응답을 요약하고 결과를
 * [com.arc.reactor.hook.model.HookContext.metadata]에 저장한다. 이 Hook은 **관측 전용**
 * 이며 핵심 실행 경로에 영향을 주지 않는다.
 *
 * ## 저장 키
 *
 * 각 호출의 요약은 `hookContext.metadata["toolSummary_${callIndex}_${toolName}"]` 에 저장된다.
 * 동일 도구가 여러 번 호출될 수 있으므로 callIndex를 포함하여 충돌을 방지한다.
 *
 * 값은 [ToolResponseSummary] 객체이다. JSON 직렬화가 필요한 경로에서는 호출자가 알아서
 * 처리한다.
 *
 * ## 통계 메타데이터
 *
 * 추가로 에이전트 실행 전체에 대한 집계 정보도 저장된다:
 * - `toolSummaryCount` — 지금까지 생성된 요약 개수 (동일 키 override 방지용 증가 카운터)
 *
 * ## opt-in
 *
 * 이 Hook은 자동 구성에서 `arc.reactor.tool.response.summarizer.enabled=true` 속성을
 * 설정해야만 빈으로 등록된다. 기본값은 비활성이며 [NoOpToolResponseSummarizer]가 주입된다.
 *
 * ## Fail-Open 원칙
 *
 * 요약 생성 예외는 모두 삼키고 경고만 남긴다. 요약 실패가 핵심 에이전트 실행을 방해하면
 * 안 된다 (`failOnError = false`).
 *
 * ## 3대 최상위 제약 준수
 *
 * - **MCP**: 도구 응답의 원본 필드는 전혀 건드리지 않음. 요약은 별도 메타데이터 키에만 저장.
 * - **Redis 캐시**: `systemPrompt` 미수정 → scopeFingerprint 불변.
 * - **컨텍스트 관리**: 대화 이력(`MemoryStore`)에 요약이 흘러가지 않음. Hook 메타데이터에만 보관.
 *
 * @param summarizer 실제 요약 전략
 * @see ToolResponseSummarizer 요약 인터페이스
 * @see DefaultToolResponseSummarizer 기본 휴리스틱 구현체
 */
class ToolResponseSummarizerHook(
    private val summarizer: ToolResponseSummarizer
) : AfterToolCallHook {

    /** AfterToolCall Hook 순서 — 표준 Hook 범위 (100-199) */
    override val order: Int = 160

    override val failOnError: Boolean = false

    override suspend fun afterToolCall(
        context: ToolCallContext,
        result: ToolCallResult
    ) {
        try {
            val payload = result.output ?: return
            val summary = summarizer.summarize(
                toolName = context.toolName,
                rawPayload = payload,
                success = result.success
            ) ?: return
            storeSummary(context, summary)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "ToolResponseSummarizerHook 기록 실패 (무시): tool=${context.toolName}, " +
                    "callIndex=${context.callIndex}"
            }
        }
    }

    /** 요약을 HookContext 메타데이터에 저장한다. 충돌 방지를 위해 (callIndex, toolName) 키 사용. */
    private fun storeSummary(context: ToolCallContext, summary: ToolResponseSummary) {
        val key = buildKey(context.callIndex, context.toolName)
        context.agentContext.metadata[key] = summary
        incrementCounter(context)
    }

    /**
     * `toolSummaryCount` 카운터를 원자적으로 증가시킨다.
     *
     * **R335 fix**: 기존 구현은 `metadata[KEY]` 읽기 → `+1` 계산 → `metadata[KEY] = ...` 쓰기의
     * 3-step read-modify-write 였다. `HookContext.metadata`의 `put`/`get`은 스레드 안전하나
     * 3-step 조합은 atomic이 아니다. `ToolCallOrchestrator.executeInParallel`이
     * `async { }.awaitAll()`로 tool을 병렬 실행하고 각 코루틴이 `afterToolCall` → 이 Hook을
     * 호출하면, 두 코루틴이 동시에 `current=0`을 읽고 각자 `1`을 쓰면서 한 번의 증가로 귀결된다
     * → 카운터 언더카운트 → 관측 레이어 집계 누락. 기존 주석은 "원자적"이라고 거짓을 기재하고
     * 있었다.
     *
     * **수정**: `HookContext.metadata`의 default backing은 `ConcurrentHashMap`([HookModels.kt:72])
     * 이므로 `compute` 함수를 사용해 read-modify-write를 atomic하게 수행한다. 인터페이스 타입은
     * `MutableMap<String, Any>`이므로 runtime 체크 후 fallback path를 함께 제공한다 —
     * 테스트 double이나 커스텀 Map implementation이 주입되는 경우를 위한 것으로, 일반 운영에서는
     * concurrent 경로만 사용된다.
     */
    private fun incrementCounter(context: ToolCallContext) {
        val metadata = context.agentContext.metadata
        @Suppress("UNCHECKED_CAST")
        val concurrent = metadata as? java.util.concurrent.ConcurrentMap<String, Any>
        if (concurrent != null) {
            concurrent.compute(COUNTER_KEY) { _, existing ->
                ((existing as? Int) ?: 0) + 1
            }
            return
        }
        // R335: non-ConcurrentMap fallback (테스트/커스텀 구현) — 최소한의 원자성 제공
        synchronized(metadata) {
            val current = (metadata[COUNTER_KEY] as? Int) ?: 0
            metadata[COUNTER_KEY] = current + 1
        }
    }

    companion object {
        /** 요약 카운터 메타데이터 키. */
        const val COUNTER_KEY = "toolSummaryCount"

        /** 요약 저장 키 접두사. */
        const val SUMMARY_KEY_PREFIX = "toolSummary_"

        /**
         * 요약 저장 키를 생성한다. 외부에서 [com.arc.reactor.hook.model.HookContext]에서
         * 요약을 조회하고자 할 때 사용한다.
         */
        fun buildKey(callIndex: Int, toolName: String): String =
            "${SUMMARY_KEY_PREFIX}${callIndex}_$toolName"
    }
}
