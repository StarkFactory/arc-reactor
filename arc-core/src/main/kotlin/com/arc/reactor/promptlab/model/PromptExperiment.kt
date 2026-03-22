package com.arc.reactor.promptlab.model

import java.time.Instant
import java.util.UUID

/**
 * 라이브 A/B 테스트 실험 상태.
 *
 * 배치 실험([ExperimentStatus])과 구분하여 라이브 트래픽 실험의 수명주기를 나타낸다.
 */
enum class LiveExperimentStatus {
    /** 설정 완료, 아직 트래픽 라우팅 전 */
    DRAFT,

    /** 트래픽 라우팅 중 — 실제 요청에 적용 */
    RUNNING,

    /** 실험 종료 — 결과 분석 가능 */
    COMPLETED
}

/**
 * 라이브 A/B 테스트에서 사용된 프롬프트 변형.
 */
enum class PromptVariant {
    /** 기존(기준) 프롬프트 */
    CONTROL,

    /** 새로운 후보 프롬프트 */
    VARIANT
}

/**
 * 라이브 프롬프트 A/B 테스트 실험 정의.
 *
 * 운영 트래픽의 일정 비율을 새 프롬프트(variant)로 라우팅하여
 * 기존 프롬프트(control)와 성공률을 비교한다.
 *
 * 배치 실험([Experiment])과 달리 실시간 트래픽을 대상으로 한다.
 * 세션 기반 결정론적 라우팅으로 동일 세션은 항상 같은 변형을 사용한다.
 *
 * @param id 실험 고유 식별자
 * @param name 실험 이름
 * @param description 실험 설명
 * @param controlPrompt 기준(현재) 시스템 프롬프트
 * @param variantPrompt 후보(새) 시스템 프롬프트
 * @param trafficPercent 후보에 라우팅할 트래픽 비율 (0-100)
 * @param status 실험 상태
 * @param metrics 실험 메트릭 집계
 * @param createdAt 생성 시각
 * @param startedAt 실험 시작 시각
 * @param completedAt 실험 완료 시각
 * @see com.arc.reactor.promptlab.PromptExperimentRouter 트래픽 라우팅
 * @see com.arc.reactor.promptlab.LiveExperimentStore 저장소
 */
data class PromptExperiment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val controlPrompt: String,
    val variantPrompt: String,
    val trafficPercent: Int = 10,
    val status: LiveExperimentStatus = LiveExperimentStatus.DRAFT,
    val metrics: ExperimentMetrics = ExperimentMetrics(),
    val createdAt: Instant = Instant.now(),
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
) {
    init {
        require(trafficPercent in 0..100) {
            "trafficPercent must be 0-100, was: $trafficPercent"
        }
    }
}

/**
 * 실험의 집계 메트릭.
 *
 * control/variant 각각의 성공률, 샘플 수, 평균 지연시간을 추적한다.
 *
 * @param controlSuccessCount control 성공 수
 * @param controlTotalCount control 전체 수
 * @param variantSuccessCount variant 성공 수
 * @param variantTotalCount variant 전체 수
 * @param controlTotalLatencyMs control 누적 지연시간 (밀리초)
 * @param variantTotalLatencyMs variant 누적 지연시간 (밀리초)
 */
data class ExperimentMetrics(
    val controlSuccessCount: Int = 0,
    val controlTotalCount: Int = 0,
    val variantSuccessCount: Int = 0,
    val variantTotalCount: Int = 0,
    val controlTotalLatencyMs: Long = 0,
    val variantTotalLatencyMs: Long = 0
) {
    /** control 성공률 (0.0-1.0). 샘플이 없으면 0.0. */
    val controlSuccessRate: Double
        get() = if (controlTotalCount > 0) {
            controlSuccessCount.toDouble() / controlTotalCount
        } else 0.0

    /** variant 성공률 (0.0-1.0). 샘플이 없으면 0.0. */
    val variantSuccessRate: Double
        get() = if (variantTotalCount > 0) {
            variantSuccessCount.toDouble() / variantTotalCount
        } else 0.0

    /** 전체 샘플 수 (control + variant). */
    val totalSampleCount: Int
        get() = controlTotalCount + variantTotalCount

    /** control 평균 지연시간 (밀리초). 샘플이 없으면 0. */
    val controlAvgLatencyMs: Long
        get() = if (controlTotalCount > 0) {
            controlTotalLatencyMs / controlTotalCount
        } else 0

    /** variant 평균 지연시간 (밀리초). 샘플이 없으면 0. */
    val variantAvgLatencyMs: Long
        get() = if (variantTotalCount > 0) {
            variantTotalLatencyMs / variantTotalCount
        } else 0
}

/**
 * 라이브 A/B 테스트의 단일 실행 결과.
 *
 * @param id 결과 고유 식별자
 * @param experimentId 소속 실험 ID
 * @param variant 사용된 프롬프트 변형
 * @param success 실행 성공 여부
 * @param latencyMs 실행 소요 시간 (밀리초)
 * @param sessionId 세션 ID (동일 세션 결정론적 라우팅 추적용)
 * @param timestamp 기록 시각
 */
data class ExperimentResult(
    val id: String = UUID.randomUUID().toString(),
    val experimentId: String,
    val variant: PromptVariant,
    val success: Boolean,
    val latencyMs: Long,
    val sessionId: String? = null,
    val timestamp: Instant = Instant.now()
)

/**
 * 실험 보고서 — 실험 종료 후 최종 집계.
 *
 * @param experimentId 실험 ID
 * @param experimentName 실험 이름
 * @param metrics 최종 메트릭
 * @param winner 승자 변형 (통계적으로 유의미한 경우)
 * @param confidenceLevel 통계적 신뢰도
 * @param generatedAt 보고서 생성 시각
 */
data class LiveExperimentReport(
    val experimentId: String,
    val experimentName: String,
    val metrics: ExperimentMetrics,
    val winner: PromptVariant?,
    val confidenceLevel: String,
    val generatedAt: Instant = Instant.now()
)
