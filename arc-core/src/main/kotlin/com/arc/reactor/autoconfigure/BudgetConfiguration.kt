package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.budget.MonthlyBudgetTracker
import com.arc.reactor.agent.config.AgentProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 토큰 예산 추적 자동 설정.
 *
 * `arc.reactor.budget.enabled=true`일 때 활성화된다.
 * [com.arc.reactor.agent.budget.StepBudgetTracker]는 요청 스코프 객체이므로
 * 에이전트 실행기 내부에서 생성된다.
 * [MonthlyBudgetTracker]는 싱글톤으로 테넌트별 월간 비용을 추적한다.
 *
 * @see com.arc.reactor.agent.budget.StepBudgetTracker 요청별 예산 추적
 * @see MonthlyBudgetTracker 테넌트별 월간 예산 추적
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.budget", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class BudgetConfiguration {

    /** 테넌트별 월간 비용 추적기. */
    @Bean
    @ConditionalOnMissingBean
    fun monthlyBudgetTracker(properties: AgentProperties): MonthlyBudgetTracker {
        val budget = properties.budget
        return MonthlyBudgetTracker(
            monthlyLimitUsd = budget.monthlyLimitUsd,
            warningPercent = budget.monthlyWarningPercent
        )
    }
}
