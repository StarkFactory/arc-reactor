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
 * Enables indirect prompt injection defense via tool output sanitization.
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
