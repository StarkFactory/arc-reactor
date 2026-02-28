package com.arc.reactor.slack.tools.usecase

import com.arc.reactor.slack.tools.client.*

class SendMessageUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, text: String, threadTs: String?): PostMessageResult =
        slackClient.postMessage(channelId, text, threadTs)
}

class ReplyToThreadUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, threadTs: String, text: String): PostMessageResult =
        slackClient.postMessage(channelId, text, threadTs)
}

class ListChannelsUseCase(private val slackClient: SlackApiClient) {
    fun execute(limit: Int, cursor: String?): ConversationsListResult =
        slackClient.conversationsList(limit, cursor)
}

class FindChannelUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, exactMatch: Boolean, limit: Int): FindChannelsResult =
        slackClient.findChannelsByName(query, exactMatch, limit)
}

class ReadMessagesUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, limit: Int, cursor: String?): ConversationHistoryResult =
        slackClient.conversationHistory(channelId, limit, cursor)
}

class ReadThreadRepliesUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, threadTs: String, limit: Int, cursor: String?): ConversationHistoryResult =
        slackClient.threadReplies(channelId, threadTs, limit, cursor)
}

class AddReactionUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, timestamp: String, emoji: String): SimpleResult =
        slackClient.addReaction(channelId, timestamp, emoji)
}

class GetUserInfoUseCase(private val slackClient: SlackApiClient) {
    fun execute(userId: String): UserInfoResult =
        slackClient.getUserInfo(userId)
}

class FindUserUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, exactMatch: Boolean, limit: Int): FindUsersResult =
        slackClient.findUsersByName(query, exactMatch, limit)
}

class SearchMessagesUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, count: Int, page: Int): SearchMessagesResult =
        slackClient.searchMessages(query, count, page)
}

class UploadFileUseCase(private val slackClient: SlackApiClient) {
    fun execute(
        channelId: String,
        filename: String,
        content: String,
        title: String?,
        initialComment: String?,
        threadTs: String?
    ): UploadFileResult = slackClient.uploadFile(
        channelId = channelId,
        filename = filename,
        content = content,
        title = title,
        initialComment = initialComment,
        threadTs = threadTs
    )
}
