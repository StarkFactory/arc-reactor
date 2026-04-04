package com.arc.reactor.slack.tools.config

import com.arc.reactor.slack.tools.client.SlackApiClient
import com.arc.reactor.slack.tools.health.SlackApiHealthIndicator
import com.arc.reactor.slack.tools.health.SlackToolsReadinessHealthIndicator
import com.arc.reactor.slack.tools.observability.ToolObservabilityAspect
import com.arc.reactor.slack.tools.tool.AddReactionTool
import com.arc.reactor.slack.tools.tool.AppendCanvasTool
import com.arc.reactor.slack.tools.tool.CanvasOwnershipPolicyService
import com.arc.reactor.slack.tools.tool.CreateCanvasTool
import com.arc.reactor.slack.tools.tool.FindChannelTool
import com.arc.reactor.slack.tools.tool.FindUserTool
import com.arc.reactor.slack.tools.tool.GetUserInfoTool
import com.arc.reactor.slack.tools.tool.InMemoryCanvasOwnershipPolicyService
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
import com.arc.reactor.slack.tools.usecase.AppendCanvasUseCase
import com.arc.reactor.slack.tools.usecase.CreateCanvasUseCase
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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Slack 도구 모듈 자동 설정.
 *
 * `arc.reactor.slack.tools.enabled=true`일 때 활성화되며,
 * [SlackApiClient], 각 도구(Tool), 유스케이스(UseCase), 헬스 인디케이터,
 * 관측성(Observability) 빈을 등록한다.
 *
 * 모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 사용자 정의 빈으로 교체 가능하다.
 *
 * @see SlackToolsProperties
 * @see SlackApiClient
 * @see ToolObservabilityAspect
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.slack.tools", name = ["enabled"],
    havingValue = "true", matchIfMissing = true
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
        properties: SlackToolsProperties,
        toolExposureResolver: ToolExposureResolver
    ): LocalToolFilter = createSlackScopeAwareLocalToolFilter(properties, toolExposureResolver)

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
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack.tools.canvas",
        name = ["enabled"],
        havingValue = "true"
    )
    fun canvasOwnershipPolicyService(properties: SlackToolsProperties): CanvasOwnershipPolicyService =
        InMemoryCanvasOwnershipPolicyService(properties.canvas)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack.tools.canvas",
        name = ["enabled"],
        havingValue = "true"
    )
    fun createCanvasUseCase(slackApiClient: SlackApiClient): CreateCanvasUseCase =
        CreateCanvasUseCase(slackApiClient)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack.tools.canvas",
        name = ["enabled"],
        havingValue = "true"
    )
    fun appendCanvasUseCase(slackApiClient: SlackApiClient): AppendCanvasUseCase =
        AppendCanvasUseCase(slackApiClient)

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
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack.tools.canvas",
        name = ["enabled"],
        havingValue = "true"
    )
    fun createCanvasTool(
        createCanvasUseCase: CreateCanvasUseCase,
        canvasOwnershipPolicyService: CanvasOwnershipPolicyService,
        idempotencyService: WriteOperationIdempotencyService
    ): CreateCanvasTool = CreateCanvasTool(
        createCanvasUseCase = createCanvasUseCase,
        canvasOwnershipPolicyService = canvasOwnershipPolicyService,
        idempotencyService = idempotencyService
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "arc.reactor.slack.tools.canvas",
        name = ["enabled"],
        havingValue = "true"
    )
    fun appendCanvasTool(
        appendCanvasUseCase: AppendCanvasUseCase,
        canvasOwnershipPolicyService: CanvasOwnershipPolicyService,
        idempotencyService: WriteOperationIdempotencyService
    ): AppendCanvasTool = AppendCanvasTool(
        appendCanvasUseCase = appendCanvasUseCase,
        canvasOwnershipPolicyService = canvasOwnershipPolicyService,
        idempotencyService = idempotencyService
    )

    @Bean
    @ConditionalOnMissingBean
    fun slackApiHealthIndicator(
        methodsClient: MethodsClient,
        properties: SlackToolsProperties
    ): SlackApiHealthIndicator = SlackApiHealthIndicator(methodsClient, properties)

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
        uploadFileTool: UploadFileTool,
        createCanvasToolProvider: ObjectProvider<CreateCanvasTool>,
        appendCanvasToolProvider: ObjectProvider<AppendCanvasTool>
    ): SlackToolsReadinessHealthIndicator = SlackToolsReadinessHealthIndicator(
        tools = buildList {
            addAll(
                listOf(
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
            createCanvasToolProvider.ifAvailable?.let(::add)
            appendCanvasToolProvider.ifAvailable?.let(::add)
        }
    )

    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean
    fun toolObservabilityAspect(meterRegistry: MeterRegistry): ToolObservabilityAspect =
        ToolObservabilityAspect(meterRegistry)
}
