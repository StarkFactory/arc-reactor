package com.arc.reactor.scheduler

import java.time.Instant

/**
 * Scheduled job definition for dynamic cron-based MCP tool execution.
 */
data class ScheduledJob(
    val id: String = "",
    val name: String,
    val description: String? = null,
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",
    val mcpServerName: String,
    val toolName: String,
    val toolArguments: Map<String, Any> = emptyMap(),
    val slackChannelId: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Instant? = null,
    val lastStatus: JobExecutionStatus? = null,
    val lastResult: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class JobExecutionStatus {
    SUCCESS, FAILED, RUNNING, SKIPPED
}
