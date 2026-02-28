package com.arc.reactor.slack.tools.health

import com.arc.reactor.tool.LocalTool
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

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
