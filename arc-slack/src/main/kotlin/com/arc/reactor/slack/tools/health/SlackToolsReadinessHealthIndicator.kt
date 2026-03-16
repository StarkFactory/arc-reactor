package com.arc.reactor.slack.tools.health

import com.arc.reactor.tool.LocalTool
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Slack 도구 준비 상태 헬스 인디케이터.
 *
 * 등록된 [LocalTool] 빈의 수와 이름을 확인하여 도구 모듈의 준비 상태를 보고한다.
 * 도구가 하나도 등록되지 않으면 DOWN 상태를 반환한다.
 *
 * @param tools 등록된 Slack 도구 목록
 * @see SlackApiHealthIndicator
 */
class SlackToolsReadinessHealthIndicator(
    private val tools: List<LocalTool>
) : HealthIndicator {

    override fun health(): Health {
        if (tools.isEmpty()) {
            return Health.down()
                .withDetail("error", "no_tools_registered")
                .build()
        }

        val toolNames = tools.map { it::class.simpleName.orEmpty() }.filter { it.isNotBlank() }.sorted()
        return Health.up()
            .withDetail("toolCount", toolNames.size)
            .withDetail("tools", toolNames)
            .build()
    }
}
