package com.arc.reactor.slack.tools.usecase

import com.arc.reactor.slack.tools.client.CanvasCreateResult
import com.arc.reactor.slack.tools.client.CanvasEditResult
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

/**
 * 각 UseCase 클래스의 [SlackApiClient] 위임 동작 테스트.
 *
 * 모든 UseCase가 비즈니스 로직 없이 SlackApiClient에 올바르게 위임하고,
 * 정확히 한 번만 호출되는지 검증한다.
 */
class UseCaseDelegationTest {

    private val slackClient = mockk<SlackApiClient>()

    @Test
    fun `SendMessageUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = SendMessageUseCase(slackClient)
        val expected = PostMessageResult(ok = true, ts = "1234.5678", channel = "C123")
        every { slackClient.postMessage("C123", "hello", null) } returns expected

        val result = useCase.execute("C123", "hello", null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.postMessage("C123", "hello", null) }
    }

    @Test
    fun `ReplyToThreadUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = ReplyToThreadUseCase(slackClient)
        val expected = PostMessageResult(ok = true, ts = "1234.9999", channel = "C123")
        every { slackClient.postMessage("C123", "reply", "1234.5678") } returns expected

        val result = useCase.execute("C123", "1234.5678", "reply")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.postMessage("C123", "reply", "1234.5678") }
    }

    @Test
    fun `ListChannelsUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = ListChannelsUseCase(slackClient)
        val expected = ConversationsListResult(ok = true)
        every { slackClient.conversationsList(100, null) } returns expected

        val result = useCase.execute(100, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.conversationsList(100, null) }
    }

    @Test
    fun `FindChannelUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = FindChannelUseCase(slackClient)
        val expected = FindChannelsResult(ok = true, query = "gen")
        every { slackClient.findChannelsByName("gen", false, 10) } returns expected

        val result = useCase.execute("gen", false, 10)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.findChannelsByName("gen", false, 10) }
    }

    @Test
    fun `ReadMessagesUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = ReadMessagesUseCase(slackClient)
        val expected = ConversationHistoryResult(ok = true)
        every { slackClient.conversationHistory("C123", 10, null) } returns expected

        val result = useCase.execute("C123", 10, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.conversationHistory("C123", 10, null) }
    }

    @Test
    fun `ReadThreadRepliesUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = ReadThreadRepliesUseCase(slackClient)
        val expected = ConversationHistoryResult(ok = true)
        every { slackClient.threadReplies("C123", "1234.5678", 10, null) } returns expected

        val result = useCase.execute("C123", "1234.5678", 10, null)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.threadReplies("C123", "1234.5678", 10, null) }
    }

    @Test
    fun `AddReactionUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = AddReactionUseCase(slackClient)
        val expected = SimpleResult(ok = true)
        every { slackClient.addReaction("C123", "1234.5678", "thumbsup") } returns expected

        val result = useCase.execute("C123", "1234.5678", "thumbsup")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.addReaction("C123", "1234.5678", "thumbsup") }
    }

    @Test
    fun `GetUserInfoUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = GetUserInfoUseCase(slackClient)
        val expected = UserInfoResult(ok = true, user = SlackUser(id = "U123", name = "john"))
        every { slackClient.getUserInfo("U123") } returns expected

        val result = useCase.execute("U123")

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.getUserInfo("U123") }
    }

    @Test
    fun `FindUserUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = FindUserUseCase(slackClient)
        val expected = FindUsersResult(ok = true, query = "john")
        every { slackClient.findUsersByName("john", false, 10) } returns expected

        val result = useCase.execute("john", false, 10)

        assertEquals(expected, result)
        verify(exactly = 1) { slackClient.findUsersByName("john", false, 10) }
    }

    @Test
    fun `SearchMessagesUseCase은(는) SlackApiClient에 위임한다`() {
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
    fun `UploadFileUseCase은(는) SlackApiClient에 위임한다`() {
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

    @Test
    fun `CreateCanvasUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = CreateCanvasUseCase(slackClient)
        val expected = CanvasCreateResult(ok = true, canvasId = "F_CANVAS123")
        every { slackClient.createCanvas(title = "Sprint Notes", markdown = "# Notes") } returns expected

        val result = useCase.execute("Sprint Notes", "# Notes")

        assertEquals(expected, result) { "CreateCanvasUseCase가 SlackApiClient.createCanvas()에 위임한 결과와 달라야 하지 않는다" }
        verify(exactly = 1) { slackClient.createCanvas(title = "Sprint Notes", markdown = "# Notes") }
    }

    @Test
    fun `AppendCanvasUseCase은(는) SlackApiClient에 위임한다`() {
        val useCase = AppendCanvasUseCase(slackClient)
        val expected = CanvasEditResult(ok = true, canvasId = "F_CANVAS123")
        every { slackClient.appendCanvas(canvasId = "F_CANVAS123", markdown = "## New Section") } returns expected

        val result = useCase.execute("F_CANVAS123", "## New Section")

        assertEquals(expected, result) { "AppendCanvasUseCase가 SlackApiClient.appendCanvas()에 위임한 결과와 달라야 하지 않는다" }
        verify(exactly = 1) { slackClient.appendCanvas(canvasId = "F_CANVAS123", markdown = "## New Section") }
    }
}
