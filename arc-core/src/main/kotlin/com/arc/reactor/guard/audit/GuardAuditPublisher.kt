package com.arc.reactor.guard.audit

import com.arc.reactor.guard.model.GuardCommand

/**
 * Guard 감사 이벤트 퍼블리셔
 *
 * Guard 파이프라인 실행 과정의 감사 이벤트를 발행한다.
 * SOC 2 준수와 관찰가능성(observability)을 위해 사용된다.
 *
 * ## 발행되는 이벤트 유형
 * - **allowed**: 단계 통과 (각 단계별 + 파이프라인 전체)
 * - **rejected**: 단계에서 요청 거부
 * - **error**: 단계에서 예외 발생 (fail-close로 거부됨)
 *
 * ## 사용 위치
 * [com.arc.reactor.guard.impl.GuardPipeline]이 각 단계 실행 후
 * 이 퍼블리셔를 호출하여 감사 로그를 남긴다.
 *
 * @see com.arc.reactor.guard.impl.GuardPipeline Guard 파이프라인에서의 사용
 */
interface GuardAuditPublisher {

    /**
     * Guard 감사 이벤트를 발행한다.
     *
     * @param command 검사 중인 Guard 커맨드
     * @param stage Guard 단계명 (또는 파이프라인 레벨 이벤트의 경우 "pipeline")
     * @param result 결과: "allowed", "rejected", 또는 "error"
     * @param reason 거부/오류 사유 (허용 시 null)
     * @param category 거부 카테고리 (허용/오류 시 null)
     * @param stageLatencyMs 개별 단계 레이턴시 (밀리초)
     * @param pipelineLatencyMs 누적 파이프라인 레이턴시 (밀리초)
     */
    fun publish(
        command: GuardCommand,
        stage: String,
        result: String,
        reason: String?,
        category: String? = null,
        stageLatencyMs: Long,
        pipelineLatencyMs: Long
    )
}
