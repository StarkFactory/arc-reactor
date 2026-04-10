package com.arc.reactor.tool.summarize

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 여러 [ToolResponseSummarizer]를 순서대로 시도하여 첫 non-null 결과를 반환하는 조합 유틸.
 *
 * R226 [com.arc.reactor.approval.ChainedApprovalContextResolver]와 동일한 패턴을
 * R223 [ToolResponseSummarizer]에도 적용한 것이다. 각 도구 카테고리에 특화된 summarizer
 * (예: Jira/Confluence/Bitbucket 전용)와 일반 fallback을 체이닝하는 데 유용하다.
 *
 * ## 동작
 *
 * 생성자에 전달된 summarizer 리스트를 순서대로 `summarize()`하여:
 * - **첫 번째 non-null 결과가 나오면 즉시 반환** (short-circuit)
 * - summarizer가 null을 반환하면 다음으로 진행
 * - summarizer가 **예외를 던지면 로깅 후 다음으로 진행** (fail-open)
 * - 모든 summarizer가 null(또는 예외)이면 최종 null 반환
 *
 * ## 사용 예
 *
 * 특화 summarizer + Default fallback:
 *
 * ```kotlin
 * @Bean
 * fun toolResponseSummarizer(): ToolResponseSummarizer = ChainedToolResponseSummarizer(
 *     JiraSpecificSummarizer(),           // jira_* 도구 전용 (사용자 구현)
 *     BitbucketSpecificSummarizer(),      // bitbucket_* 도구 전용
 *     DefaultToolResponseSummarizer()     // 그 외 모든 도구
 * )
 * ```
 *
 * 단독 특화 사용:
 *
 * ```kotlin
 * @Bean
 * fun toolResponseSummarizer(): ToolResponseSummarizer = ChainedToolResponseSummarizer(
 *     MyCustomSummarizer(),
 *     DefaultToolResponseSummarizer()
 * )
 * ```
 *
 * ## Fail-Open 보장
 *
 * 특정 summarizer가 예외를 던지더라도 체인 전체가 실패하지 않는다. 개별 summarizer의
 * 버그가 전체 관측 레이어를 막지 않도록 보호한다. 모든 예외는 `logger.warn`으로 기록된다.
 *
 * ## Thread Safety
 *
 * summarizer 리스트는 생성자에서 복사본으로 고정된다. 외부에서 원본 리스트를 변경해도
 * 영향이 없으며, 동시 호출도 안전하다 (각 summarizer의 `summarize()`가 thread-safe 한 경우).
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 응답 payload 미수정 (관측만)
 * - Redis 캐시: `systemPrompt` 미수정
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정
 *
 * @param summarizers 시도 순서대로 나열된 summarizer 리스트 (빈 리스트 허용)
 *
 * @see ToolResponseSummarizer 인터페이스
 * @see DefaultToolResponseSummarizer 기본 휴리스틱 구현
 * @see com.arc.reactor.approval.ChainedApprovalContextResolver R226 동일 패턴의 Approval 버전
 */
class ChainedToolResponseSummarizer(
    summarizers: List<ToolResponseSummarizer>
) : ToolResponseSummarizer {

    /** 생성 시점에 복사한 summarizer 리스트 — 이후 불변. */
    private val summarizers: List<ToolResponseSummarizer> = summarizers.toList()

    /**
     * varargs 생성자 — 간결한 사용을 위해 제공.
     *
     * ```kotlin
     * ChainedToolResponseSummarizer(
     *     SpecializedSummarizer(),
     *     DefaultToolResponseSummarizer()
     * )
     * ```
     */
    constructor(vararg summarizers: ToolResponseSummarizer) : this(summarizers.toList())

    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? {
        if (summarizers.isEmpty()) return null
        for ((index, summarizer) in summarizers.withIndex()) {
            val result = trySummarize(summarizer, toolName, rawPayload, success, index)
            if (result != null) return result
        }
        return null
    }

    /**
     * 단일 summarizer 호출을 fail-open으로 감싼다.
     * 예외 발생 시 로깅 후 null을 반환하여 체인이 다음 summarizer로 진행하도록 한다.
     */
    private fun trySummarize(
        summarizer: ToolResponseSummarizer,
        toolName: String,
        rawPayload: String,
        success: Boolean,
        index: Int
    ): ToolResponseSummary? {
        return try {
            summarizer.summarize(toolName, rawPayload, success)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "ChainedToolResponseSummarizer: summarizer #$index " +
                    "(${summarizer::class.simpleName}) 실패, 다음으로 진행: tool=$toolName"
            }
            null
        }
    }

    /** 등록된 summarizer 개수를 반환한다 (테스트/디버깅용). */
    fun size(): Int = summarizers.size

    /** 체인이 비어있는지 확인한다. */
    fun isEmpty(): Boolean = summarizers.isEmpty()

    companion object {
        /** 빈 체인 — [summarize]가 항상 null을 반환한다. */
        val EMPTY: ChainedToolResponseSummarizer = ChainedToolResponseSummarizer()
    }
}
