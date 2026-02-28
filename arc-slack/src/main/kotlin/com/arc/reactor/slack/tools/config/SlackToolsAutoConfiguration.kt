package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.client.SlackApiClient
import com.arc.reactor.slack.tools.health.SlackApiHealthIndicator
import com.arc.reactor.slack.tools.health.SlackToolsReadinessHealthIndicator
import com.arc.reactor.slack.tools.observability.ToolObservabilityAspect
import com.arc.reactor.slack.tools.tool.AddReactionTool
import com.arc.reactor.slack.tools.tool.FindChannelTool
import com.arc.reactor.slack.tools.tool.FindUserTool
import com.arc.reactor.slack.tools.tool.GetUserInfoTool
import com.arc.reactor.slack.tools.tool.InMemoryWriteOperationIdempotencyService
import com.arc.reactor.slack.tools.tool.ListChannelsTool
import com.arc.reactor.slack.tools.tool.ReadMessagesTool
import com.arc.reactor.slack.tools.tool.ReadThreadRepliesTool
import com.arc.reactor.slack.tools.tool.ReplyToThreadTool
import com.arc.reactor.slack.tools.tool.SearchMessagesTool
import com.arc.reactor.slack.tools.tool.SendMessageTool
import com.arc.reactor.slack.tools.tool.UploadFileTool
import com.arc.reactor.slack.tools.tool.WriteOperationIdempotencyService
import com.arc.reactor.slack.tools.usecase.AddReactionUseCase
import com.arc.reactor.slack.tools.usecase.FindChannelUseCase
import com.arc.reactor.slack.tools.usecase.FindUserUseCase
import com.arc.reactor.slack.tools.usecase.GetUserInfoUseCase
import com.arc.reactor.slack.tools.usecase.ListChannelsUseCase
import com.arc.reactor.slack.tools.usecase.ReadMessagesUseCase
import com.arc.reactor.slack.tools.usecase.ReadThreadRepliesUseCase
import com.arc.reactor.slack.tools.usecase.ReplyToThreadUseCase
import com.arc.reactor.slack.tools.usecase.SearchMessagesUseCase
import com.arc.reactor.slack.tools.usecase.SendMessageUseCase
import com.arc.reactor.slack.tools.usecase.UploadFileUseCase
import com.arc.reactor.tool.LocalToolFilter
import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.slack.tools", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(SlackToolsProperties::class)
class SlackToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun slackMethodsClient(properties: SlackToolsProperties): MethodsClient =
        Slack.getInstance().methods(properties.botToken)

    @Bean
    @ConditionalOnMissingBean
    fun slackScopeProvider(methodsClient: MethodsClient): SlackScopeProvider =
        SlackAuthTestScopeProvider(methodsClient)

    @Bean
    @ConditionalOnMissingBean
    fun toolExposureResolver(
        properties: SlackToolsProperties,
        slackScopeProvider: SlackScopeProvider
    ): ToolExposureResolver = ToolExposureResolver(properties, slackScopeProvider)

    @Bean
    @ConditionalOnMissingBean(name = ["slackScopeAwareLocalToolFilter"])
    fun slackScopeAwareLocalToolFilter(
        toolExposureResolver: ToolExposureResolver
    ): LocalToolFilter = createSlackScopeAwareLocalToolFilter(toolExposureResolver)

    @Bean
    @ConditionalOnMissingBean
    fun writeOperationIdempotencyService(properties: SlackToolsProperties): WriteOperationIdempotencyService =
        InMemoryWriteOperationIdempotencyService(properties)

    @Bean
    @ConditionalOnMissingBean
    fun slackApiClient(
        methodsClient: MethodsClient,
        properties: SlackToolsProperties,
        meterRegistry: MeterRegistry?
    ): SlackApiClient = SlackApiClient(
        client = methodsClient,
        properties = properties,
        meterRegistry = meterRegistry
    )

    @Bean
    @ConditionalOnMissingBean
    fun sendMessageUseCase(slackApiClient: SlackApiClient): SendMessageUseCase = SendMessageUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun replyToThreadUseCase(slackApiClient: SlackApiClient): ReplyToThreadUseCase = ReplyToThreadUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun listChannelsUseCase(slackApiClient: SlackApiClient): ListChannelsUseCase = ListChannelsUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun findChannelUseCase(slackApiClient: SlackApiClient): FindChannelUseCase = FindChannelUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun readMessagesUseCase(slackApiClient: SlackApiClient): ReadMessagesUseCase = ReadMessagesUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun readThreadRepliesUseCase(slackApiClient: SlackApiClient): ReadThreadRepliesUseCase =
        ReadThreadRepliesUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun addReactionUseCase(slackApiClient: SlackApiClient): AddReactionUseCase = AddReactionUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun getUserInfoUseCase(slackApiClient: SlackApiClient): GetUserInfoUseCase = GetUserInfoUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun findUserUseCase(slackApiClient: SlackApiClient): FindUserUseCase = FindUserUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun searchMessagesUseCase(slackApiClient: SlackApiClient): SearchMessagesUseCase = SearchMessagesUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun uploadFileUseCase(slackApiClient: SlackApiClient): UploadFileUseCase = UploadFileUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    fun sendMessageTool(
        sendMessageUseCase: SendMessageUseCase,
        idempotencyService: WriteOperationIdempotencyService
    ): SendMessageTool = SendMessageTool(sendMessageUseCase, idempotencyService)

    @Bean
    @ConditionalOnMissingBean
    fun replyToThreadTool(
        replyToThreadUseCase: ReplyToThreadUseCase,
        idempotencyService: WriteOperationIdempotencyService
    ): ReplyToThreadTool = ReplyToThreadTool(replyToThreadUseCase, idempotencyService)

    @Bean
    @ConditionalOnMissingBean
    fun listChannelsTool(listChannelsUseCase: ListChannelsUseCase): ListChannelsTool = ListChannelsTool(listChannelsUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun findChannelTool(findChannelUseCase: FindChannelUseCase): FindChannelTool = FindChannelTool(findChannelUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun readMessagesTool(readMessagesUseCase: ReadMessagesUseCase): ReadMessagesTool = ReadMessagesTool(readMessagesUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun readThreadRepliesTool(readThreadRepliesUseCase: ReadThreadRepliesUseCase): ReadThreadRepliesTool =
        ReadThreadRepliesTool(readThreadRepliesUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun addReactionTool(
        addReactionUseCase: AddReactionUseCase,
        idempotencyService: WriteOperationIdempotencyService
    ): AddReactionTool = AddReactionTool(addReactionUseCase, idempotencyService)

    @Bean
    @ConditionalOnMissingBean
    fun getUserInfoTool(getUserInfoUseCase: GetUserInfoUseCase): GetUserInfoTool = GetUserInfoTool(getUserInfoUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun findUserTool(findUserUseCase: FindUserUseCase): FindUserTool = FindUserTool(findUserUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun searchMessagesTool(searchMessagesUseCase: SearchMessagesUseCase): SearchMessagesTool =
        SearchMessagesTool(searchMessagesUseCase)

    @Bean
    @ConditionalOnMissingBean
    fun uploadFileTool(
        uploadFileUseCase: UploadFileUseCase,
        idempotencyService: WriteOperationIdempotencyService
    ): UploadFileTool = UploadFileTool(uploadFileUseCase, idempotencyService)

    @Bean
    @ConditionalOnMissingBean
    fun slackApiHealthIndicator(methodsClient: MethodsClient): SlackApiHealthIndicator = SlackApiHealthIndicator(methodsClient)

    @Bean
    @ConditionalOnMissingBean
    fun slackToolsReadinessHealthIndicator(
        sendMessageTool: SendMessageTool,
        replyToThreadTool: ReplyToThreadTool,
        listChannelsTool: ListChannelsTool,
        findChannelTool: FindChannelTool,
        readMessagesTool: ReadMessagesTool,
        readThreadRepliesTool: ReadThreadRepliesTool,
        addReactionTool: AddReactionTool,
        getUserInfoTool: GetUserInfoTool,
        findUserTool: FindUserTool,
        searchMessagesTool: SearchMessagesTool,
        uploadFileTool: UploadFileTool
    ): SlackToolsReadinessHealthIndicator = SlackToolsReadinessHealthIndicator(
        tools = listOf(
            sendMessageTool,
            replyToThreadTool,
            listChannelsTool,
            findChannelTool,
            readMessagesTool,
            readThreadRepliesTool,
            addReactionTool,
            getUserInfoTool,
            findUserTool,
            searchMessagesTool,
            uploadFileTool
        )
    )

    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean
    fun toolObservabilityAspect(meterRegistry: MeterRegistry): ToolObservabilityAspect =
        ToolObservabilityAspect(meterRegistry)
}
