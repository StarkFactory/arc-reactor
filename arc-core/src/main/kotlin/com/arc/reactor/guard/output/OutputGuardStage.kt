package com.arc.reactor.guard.output

/**
 * 출력 Guard 단계 인터페이스
 *
 * LLM 응답을 검증하는 실행 후(post-execution) Guard 단계이다.
 * 입력 [com.arc.reactor.guard.GuardStage]는 허용/거부만 가능하지만,
 * 출력 Guard 단계는 콘텐츠를 **수정**할 수도 있다 (예: PII 마스킹).
 *
 * [OutputGuardPipeline]이 [order] 순서대로 단계를 실행한다.
 * 파이프라인은 **fail-close**로 동작한다: 예외 발생 시 응답을 차단한다.
 *
 * ## 구현 규칙
 * - [kotlin.coroutines.cancellation.CancellationException]은 항상 재던질 것
 * - 문제없으면 [OutputGuardResult.Allowed]를 반환
 * - 콘텐츠를 마스킹/수정하려면 [OutputGuardResult.Modified]를 반환
 * - 응답 전체를 차단하려면 [OutputGuardResult.Rejected]를 반환
 * - Regex 패턴은 companion object에 추출 (hot path 컴파일 방지)
 *
 * ## Order 가이드
 * - 1~99: 내장 단계
 * - 100+: 사용자 정의 단계
 *
 * @see OutputGuardPipeline 단계들을 실행하는 파이프라인
 * @see com.arc.reactor.guard.GuardStage 입력 Guard 단계 (비교용)
 *
 * ## 예제
 * ```kotlin
 * class ToxicContentGuard : OutputGuardStage {
 *     override val stageName = "ToxicContent"
 *     override val order = 30
 *     override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
 *         if (containsToxicContent(content)) {
 *             return OutputGuardResult.Rejected(
 *                 reason = "Response contains harmful content",
 *                 category = OutputRejectionCategory.HARMFUL_CONTENT
 *             )
 *         }
 *         return OutputGuardResult.Allowed.DEFAULT
 *     }
 * }
 * ```
 */
interface OutputGuardStage {

    /** 로깅 및 메트릭에 사용되는 단계명 */
    val stageName: String

    /**
     * 실행 순서. 낮은 값이 먼저 실행된다.
     * 내장 단계: 1~99, 사용자 정의 단계: 100+
     */
    val order: Int

    /** 이 단계의 활성화 여부. 비활성화된 단계는 건너뛴다. */
    val enabled: Boolean get() = true

    /**
     * LLM 응답 콘텐츠를 검사한다.
     *
     * @param content LLM 응답 콘텐츠 (이전 단계에서 수정되었을 수 있음)
     * @param context 요청 및 실행 메타데이터
     * @return 허용(Allowed), 수정(Modified, 새 콘텐츠 포함), 또는 거부(Rejected)
     */
    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult
}
