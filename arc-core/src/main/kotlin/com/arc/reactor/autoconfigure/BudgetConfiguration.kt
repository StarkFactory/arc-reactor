package com.arc.reactor.autoconfigure

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration

/**
 * 단계별 토큰 예산 추적 자동 설정.
 *
 * `arc.reactor.budget.enabled=true`일 때 활성화된다.
 * [com.arc.reactor.agent.budget.StepBudgetTracker]는 요청 스코프 객체이므로
 * 싱글톤 빈이 아니라, 에이전트 실행기 내부에서 설정 값을 기반으로 생성된다.
 *
 * 이 Configuration은 기능 플래그 마커 역할을 하며,
 * 향후 관련 빈(예: 예산 메트릭 수집기)을 추가할 수 있는 확장점이다.
 *
 * @see com.arc.reactor.agent.budget.StepBudgetTracker 예산 추적기
 * @see com.arc.reactor.agent.config.BudgetProperties 설정 속성
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.budget", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class BudgetConfiguration
