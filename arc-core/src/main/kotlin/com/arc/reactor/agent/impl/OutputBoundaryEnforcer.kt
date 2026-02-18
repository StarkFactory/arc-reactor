package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.support.formatBoundaryViolation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class OutputBoundaryEnforcer(
    private val boundaries: BoundaryProperties,
    private val agentMetrics: AgentMetrics
) {

    suspend fun apply(
        result: AgentResult,
        command: AgentCommand,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult? {
        val content = result.content ?: return result
        val len = content.length

        val afterMax = if (boundaries.outputMaxChars > 0 && len > boundaries.outputMaxChars) {
            val policy = "truncate"
            agentMetrics.recordBoundaryViolation(
                "output_too_long", policy, boundaries.outputMaxChars, len
            )
            logger.info { formatBoundaryViolation("output_too_long", policy, boundaries.outputMaxChars, len) }
            result.copy(content = content.take(boundaries.outputMaxChars) + "\n\n[Response truncated]")
        } else {
            result
        }

        val effectiveContent = afterMax.content ?: return afterMax
        if (boundaries.outputMinChars <= 0 || effectiveContent.length >= boundaries.outputMinChars) {
            return afterMax
        }

        return when (boundaries.outputMinViolationMode) {
            OutputMinViolationMode.WARN -> {
                val policy = OutputMinViolationMode.WARN.name.lowercase()
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", policy, boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    formatBoundaryViolation(
                        "output_too_short",
                        policy,
                        boundaries.outputMinChars,
                        effectiveContent.length
                    )
                }
                afterMax
            }

            OutputMinViolationMode.RETRY_ONCE -> {
                val policy = OutputMinViolationMode.RETRY_ONCE.name.lowercase()
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", policy, boundaries.outputMinChars, effectiveContent.length
                )
                logger.info {
                    formatBoundaryViolation(
                        "output_too_short",
                        policy,
                        boundaries.outputMinChars,
                        effectiveContent.length
                    )
                }
                val retried = attemptLongerResponse(effectiveContent, boundaries.outputMinChars, command)
                if (retried != null && retried.length >= boundaries.outputMinChars) {
                    afterMax.copy(content = retried)
                } else {
                    logger.warn {
                        "Boundary retry result: output_too_short still below limit " +
                            "(actual=${retried?.length ?: 0}, limit=${boundaries.outputMinChars})"
                    }
                    afterMax
                }
            }

            OutputMinViolationMode.FAIL -> {
                val policy = OutputMinViolationMode.FAIL.name.lowercase()
                agentMetrics.recordBoundaryViolation(
                    "output_too_short", policy, boundaries.outputMinChars, effectiveContent.length
                )
                logger.warn {
                    formatBoundaryViolation(
                        "output_too_short",
                        policy,
                        boundaries.outputMinChars,
                        effectiveContent.length
                    )
                }
                null
            }
        }
    }
}
