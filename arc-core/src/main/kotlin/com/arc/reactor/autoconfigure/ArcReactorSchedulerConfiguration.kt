package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.SlackMessageSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * Dynamic Scheduler Configuration (only when arc.reactor.scheduler.enabled=true)
 *
 * Provides cron-based MCP tool execution with dynamic job management.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.scheduler", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class SchedulerConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["schedulerTaskScheduler"])
    fun schedulerTaskScheduler(properties: AgentProperties): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = properties.scheduler.threadPoolSize
        scheduler.setThreadNamePrefix("arc-scheduler-")
        scheduler.initialize()
        return scheduler
    }

    @Bean
    @ConditionalOnMissingBean
    fun dynamicSchedulerService(
        scheduledJobStore: ScheduledJobStore,
        schedulerTaskScheduler: TaskScheduler,
        mcpManager: McpManager,
        slackMessageSender: ObjectProvider<SlackMessageSender>,
        hookExecutorProvider: ObjectProvider<HookExecutor>,
        toolApprovalPolicyProvider: ObjectProvider<ToolApprovalPolicy>,
        pendingApprovalStoreProvider: ObjectProvider<PendingApprovalStore>
    ): DynamicSchedulerService = DynamicSchedulerService(
        store = scheduledJobStore,
        taskScheduler = schedulerTaskScheduler,
        mcpManager = mcpManager,
        slackMessageSender = slackMessageSender.ifAvailable,
        hookExecutor = hookExecutorProvider.ifAvailable,
        toolApprovalPolicy = toolApprovalPolicyProvider.ifAvailable,
        pendingApprovalStore = pendingApprovalStoreProvider.ifAvailable
    )
}
