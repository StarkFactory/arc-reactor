package com.arc.reactor.slack.tools.usecase

import com.arc.reactor.slack.tools.client.*

// ── Slack 도구 유스케이스 ──
// 각 유스케이스는 [SlackApiClient]에 단순 위임하는 얇은 계층이다.
// 도구(Tool)와 API 클라이언트 사이에 비즈니스 로직 확장 지점을 제공한다.

/** 채널에 메시지를 전송하는 유스케이스. */
class SendMessageUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, text: String, threadTs: String?): PostMessageResult =
        slackClient.postMessage(channelId, text, threadTs)
}

/** 스레드에 답장하는 유스케이스. */
class ReplyToThreadUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, threadTs: String, text: String): PostMessageResult =
        slackClient.postMessage(channelId, text, threadTs)
}

/** 채널 목록을 조회하는 유스케이스. */
class ListChannelsUseCase(private val slackClient: SlackApiClient) {
    fun execute(limit: Int, cursor: String?): ConversationsListResult =
        slackClient.conversationsList(limit, cursor)
}

/** 이름으로 채널을 검색하는 유스케이스. */
class FindChannelUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, exactMatch: Boolean, limit: Int): FindChannelsResult =
        slackClient.findChannelsByName(query, exactMatch, limit)
}

/** 채널 메시지 이력을 읽는 유스케이스. */
class ReadMessagesUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, limit: Int, cursor: String?): ConversationHistoryResult =
        slackClient.conversationHistory(channelId, limit, cursor)
}

/** 스레드 답글을 읽는 유스케이스. */
class ReadThreadRepliesUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, threadTs: String, limit: Int, cursor: String?): ConversationHistoryResult =
        slackClient.threadReplies(channelId, threadTs, limit, cursor)
}

/** 이모지 리액션을 추가하는 유스케이스. */
class AddReactionUseCase(private val slackClient: SlackApiClient) {
    fun execute(channelId: String, timestamp: String, emoji: String): SimpleResult =
        slackClient.addReaction(channelId, timestamp, emoji)
}

/** 사용자 정보를 조회하는 유스케이스. */
class GetUserInfoUseCase(private val slackClient: SlackApiClient) {
    fun execute(userId: String): UserInfoResult =
        slackClient.getUserInfo(userId)
}

/** 이름으로 사용자를 검색하는 유스케이스. */
class FindUserUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, exactMatch: Boolean, limit: Int): FindUsersResult =
        slackClient.findUsersByName(query, exactMatch, limit)
}

/** 메시지를 검색하는 유스케이스. */
class SearchMessagesUseCase(private val slackClient: SlackApiClient) {
    fun execute(query: String, count: Int, page: Int): SearchMessagesResult =
        slackClient.searchMessages(query, count, page)
}

/** 파일을 업로드하는 유스케이스. */
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

/** Canvas를 생성하는 유스케이스. */
class CreateCanvasUseCase(private val slackClient: SlackApiClient) {
    fun execute(title: String, markdown: String): CanvasCreateResult =
        slackClient.createCanvas(title = title, markdown = markdown)
}

/** Canvas에 내용을 추가하는 유스케이스. */
class AppendCanvasUseCase(private val slackClient: SlackApiClient) {
    fun execute(canvasId: String, markdown: String): CanvasEditResult =
        slackClient.appendCanvas(canvasId = canvasId, markdown = markdown)
}
