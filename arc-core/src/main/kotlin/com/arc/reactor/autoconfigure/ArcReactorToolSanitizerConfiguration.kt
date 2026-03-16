package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Tool Output Sanitizer Configuration
 *
 * 도구 출력 새니타이징을 통한 간접 프롬프트 주입 방어를 활성화한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.guard", name = ["tool-output-sanitization-enabled"],
    havingValue = "true", matchIfMissing = false
)
class ToolSanitizerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun toolOutputSanitizer(properties: AgentProperties): ToolOutputSanitizer =
        ToolOutputSanitizer(maxOutputLength = properties.mcp.security.maxToolOutputLength)
}
