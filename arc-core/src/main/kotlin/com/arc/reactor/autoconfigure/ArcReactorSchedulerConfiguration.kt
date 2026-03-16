package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.scheduler.DynamicSchedulerService
import com.arc.reactor.scheduler.InMemoryScheduledJobStore
import com.arc.reactor.scheduler.InMemoryScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobExecutionStore
import com.arc.reactor.scheduler.ScheduledJobStore
import com.arc.reactor.scheduler.SlackMessageSender
import com.arc.reactor.scheduler.TeamsMessageSender
import com.arc.reactor.scheduler.tool.CreateScheduledJobTool
import com.arc.reactor.scheduler.tool.DeleteScheduledJobTool
import com.arc.reactor.scheduler.tool.ListScheduledJobsTool
import com.arc.reactor.scheduler.tool.UpdateScheduledJobTool
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 동적 스케줄러 설정 (arc.reactor.scheduler.enabled=true일 때만)
 *
 * cron 기반 작업 실행과 동적 작업 관리를 제공한다.
 * 두 가지 실행 모드를 지원한다:
 * - **MCP_TOOL**: 단일 MCP 도구를 직접 호출.
 * - **AGENT**: 다중 소스 브리핑을 위한 전체 ReAct 에이전트 루프 실행.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.scheduler", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class SchedulerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryScheduledJobStore(): ScheduledJobStore = InMemoryScheduledJobStore()

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
        properties: AgentProperties,
        slackMessageSender: ObjectProvider<SlackMessageSender>,
        teamsMessageSenderProvider: ObjectProvider<TeamsMessageSender>,
        hookExecutorProvider: ObjectProvider<HookExecutor>,
        toolApprovalPolicyProvider: ObjectProvider<ToolApprovalPolicy>,
        pendingApprovalStoreProvider: ObjectProvider<PendingApprovalStore>,
        agentExecutorProvider: ObjectProvider<AgentExecutor>,
        personaStoreProvider: ObjectProvider<PersonaStore>,
        promptTemplateStoreProvider: ObjectProvider<PromptTemplateStore>,
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
        agentExecutorProvider = { agentExecutorProvider.ifAvailable },
        personaStore = personaStoreProvider.ifAvailable,
        promptTemplateStore = promptTemplateStoreProvider.ifAvailable,
        executionStore = executionStoreProvider.ifAvailable,
        schedulerProperties = properties.scheduler
    )

    @Bean
    @ConditionalOnMissingBean
    fun createScheduledJobTool(
        service: DynamicSchedulerService,
        properties: AgentProperties
    ): CreateScheduledJobTool =
        CreateScheduledJobTool(service, properties.scheduler.defaultTimezone)

    @Bean
    @ConditionalOnMissingBean
    fun listScheduledJobsTool(service: DynamicSchedulerService): ListScheduledJobsTool =
        ListScheduledJobsTool(service)

    @Bean
    @ConditionalOnMissingBean
    fun updateScheduledJobTool(service: DynamicSchedulerService): UpdateScheduledJobTool =
        UpdateScheduledJobTool(service)

    @Bean
    @ConditionalOnMissingBean
    fun deleteScheduledJobTool(service: DynamicSchedulerService): DeleteScheduledJobTool =
        DeleteScheduledJobTool(service)
}
