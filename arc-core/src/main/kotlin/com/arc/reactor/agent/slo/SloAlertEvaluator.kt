package com.arc.reactor.agent.slo

/**
 * SLO 알림 평가기 인터페이스.
 *
 * 슬라이딩 윈도우 기반으로 레이턴시(P95)와 에러율을 추적하고,
 * 설정된 임계값 초과 시 [SloViolation]을 반환한다.
 *
 * @see DefaultSloAlertEvaluator 기본 구현체
 */
interface SloAlertEvaluator {

    /**
     * 현재 메트릭을 임계값과 비교하여 위반 목록을 반환한다.
     *
     * 쿨다운 기간 내에는 동일 유형의 위반을 반복 반환하지 않는다.
     *
     * @return SLO 위반 목록 (위반 없으면 빈 리스트)
     */
    fun evaluate(): List<SloViolation>

    /**
     * 레이턴시 데이터 포인트를 기록한다.
     *
     * @param durationMs 요청 E2E 소요 시간 (밀리초)
     */
    fun recordLatency(durationMs: Long)

    /**
     * 요청 결과(성공/실패)를 기록한다.
     *
     * @param success 요청 성공 여부
     */
    fun recordResult(success: Boolean)
}
