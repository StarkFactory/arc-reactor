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

    /** toolSummaryCount 카운터를 원자적으로 증가시킨다. */
    private fun incrementCounter(context: ToolCallContext) {
        val current = (context.agentContext.metadata[COUNTER_KEY] as? Int) ?: 0
        context.agentContext.metadata[COUNTER_KEY] = current + 1
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
