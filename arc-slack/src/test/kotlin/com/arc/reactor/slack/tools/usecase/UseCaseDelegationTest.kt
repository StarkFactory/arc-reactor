package com.arc.reactor.slack.tools.usecase

import com.arc.reactor.slack.tools.client.ConversationHistoryResult
import com.arc.reactor.slack.tools.client.ConversationsListResult
import com.arc.reactor.slack.tools.client.FindChannelsResult
import com.arc.reactor.slack.tools.client.FindUsersResult
import com.arc.reactor.slack.tools.client.PostMessageResult
import com.arc.reactor.slack.tools.client.SearchMessagesResult
import com.arc.reactor.slack.tools.client.SimpleResult
import com.arc.reactor.slack.tools.client.SlackApiClient
import com.arc.reactor.slack.tools.client.SlackSearchMessage
import com.arc.reactor.slack.tools.client.SlackUser
import com.arc.reactor.slack.tools.client.UploadFileResult
import com.arc.reactor.slack.tools.client.UserInfoResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UseCaseDelegationTest {

    private val slackClient = mockk<SlackApiClient>()

    @Test
    fun `SendMessageUseCase delegates to SlackApiClient`() {
        val useCase = SendMessageUseCase(slackClient)
        val expected = PostMessageResult(ok = true, ts = "1234.5678", channel = "C123")
        every { slackClient.postMessage("C123", "hello", null) } returns expected

        val result = useCase.execute("C123", "hello", null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.postMessage("C123", "hello", null) }
    }

    @Test
    fun `ReplyToThreadUseCase delegates to SlackApiClient`() {
        val useCase = ReplyToThreadUseCase(slackClient)
        val expected = PostMessageResult(ok = true, ts = "1234.9999", channel = "C123")
        every { slackClient.postMessage("C123", "reply", "1234.5678") } returns expected

        val result = useCase.execute("C123", "1234.5678", "reply")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.postMessage("C123", "reply", "1234.5678") }
    }

    @Test
    fun `ListChannelsUseCase delegates to SlackApiClient`() {
        val useCase = ListChannelsUseCase(slackClient)
        val expected = ConversationsListResult(ok = true)
        every { slackClient.conversationsList(100, null) } returns expected

        val result = useCase.execute(100, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.conversationsList(100, null) }
    }

    @Test
    fun `FindChannelUseCase delegates to SlackApiClient`() {
        val useCase = FindChannelUseCase(slackClient)
        val expected = FindChannelsResult(ok = true, query = "gen")
        every { slackClient.findChannelsByName("gen", false, 10) } returns expected

        val result = useCase.execute("gen", false, 10)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.findChannelsByName("gen", false, 10) }
    }

    @Test
    fun `ReadMessagesUseCase delegates to SlackApiClient`() {
        val useCase = ReadMessagesUseCase(slackClient)
        val expected = ConversationHistoryResult(ok = true)
        every { slackClient.conversationHistory("C123", 10, null) } returns expected

        val result = useCase.execute("C123", 10, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.conversationHistory("C123", 10, null) }
    }

    @Test
    fun `ReadThreadRepliesUseCase delegates to SlackApiClient`() {
        val useCase = ReadThreadRepliesUseCase(slackClient)
        val expected = ConversationHistoryResult(ok = true)
        every { slackClient.threadReplies("C123", "1234.5678", 10, null) } returns expected

        val result = useCase.execute("C123", "1234.5678", 10, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.threadReplies("C123", "1234.5678", 10, null) }
    }

    @Test
    fun `AddReactionUseCase delegates to SlackApiClient`() {
        val useCase = AddReactionUseCase(slackClient)
        val expected = SimpleResult(ok = true)
        every { slackClient.addReaction("C123", "1234.5678", "thumbsup") } returns expected

        val result = useCase.execute("C123", "1234.5678", "thumbsup")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.addReaction("C123", "1234.5678", "thumbsup") }
    }

    @Test
    fun `GetUserInfoUseCase delegates to SlackApiClient`() {
        val useCase = GetUserInfoUseCase(slackClient)
        val expected = UserInfoResult(ok = true, user = SlackUser(id = "U123", name = "john"))
        every { slackClient.getUserInfo("U123") } returns expected

        val result = useCase.execute("U123")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.getUserInfo("U123") }
    }

    @Test
    fun `FindUserUseCase delegates to SlackApiClient`() {
        val useCase = FindUserUseCase(slackClient)
        val expected = FindUsersResult(ok = true, query = "john")
        every { slackClient.findUsersByName("john", false, 10) } returns expected

        val result = useCase.execute("john", false, 10)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.findUsersByName("john", false, 10) }
    }

    @Test
    fun `SearchMessagesUseCase delegates to SlackApiClient`() {
        val useCase = SearchMessagesUseCase(slackClient)
        val expected = SearchMessagesResult(
            ok = true,
            query = "deploy",
            matches = listOf(SlackSearchMessage(text = "deploy done"))
        )
        every { slackClient.searchMessages("deploy", 20, 1) } returns expected

        val result = useCase.execute("deploy", 20, 1)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.searchMessages("deploy", 20, 1) }
    }

    @Test
    fun `UploadFileUseCase delegates to SlackApiClient`() {
        val useCase = UploadFileUseCase(slackClient)
        val expected = UploadFileResult(ok = true, fileId = "F123")
        every {
            slackClient.uploadFile(
                channelId = "C123",
                filename = "report.txt",
                content = "hello",
                title = "Report",
                initialComment = "uploaded",
                threadTs = "1234.5678"
            )
        } returns expected

        val result = useCase.execute(
            channelId = "C123",
            filename = "report.txt",
            content = "hello",
            title = "Report",
            initialComment = "uploaded",
            threadTs = "1234.5678"
        )

        assertEquals(expected, result)
        verify(exactly = 1) {
            slackClient.uploadFile(
                channelId = "C123",
                filename = "report.txt",
                content = "hello",
                title = "Report",
                initialComment = "uploaded",
                threadTs = "1234.5678"
            )
        }
    }
}
