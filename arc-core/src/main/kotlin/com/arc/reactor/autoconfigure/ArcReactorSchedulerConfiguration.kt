package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.InMemoryScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.SlackMessageSender
import com.arc.reactor.scheduler.TeamsMessageSender
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
 * Provides cron-based job execution with dynamic job management.
 * Supports two execution modes:
 * - **MCP_TOOL**: Directly invokes a single MCP tool.
 * - **AGENT**: Runs the full ReAct agent loop for multi-source briefing.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.scheduler", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class SchedulerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryScheduledJobExecutionStore(): ScheduledJobExecutionStore =
        InMemoryScheduledJobExecutionStore()

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
        teamsMessageSenderProvider: ObjectProvider<TeamsMessageSender>,
        hookExecutorProvider: ObjectProvider<HookExecutor>,
        toolApprovalPolicyProvider: ObjectProvider<ToolApprovalPolicy>,
        pendingApprovalStoreProvider: ObjectProvider<PendingApprovalStore>,
        agentExecutorProvider: ObjectProvider<AgentExecutor>,
        personaStoreProvider: ObjectProvider<PersonaStore>,
        executionStoreProvider: ObjectProvider<ScheduledJobExecutionStore>
    ): DynamicSchedulerService = DynamicSchedulerService(
        store = scheduledJobStore,
        taskScheduler = schedulerTaskScheduler,
        mcpManager = mcpManager,
        slackMessageSender = slackMessageSender.ifAvailable,
        teamsMessageSender = teamsMessageSenderProvider.ifAvailable,
        hookExecutor = hookExecutorProvider.ifAvailable,
        toolApprovalPolicy = toolApprovalPolicyProvider.ifAvailable,
        pendingApprovalStore = pendingApprovalStoreProvider.ifAvailable,
        agentExecutor = agentExecutorProvider.ifAvailable,
        personaStore = personaStoreProvider.ifAvailable,
        executionStore = executionStoreProvider.ifAvailable
    )
}
