package com.arc.reactor.slack.tools.client

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest
import com.slack.api.methods.request.conversations.ConversationsListRequest
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest
import com.slack.api.methods.request.files.FilesUploadV2Request
import com.slack.api.methods.request.reactions.ReactionsAddRequest
import com.slack.api.methods.request.search.SearchMessagesRequest
import com.slack.api.methods.request.users.UsersInfoRequest
import com.slack.api.methods.request.users.UsersListRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse
import com.slack.api.methods.response.conversations.ConversationsListResponse
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse
import com.slack.api.methods.response.files.FilesUploadV2Response
import com.slack.api.methods.response.reactions.ReactionsAddResponse
import com.slack.api.methods.response.search.SearchMessagesResponse
import com.slack.api.methods.response.users.UsersInfoResponse
import com.slack.api.methods.response.users.UsersListResponse
import com.slack.api.model.Channel
import com.slack.api.model.Conversation
import com.slack.api.model.ConversationType
import com.slack.api.model.File
import com.slack.api.model.Message
import com.slack.api.model.MatchedItem
import com.slack.api.model.ResponseMetadata
import com.slack.api.model.SearchResult
import com.slack.api.model.User
import com.arc.reactor.slack.tools.config.CircuitBreakerProperties
import com.arc.reactor.slack.tools.config.ResilienceProperties
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import com.arc.reactor.slack.tools.config.WriteIdempotencyProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SlackApiClientTest {

    private val methodsClient = mockk<MethodsClient>()
    private val client = slackApiClient(methodsClient)

    @Test
    fun `postMessage maps Slack response`() {
        val response = mockk<ChatPostMessageResponse>()
        every { response.isOk } returns true
        every { response.ts } returns "1234.5678"
        every { response.channel } returns "C123"
        every { response.error } returns null
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } returns response

        val result = client.postMessage("C123", "hello")

        assertTrue(result.ok)
        assertEquals("1234.5678", result.ts)
        assertEquals("C123", result.channel)
        assertNull(result.error)
    }

    @Test
    fun `addReaction maps Slack response`() {
        val response = mockk<ReactionsAddResponse>()
        every { response.isOk } returns false
        every { response.error } returns "already_reacted"
        every { methodsClient.reactionsAdd(any<ReactionsAddRequest>()) } returns response

        val result = client.addReaction("C123", "1234.5678", "thumbsup")

        assertFalse(result.ok)
        assertEquals("already_reacted", result.error)
    }

    @Test
    fun `conversationsList maps channels and cursor`() {
        val channel = mockk<Conversation>()
        every { channel.id } returns "C123"
        every { channel.name } returns "general"
        every { channel.topic } returns null
        every { channel.numOfMembers } returns 42
        every { channel.isPrivate } returns false

        val metadata = mockk<ResponseMetadata>()
        every { metadata.nextCursor } returns "next-123"

        val response = mockk<ConversationsListResponse>()
        every { response.isOk } returns true
        every { response.channels } returns listOf(channel)
        every { response.responseMetadata } returns metadata
        every { response.error } returns null
        val requestSlot = slot<ConversationsListRequest>()
        every { methodsClient.conversationsList(capture(requestSlot)) } returns response

        val result = client.conversationsList(limit = 20, cursor = "cursor")

        assertTrue(result.ok)
        assertEquals(1, result.channels.size)
        assertEquals("C123", result.channels.first().id)
        assertEquals("general", result.channels.first().name)
        assertEquals(42, result.channels.first().memberCount)
        assertEquals("next-123", result.nextCursor)
        assertEquals(
            listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL),
            requestSlot.captured.types
        )
    }

    @Test
    fun `conversationHistory maps messages`() {
        val message = mockk<Message>()
        every { message.user } returns "U123"
        every { message.text } returns "hello world"
        every { message.ts } returns "1234.5678"
        every { message.threadTs } returns null

        val response = mockk<ConversationsHistoryResponse>()
        every { response.isOk } returns true
        every { response.messages } returns listOf(message)
        every { response.responseMetadata } returns null
        every { response.error } returns null
        every { methodsClient.conversationsHistory(any<ConversationsHistoryRequest>()) } returns response

        val result = client.conversationHistory("C123", 10)

        assertTrue(result.ok)
        assertEquals(1, result.messages.size)
        assertEquals("U123", result.messages.first().user)
        assertEquals("hello world", result.messages.first().text)
    }

    @Test
    fun `threadReplies maps reply messages`() {
        val message = mockk<Message>()
        every { message.user } returns "U234"
        every { message.text } returns "thread reply"
        every { message.ts } returns "1234.5679"
        every { message.threadTs } returns "1234.5678"

        val response = mockk<ConversationsRepliesResponse>()
        every { response.isOk } returns true
        every { response.messages } returns listOf(message)
        every { response.responseMetadata } returns null
        every { response.error } returns null
        val requestSlot = slot<ConversationsRepliesRequest>()
        every { methodsClient.conversationsReplies(capture(requestSlot)) } returns response

        val result = client.threadReplies("C123", "1234.5678", 15)

        assertTrue(result.ok)
        assertEquals(1, result.messages.size)
        assertEquals("thread reply", result.messages.first().text)
        assertEquals("C123", requestSlot.captured.channel)
        assertEquals("1234.5678", requestSlot.captured.ts)
        assertEquals(15, requestSlot.captured.limit)
    }

    @Test
    fun `threadReplies excludes parent message`() {
        val parent = mockk<Message>()
        every { parent.user } returns "U100"
        every { parent.text } returns "parent"
        every { parent.ts } returns "1234.5678"
        every { parent.threadTs } returns "1234.5678"

        val reply = mockk<Message>()
        every { reply.user } returns "U200"
        every { reply.text } returns "reply"
        every { reply.ts } returns "1234.5679"
        every { reply.threadTs } returns "1234.5678"

        val response = mockk<ConversationsRepliesResponse>()
        every { response.isOk } returns true
        every { response.messages } returns listOf(parent, reply)
        every { response.responseMetadata } returns null
        every { response.error } returns null
        every { methodsClient.conversationsReplies(any<ConversationsRepliesRequest>()) } returns response

        val result = client.threadReplies("C123", "1234.5678", 10)

        assertTrue(result.ok)
        assertEquals(1, result.messages.size)
        assertEquals("reply", result.messages.first().text)
    }

    @Test
    fun `getUserInfo maps user fields`() {
        val user = mockk<User>()
        every { user.id } returns "U123"
        every { user.name } returns "john"
        every { user.realName } returns "John Doe"
        every { user.profile } returns null
        every { user.isBot } returns false

        val response = mockk<UsersInfoResponse>()
        every { response.isOk } returns true
        every { response.user } returns user
        every { response.error } returns null
        every { methodsClient.usersInfo(any<UsersInfoRequest>()) } returns response

        val result = client.getUserInfo("U123")

        assertTrue(result.ok)
        assertEquals("U123", result.user?.id)
        assertEquals("john", result.user?.name)
        assertEquals("John Doe", result.user?.realName)
        assertFalse(result.user?.isBot ?: true)
    }

    @Test
    fun `postMessage returns error result when Slack SDK throws`() {
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws RuntimeException("network failed")

        val result = client.postMessage("C123", "hello")

        assertFalse(result.ok)
        assertNotNull(result.error)
    }

    @Test
    fun `conversationsList returns error result when Slack SDK throws`() {
        every { methodsClient.conversationsList(any<ConversationsListRequest>()) } throws RuntimeException("timeout")

        val result = client.conversationsList()

        assertFalse(result.ok)
        assertNotNull(result.error)
        assertTrue(result.channels.isEmpty())
    }

    @Test
    fun `postMessage rate-limited exception includes retry metadata`() {
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws
            slackApiException(status = 429, body = """{"ok":false,"error":"rate_limited"}""", retryAfter = "0")

        val result = client.postMessage("C123", "hello")

        assertFalse(result.ok)
        assertEquals("rate_limited", result.error)
        assertEquals("rate_limited", result.errorDetails?.code)
        assertTrue(result.errorDetails?.retryable == true)
        assertEquals(0L, result.errorDetails?.retryAfterSeconds)
    }

    @Test
    fun `io exception is marked retryable`() {
        every { methodsClient.conversationsHistory(any<ConversationsHistoryRequest>()) } throws IOException("socket timeout")

        val result = client.conversationHistory("C123", 10)

        assertFalse(result.ok)
        assertEquals("io_error", result.error)
        assertTrue(result.errorDetails?.retryable == true)
    }

    @Test
    fun `threadReplies returns error result when Slack SDK throws`() {
        every { methodsClient.conversationsReplies(any<ConversationsRepliesRequest>()) } throws IOException("socket timeout")

        val result = client.threadReplies("C123", "1234.5678", 10)

        assertFalse(result.ok)
        assertEquals("io_error", result.error)
        assertTrue(result.errorDetails?.retryable == true)
    }

    @Test
    fun `findChannelsByName paginates and filters by partial query`() {
        val general = mockk<Conversation>()
        every { general.id } returns "C123"
        every { general.name } returns "general"
        every { general.topic } returns null
        every { general.numOfMembers } returns 50
        every { general.isPrivate } returns false

        val random = mockk<Conversation>()
        every { random.id } returns "C456"
        every { random.name } returns "random"
        every { random.topic } returns null
        every { random.numOfMembers } returns 10
        every { random.isPrivate } returns false

        val genAi = mockk<Conversation>()
        every { genAi.id } returns "C789"
        every { genAi.name } returns "gen-ai"
        every { genAi.topic } returns null
        every { genAi.numOfMembers } returns 20
        every { genAi.isPrivate } returns false

        val metadata1 = mockk<ResponseMetadata>()
        every { metadata1.nextCursor } returns "cursor-2"
        val metadata2 = mockk<ResponseMetadata>()
        every { metadata2.nextCursor } returns ""

        val page1 = mockk<ConversationsListResponse>()
        every { page1.isOk } returns true
        every { page1.channels } returns listOf(general, random)
        every { page1.responseMetadata } returns metadata1
        every { page1.error } returns null

        val page2 = mockk<ConversationsListResponse>()
        every { page2.isOk } returns true
        every { page2.channels } returns listOf(genAi)
        every { page2.responseMetadata } returns metadata2
        every { page2.error } returns null

        every { methodsClient.conversationsList(any<ConversationsListRequest>()) } returnsMany listOf(page1, page2)

        val result = client.findChannelsByName("gen", exactMatch = false, limit = 10)

        assertTrue(result.ok)
        assertEquals(2, result.channels.size)
        assertEquals("general", result.channels[0].name)
        assertEquals("gen-ai", result.channels[1].name)
        assertEquals(2, result.scannedPages)
    }

    @Test
    fun `findChannelsByName respects exact match and limit`() {
        val general = mockk<Conversation>()
        every { general.id } returns "C123"
        every { general.name } returns "general"
        every { general.topic } returns null
        every { general.numOfMembers } returns 50
        every { general.isPrivate } returns false

        val generalOps = mockk<Conversation>()
        every { generalOps.id } returns "C124"
        every { generalOps.name } returns "general-ops"
        every { generalOps.topic } returns null
        every { generalOps.numOfMembers } returns 15
        every { generalOps.isPrivate } returns false

        val page = mockk<ConversationsListResponse>()
        every { page.isOk } returns true
        every { page.channels } returns listOf(general, generalOps)
        every { page.responseMetadata } returns null
        every { page.error } returns null
        every { methodsClient.conversationsList(any<ConversationsListRequest>()) } returns page

        val result = client.findChannelsByName("general", exactMatch = true, limit = 1)

        assertTrue(result.ok)
        assertEquals(1, result.channels.size)
        assertEquals("general", result.channels.first().name)
    }

    @Test
    fun `findChannelsByName propagates conversationsList error`() {
        val page = mockk<ConversationsListResponse>()
        every { page.isOk } returns false
        every { page.channels } returns emptyList()
        every { page.responseMetadata } returns null
        every { page.error } returns "not_authed"
        every { methodsClient.conversationsList(any<ConversationsListRequest>()) } returns page

        val result = client.findChannelsByName("gen", exactMatch = false, limit = 10)

        assertFalse(result.ok)
        assertEquals("not_authed", result.error)
    }

    @Test
    fun `conversationHistory accepts cursor and maps next cursor`() {
        val message = mockk<Message>()
        every { message.user } returns "U123"
        every { message.text } returns "hello world"
        every { message.ts } returns "1234.5678"
        every { message.threadTs } returns null

        val metadata = mockk<ResponseMetadata>()
        every { metadata.nextCursor } returns "next-history"

        val response = mockk<ConversationsHistoryResponse>()
        every { response.isOk } returns true
        every { response.messages } returns listOf(message)
        every { response.responseMetadata } returns metadata
        every { response.error } returns null
        val requestSlot = slot<ConversationsHistoryRequest>()
        every { methodsClient.conversationsHistory(capture(requestSlot)) } returns response

        val result = client.conversationHistory("C123", 5, "cursor-1")

        assertTrue(result.ok)
        assertEquals("next-history", result.nextCursor)
        assertEquals("cursor-1", requestSlot.captured.cursor)
        assertEquals(5, requestSlot.captured.limit)
    }

    @Test
    fun `threadReplies accepts cursor and maps next cursor`() {
        val reply = mockk<Message>()
        every { reply.user } returns "U200"
        every { reply.text } returns "reply"
        every { reply.ts } returns "1234.5679"
        every { reply.threadTs } returns "1234.5678"

        val metadata = mockk<ResponseMetadata>()
        every { metadata.nextCursor } returns "next-replies"

        val response = mockk<ConversationsRepliesResponse>()
        every { response.isOk } returns true
        every { response.messages } returns listOf(reply)
        every { response.responseMetadata } returns metadata
        every { response.error } returns null
        val requestSlot = slot<ConversationsRepliesRequest>()
        every { methodsClient.conversationsReplies(capture(requestSlot)) } returns response

        val result = client.threadReplies("C123", "1234.5678", 5, "cursor-2")

        assertTrue(result.ok)
        assertEquals("next-replies", result.nextCursor)
        assertEquals("cursor-2", requestSlot.captured.cursor)
        assertEquals(5, requestSlot.captured.limit)
    }

    @Test
    fun `postMessage retries on transient io errors`() {
        val response = mockk<ChatPostMessageResponse>()
        every { response.isOk } returns true
        every { response.ts } returns "1234.5678"
        every { response.channel } returns "C123"
        every { response.error } returns null

        var attempt = 0
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } answers {
            attempt += 1
            if (attempt < 3) throw IOException("socket timeout")
            response
        }

        val result = client.postMessage("C123", "hello")

        assertTrue(result.ok)
        assertEquals(3, attempt)
        verify(exactly = 3) { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) }
    }

    @Test
    fun `postMessage does not retry on non-retryable slack api error`() {
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws
            slackApiException(status = 400, body = """{"ok":false,"error":"invalid_auth"}""")

        val result = client.postMessage("C123", "hello")

        assertFalse(result.ok)
        assertEquals("invalid_auth", result.error)
        verify(exactly = 1) { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) }
    }

    @Test
    fun `conversationHistory returns timeout error when Slack API call exceeds configured timeout`() {
        val timeoutClient = slackApiClient(
            methodsClient = methodsClient,
            timeoutMs = 30,
            circuitBreakerEnabled = false
        )
        every { methodsClient.conversationsHistory(any<ConversationsHistoryRequest>()) } answers {
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            mockk<ConversationsHistoryResponse>().also { response ->
                every { response.isOk } returns true
                every { response.messages } returns emptyList()
                every { response.responseMetadata } returns null
                every { response.error } returns null
            }
        }

        val result = timeoutClient.conversationHistory("C123", 10)

        assertFalse(result.ok)
        assertEquals("timeout", result.error)
        assertEquals("timeout", result.errorDetails?.code)
        assertTrue(result.errorDetails?.retryable == true)
        verify(exactly = 3) { methodsClient.conversationsHistory(any<ConversationsHistoryRequest>()) }
    }

    @Test
    fun `postMessage opens circuit breaker after consecutive server errors`() {
        val breakerClient = slackApiClient(
            methodsClient = methodsClient,
            circuitFailureThreshold = 2,
            circuitOpenStateDurationMs = 60_000
        )
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws
            slackApiException(
                status = 500,
                body = """{"ok":false,"error":"internal_error"}""",
                retryAfter = "0"
            )

        val first = breakerClient.postMessage("C123", "hello")
        val second = breakerClient.postMessage("C123", "hello")
        val third = breakerClient.postMessage("C123", "hello")

        assertFalse(first.ok)
        assertFalse(second.ok)
        assertFalse(third.ok)
        assertEquals("circuit_open", third.error)
        assertEquals("circuit_open", third.errorDetails?.code)
        assertTrue((third.errorDetails?.retryAfterSeconds ?: 0) > 0)
        verify(exactly = 6) { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) }
    }

    @Test
    fun `postMessage rate-limited failures do not open circuit breaker`() {
        val breakerClient = slackApiClient(
            methodsClient = methodsClient,
            circuitFailureThreshold = 1,
            circuitOpenStateDurationMs = 60_000
        )
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws
            slackApiException(
                status = 429,
                body = """{"ok":false,"error":"rate_limited"}""",
                retryAfter = "0"
            )

        val first = breakerClient.postMessage("C123", "hello")
        val second = breakerClient.postMessage("C123", "hello")

        assertFalse(first.ok)
        assertFalse(second.ok)
        assertEquals("rate_limited", first.error)
        assertEquals("rate_limited", second.error)
        verify(exactly = 6) { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) }
    }

    @Test
    fun `postMessage records retry and success metrics`() {
        val meterRegistry = SimpleMeterRegistry()
        val metricClient = slackApiClient(methodsClient = methodsClient, meterRegistry = meterRegistry)

        val response = mockk<ChatPostMessageResponse>()
        every { response.isOk } returns true
        every { response.ts } returns "1234.5678"
        every { response.channel } returns "C123"
        every { response.error } returns null

        var attempts = 0
        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } answers {
            attempts += 1
            if (attempts < 3) throw IOException("socket timeout")
            response
        }

        val result = metricClient.postMessage("C123", "hello")

        assertTrue(result.ok)
        assertEquals(
            2.0,
            meterRegistry.counter(
                "slack_api_retries_total",
                "api", "chat.postMessage",
                "reason", "io_error"
            ).count()
        )
        assertEquals(
            1.0,
            meterRegistry.counter(
                "slack_api_calls_total",
                "api", "chat.postMessage",
                "outcome", "success",
                "error_code", "none"
            ).count()
        )
    }

    @Test
    fun `postMessage records error code metrics on rate limit failure`() {
        val meterRegistry = SimpleMeterRegistry()
        val metricClient = slackApiClient(methodsClient = methodsClient, meterRegistry = meterRegistry)

        every { methodsClient.chatPostMessage(any<ChatPostMessageRequest>()) } throws
            slackApiException(
                status = 429,
                body = """{"ok":false,"error":"rate_limited"}""",
                retryAfter = "0"
            )

        val result = metricClient.postMessage("C123", "hello")

        assertFalse(result.ok)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "slack_api_calls_total",
                "api", "chat.postMessage",
                "outcome", "error",
                "error_code", "rate_limited"
            ).count()
        )
        assertEquals(
            1.0,
            meterRegistry.counter(
                "slack_api_error_code_total",
                "api", "chat.postMessage",
                "error_code", "rate_limited"
            ).count()
        )
    }

    @Test
    fun `findUsersByName paginates and filters by partial query`() {
        val john = mockk<User>()
        every { john.id } returns "U123"
        every { john.name } returns "john"
        every { john.realName } returns "John Doe"
        every { john.profile } returns null
        every { john.isBot } returns false
        every { john.isDeleted } returns false

        val jane = mockk<User>()
        every { jane.id } returns "U124"
        every { jane.name } returns "jane"
        every { jane.realName } returns "Jane Doe"
        every { jane.profile } returns null
        every { jane.isBot } returns false
        every { jane.isDeleted } returns false

        val johnny = mockk<User>()
        every { johnny.id } returns "U125"
        every { johnny.name } returns "johnny"
        every { johnny.realName } returns "Johnny Doe"
        every { johnny.profile } returns null
        every { johnny.isBot } returns false
        every { johnny.isDeleted } returns false

        val metadata1 = mockk<ResponseMetadata>()
        every { metadata1.nextCursor } returns "users-cursor-2"
        val metadata2 = mockk<ResponseMetadata>()
        every { metadata2.nextCursor } returns ""

        val page1 = mockk<UsersListResponse>()
        every { page1.isOk } returns true
        every { page1.members } returns listOf(john, jane)
        every { page1.responseMetadata } returns metadata1
        every { page1.error } returns null

        val page2 = mockk<UsersListResponse>()
        every { page2.isOk } returns true
        every { page2.members } returns listOf(johnny)
        every { page2.responseMetadata } returns metadata2
        every { page2.error } returns null

        every { methodsClient.usersList(any<UsersListRequest>()) } returnsMany listOf(page1, page2)

        val result = client.findUsersByName("john", exactMatch = false, limit = 10)

        assertTrue(result.ok)
        assertEquals(2, result.users.size)
        assertEquals("john", result.users[0].name)
        assertEquals("johnny", result.users[1].name)
        assertEquals(2, result.scannedPages)
    }

    @Test
    fun `searchMessages maps matches and pagination`() {
        val channel = mockk<Channel>()
        every { channel.id } returns "C123"
        every { channel.name } returns "general"

        val match = mockk<MatchedItem>()
        every { match.channel } returns channel
        every { match.user } returns "U123"
        every { match.text } returns "deploy done"
        every { match.ts } returns "1234.5678"
        every { match.permalink } returns "https://slack.com/archives/C123/p12345678"

        val pagination = mockk<SearchResult.Pagination>()
        every { pagination.page } returns 2
        every { pagination.pageCount } returns 4

        val searchResult = mockk<SearchResult>()
        every { searchResult.total } returns 30
        every { searchResult.pagination } returns pagination
        every { searchResult.matches } returns listOf(match)

        val response = mockk<SearchMessagesResponse>()
        every { response.isOk } returns true
        every { response.messages } returns searchResult
        every { response.error } returns null
        every { methodsClient.searchMessages(any<SearchMessagesRequest>()) } returns response

        val result = client.searchMessages("deploy", count = 10, page = 2)

        assertTrue(result.ok)
        assertEquals(30, result.total)
        assertEquals(2, result.page)
        assertEquals(4, result.pageCount)
        assertEquals(1, result.matches.size)
        assertEquals("general", result.matches.first().channelName)
        assertEquals("deploy done", result.matches.first().text)
    }

    @Test
    fun `uploadFile maps uploaded file details`() {
        val uploadedFile = mockk<File>()
        every { uploadedFile.id } returns "F123"
        every { uploadedFile.name } returns "report.txt"
        every { uploadedFile.title } returns "Report"
        every { uploadedFile.permalink } returns "https://slack.com/files/F123"

        val response = mockk<FilesUploadV2Response>()
        every { response.isOk } returns true
        every { response.file } returns uploadedFile
        every { response.files } returns null
        every { response.error } returns null
        val requestSlot = slot<FilesUploadV2Request>()
        every { methodsClient.filesUploadV2(capture(requestSlot)) } returns response

        val result = client.uploadFile(
            channelId = "C123",
            filename = "report.txt",
            content = "hello",
            title = "Report",
            initialComment = "uploaded",
            threadTs = "1234.5678"
        )

        assertTrue(result.ok)
        assertEquals("F123", result.fileId)
        assertEquals("report.txt", result.fileName)
        assertEquals("Report", result.title)
        assertEquals("uploaded", requestSlot.captured.initialComment)
        assertEquals("1234.5678", requestSlot.captured.threadTs)
    }

    private fun slackApiClient(
        methodsClient: MethodsClient,
        timeoutMs: Long = 5_000,
        circuitBreakerEnabled: Boolean = true,
        circuitFailureThreshold: Int = 3,
        circuitOpenStateDurationMs: Long = 30_000,
        meterRegistry: SimpleMeterRegistry? = null
    ): SlackApiClient {
        val properties = SlackToolsProperties(
            botToken = "xoxb-test-token",
            writeIdempotency = WriteIdempotencyProperties(),
            resilience = ResilienceProperties(
                timeoutMs = timeoutMs,
                circuitBreaker = CircuitBreakerProperties(
                    enabled = circuitBreakerEnabled,
                    failureThreshold = circuitFailureThreshold,
                    openStateDurationMs = circuitOpenStateDurationMs
                )
            )
        )
        return SlackApiClient(methodsClient, properties, meterRegistry)
    }

    private fun slackApiException(status: Int, body: String, retryAfter: String? = null): SlackApiException {
        val request = Request.Builder().url("https://slack.com/api/test").build()
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("error")
            .body(body.toResponseBody())
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter)
        }
        return SlackApiException(builder.build(), body)
    }
}
