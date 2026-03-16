package com.arc.reactor.slack.config

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.feedback.FeedbackStore
import com.arc.reactor.mcp.McpManager
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.slack.handler.DefaultSlackCommandHandler
import com.arc.reactor.slack.handler.DefaultSlackEventHandler
import com.arc.reactor.slack.handler.SlackCommandHandler
import com.arc.reactor.slack.handler.SlackEventHandler
import com.arc.reactor.slack.handler.SlackReminderScheduler
import com.arc.reactor.slack.handler.SlackReminderStore
import com.arc.reactor.slack.gateway.SlackSocketModeGateway
import com.arc.reactor.slack.metrics.MicrometerSlackMetricsRecorder
import com.arc.reactor.slack.metrics.NoOpSlackMetricsRecorder
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.proactive.InMemoryProactiveChannelStore
import com.arc.reactor.slack.proactive.ProactiveChannelStore
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.security.SlackSignatureVerifier
import com.arc.reactor.slack.security.SlackSignatureWebFilter
import com.arc.reactor.slack.session.SlackBotResponseTracker
import com.arc.reactor.slack.session.SlackThreadTracker
import com.arc.reactor.slack.adapter.SlackMessageSenderAdapter
import com.arc.reactor.slack.service.SlackMessagingService
import com.arc.reactor.slack.service.SlackUserEmailResolver
import com.arc.reactor.scheduler.SlackMessageSender
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.WebFilter

/**
 * Slack 통합 모듈 자동 설정.
 *
 * `arc.reactor.slack.enabled=true`일 때 활성화된다.
 * 모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 사용자 정의 구현으로 교체 가능하다.
 *
 * 등록되는 주요 빈:
 * - [SlackMessagingService]: Slack Web API 메시지 전송
 * - [SlackEventProcessor] / [SlackCommandProcessor]: 이벤트·명령 비동기 처리
 * - [SlackSocketModeGateway]: Socket Mode WebSocket 게이트웨이 (전송 모드가 socket_mode일 때)
 * - [SlackSignatureVerifier] / [SlackSignatureWebFilter]: 요청 서명 검증 (Events API 모드)
 * - [SlackThreadTracker] / [SlackBotResponseTracker]: 스레드·응답 추적
 * - [SlackMetricsRecorder]: Micrometer 메트릭 기록
 *
 * @see SlackProperties
 * @see SlackToolsAutoConfiguration
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.slack", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(SlackProperties::class)
class SlackAutoConfiguration {

    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(SlackMetricsRecorder::class)
    fun micrometerSlackMetricsRecorder(registry: MeterRegistry): SlackMetricsRecorder =
        MicrometerSlackMetricsRecorder(registry)

    @Bean
    @ConditionalOnMissingBean(value = [SlackMetricsRecorder::class, MeterRegistry::class])
    fun slackMetricsRecorder(): SlackMetricsRecorder = NoOpSlackMetricsRecorder()

    @Bean
    @ConditionalOnMissingBean
    fun slackSignatureVerifier(properties: SlackProperties): SlackSignatureVerifier =
        SlackSignatureVerifier(
            signingSecret = properties.signingSecret,
            timestampToleranceSeconds = properties.timestampToleranceSeconds
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack",
        name = ["thread-tracking-enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun slackThreadTracker(properties: SlackProperties): SlackThreadTracker =
        SlackThreadTracker(
            ttlSeconds = properties.threadTrackingTtlSeconds,
            maxEntries = properties.threadTrackingMaxEntries
        )


    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack",
        name = ["reaction-feedback-enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun slackBotResponseTracker(properties: SlackProperties): SlackBotResponseTracker =
        SlackBotResponseTracker(
            ttlSeconds = properties.threadTrackingTtlSeconds,
            maxEntries = properties.threadTrackingMaxEntries
        )

    @Bean
    @ConditionalOnMissingBean
    fun slackReminderStore(): SlackReminderStore = SlackReminderStore()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = [SlackReminderStore::class, SlackMessagingService::class])
    fun slackReminderScheduler(
        reminderStore: SlackReminderStore,
        messagingService: SlackMessagingService
    ): SlackReminderScheduler = SlackReminderScheduler(reminderStore, messagingService)

    @Bean
    @ConditionalOnMissingBean
    fun proactiveChannelStore(properties: SlackProperties): ProactiveChannelStore {
        val store = InMemoryProactiveChannelStore()
        store.seedFromConfig(properties.proactiveChannelIds)
        return store
    }

    @Bean("slackSignatureWebFilter")
    @ConditionalOnMissingBean(name = ["slackSignatureWebFilter"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack", name = ["signature-verification-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack", name = ["transport-mode"],
        havingValue = "events_api", matchIfMissing = true
    )
    fun slackSignatureWebFilter(
        verifier: SlackSignatureVerifier,
        objectMapper: ObjectProvider<ObjectMapper>
    ): WebFilter = SlackSignatureWebFilter(
        verifier,
        objectMapper.ifAvailable ?: ObjectMapper()
    )

    @Bean
    @ConditionalOnMissingBean
    fun slackMessagingService(
        properties: SlackProperties,
        metricsRecorder: SlackMetricsRecorder
    ): SlackMessagingService =
        SlackMessagingService(
            botToken = properties.botToken,
            maxApiRetries = properties.apiMaxRetries,
            retryDefaultDelayMs = properties.apiRetryDefaultDelayMs,
            metricsRecorder = metricsRecorder
        )

    @Bean
    @ConditionalOnMissingBean
    fun slackMessageSenderAdapter(
        messagingService: SlackMessagingService
    ): SlackMessageSender = SlackMessageSenderAdapter(messagingService)

    @Bean
    @ConditionalOnMissingBean
    fun slackUserEmailResolver(properties: SlackProperties): SlackUserEmailResolver =
        SlackUserEmailResolver(
            botToken = properties.botToken,
            enabled = properties.userEmailResolutionEnabled,
            cacheTtlSeconds = properties.userEmailCacheTtlSeconds,
            cacheMaxEntries = properties.userEmailCacheMaxEntries
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun slackEventHandler(
        agentExecutor: AgentExecutor,
        messagingService: SlackMessagingService,
        agentProperties: ObjectProvider<AgentProperties>,
        threadTracker: ObjectProvider<SlackThreadTracker>,
        userEmailResolver: ObjectProvider<SlackUserEmailResolver>,
        mcpManager: ObjectProvider<McpManager>,
        feedbackStore: ObjectProvider<FeedbackStore>,
        botResponseTracker: ObjectProvider<SlackBotResponseTracker>,
        userMemoryManager: ObjectProvider<UserMemoryManager>
    ): SlackEventHandler = DefaultSlackEventHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        defaultProvider = agentProperties.ifAvailable?.llm?.defaultProvider ?: "configured backend model",
        threadTracker = threadTracker.ifAvailable,
        userEmailResolver = userEmailResolver.ifAvailable,
        mcpManager = mcpManager.ifAvailable,
        feedbackStore = feedbackStore.ifAvailable,
        botResponseTracker = botResponseTracker.ifAvailable,
        userMemoryManager = userMemoryManager.ifAvailable
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor::class)
    fun slackCommandHandler(
        agentExecutor: AgentExecutor,
        messagingService: SlackMessagingService,
        agentProperties: ObjectProvider<AgentProperties>,
        threadTracker: ObjectProvider<SlackThreadTracker>,
        reminderStore: ObjectProvider<SlackReminderStore>,
        userEmailResolver: ObjectProvider<SlackUserEmailResolver>,
        mcpManager: ObjectProvider<McpManager>,
        userMemoryManager: ObjectProvider<UserMemoryManager>
    ): SlackCommandHandler = DefaultSlackCommandHandler(
        agentExecutor = agentExecutor,
        messagingService = messagingService,
        defaultProvider = agentProperties.ifAvailable?.llm?.defaultProvider ?: "configured backend model",
        threadTracker = threadTracker.ifAvailable,
        reminderStore = reminderStore.ifAvailable,
        userEmailResolver = userEmailResolver.ifAvailable,
        mcpManager = mcpManager.ifAvailable,
        userMemoryManager = userMemoryManager.ifAvailable
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SlackEventHandler::class)
    fun slackEventProcessor(
        eventHandler: SlackEventHandler,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder,
        properties: SlackProperties,
        threadTracker: ObjectProvider<SlackThreadTracker>,
        proactiveChannelStore: ObjectProvider<ProactiveChannelStore>,
        botResponseTracker: ObjectProvider<SlackBotResponseTracker>
    ): SlackEventProcessor = SlackEventProcessor(
        eventHandler = eventHandler,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder,
        properties = properties,
        threadTracker = threadTracker.ifAvailable,
        proactiveChannelStore = proactiveChannelStore.ifAvailable,
        botResponseTracker = botResponseTracker.ifAvailable
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SlackCommandHandler::class)
    fun slackCommandProcessor(
        commandHandler: SlackCommandHandler,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder,
        properties: SlackProperties
    ): SlackCommandProcessor = SlackCommandProcessor(
        commandHandler = commandHandler,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder,
        properties = properties
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = [SlackEventProcessor::class, SlackCommandProcessor::class])
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack",
        name = ["transport-mode"],
        havingValue = "socket_mode"
    )
    fun slackSocketModeGateway(
        properties: SlackProperties,
        objectMapper: ObjectMapper,
        commandProcessor: SlackCommandProcessor,
        eventProcessor: SlackEventProcessor,
        messagingService: SlackMessagingService,
        metricsRecorder: SlackMetricsRecorder
    ): SlackSocketModeGateway = SlackSocketModeGateway(
        properties = properties,
        objectMapper = objectMapper,
        commandProcessor = commandProcessor,
        eventProcessor = eventProcessor,
        messagingService = messagingService,
        metricsRecorder = metricsRecorder
    )

}
