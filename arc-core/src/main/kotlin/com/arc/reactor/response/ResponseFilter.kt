package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand

/**
 * 호출자에게 반환하기 전에 에이전트 응답 콘텐츠를 변환하는 필터.
 *
 * 필터는 [ResponseFilterChain]에 의해 [order] 순서로 실행된다.
 * 각 필터는 현재 콘텐츠를 받아 수정하거나 그대로 전달할 수 있다.
 *
 * ## 구현 규칙
 * - [kotlin.coroutines.cancellation.CancellationException]은 항상 재던져야 한다
 * - 변환이 필요 없으면 입력 콘텐츠를 그대로 반환한다
 * - 필터는 멱등(idempotent)이어야 한다 (여러 번 적용해도 안전)
 *
 * ## 사용 예시
 * ```kotlin
 * class ProfanityFilter : ResponseFilter {
 *     override val order = 20
 *     override suspend fun filter(content: String, context: ResponseFilterContext): String {
 *         return content.replace(profanityRegex, "***")
 *     }
 * }
 * ```
 */
interface ResponseFilter {

    /**
     * 실행 순서. 낮은 값이 먼저 실행된다.
     * 내장 필터는 1-99를 사용한다. 커스텀 필터는 100+을 사용해야 한다.
     */
    val order: Int get() = 100

    /**
     * 응답 콘텐츠를 변환한다.
     *
     * @param content 현재 응답 콘텐츠 (이전 필터에 의해 수정되었을 수 있음)
     * @param context 요청 및 실행에 대한 메타데이터
     * @return 변환된 콘텐츠
     */
    suspend fun filter(content: String, context: ResponseFilterContext): String
}

/**
 * [ResponseFilter]에 전달되는 현재 요청에 대한 메타데이터 컨텍스트.
 *
 * @param command 원본 에이전트 커맨드
 * @param toolsUsed 실행 중 사용된 도구 목록
 * @param verifiedSources 도구 출력에서 수집된 검증된 출처
 * @param durationMs 실행 소요 시간 (밀리초)
 */
data class ResponseFilterContext(
    val command: AgentCommand,
    val toolsUsed: List<String>,
    val verifiedSources: List<VerifiedSource> = emptyList(),
    val durationMs: Long
)
