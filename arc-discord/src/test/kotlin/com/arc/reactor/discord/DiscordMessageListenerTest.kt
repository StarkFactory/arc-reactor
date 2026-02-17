package com.arc.reactor.discord

import com.arc.reactor.discord.config.DiscordProperties
import com.arc.reactor.discord.handler.DiscordEventHandler
import com.arc.reactor.discord.listener.DiscordMessageListener
import com.arc.reactor.discord.model.DiscordEventCommand
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.User
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.Optional

class DiscordMessageListenerTest {

    private val client = mockk<GatewayDiscordClient>()
    private val handler = mockk<DiscordEventHandler>(relaxed = true)
    private val properties = DiscordProperties(
        enabled = true,
        token = "test-token",
        maxConcurrentRequests = 2,
        respondToMentionsOnly = true
    )

    private val selfId = Snowflake.of(111111L)

    private fun createMockEvent(
        isBot: Boolean = false,
        content: String = "<@111111> hello",
        userId: Long = 222222L,
        channelId: Long = 333333L,
        messageId: Long = 444444L,
        guildId: Long? = 555555L,
        mentionIds: List<Snowflake> = listOf(selfId),
        username: String = "testuser"
    ): MessageCreateEvent {
        val author = mockk<User>()
        every { author.isBot } returns isBot
        every { author.id } returns Snowflake.of(userId)
        every { author.username } returns username

        val message = mockk<Message>()
        every { message.author } returns Optional.of(author)
        every { message.content } returns content
        every { message.channelId } returns Snowflake.of(channelId)
        every { message.id } returns Snowflake.of(messageId)
        every { message.userMentionIds } returns mentionIds
        every { message.guildId } returns if (guildId != null) {
            Optional.of(Snowflake.of(guildId))
        } else {
            Optional.empty()
        }

        val event = mockk<MessageCreateEvent>()
        every { event.message } returns message
        return event
    }

    @Nested
    inner class MessageFiltering {

        @Test
        fun `filters out bot messages`() = runTest {
            val botEvent = createMockEvent(isBot = true)
            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns Flux.just(botEvent)

            val listener = DiscordMessageListener(client, handler, properties)
            listener.startListening()

            delay(20)

            coVerify(exactly = 0) { handler.handleMessage(any()) }
        }

        @Test
        fun `filters non-mentions when respondToMentionsOnly is true`() = runTest {
            val noMentionEvent = createMockEvent(mentionIds = emptyList())
            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns Flux.just(noMentionEvent)

            val listener = DiscordMessageListener(client, handler, properties)
            listener.startListening()

            delay(20)

            coVerify(exactly = 0) { handler.handleMessage(any()) }
        }

        @Test
        fun `accepts non-mentions when respondToMentionsOnly is false`() = runTest {
            val noMentionProps = properties.copy(respondToMentionsOnly = false)
            val noMentionEvent = createMockEvent(
                mentionIds = emptyList(),
                content = "hello bot"
            )
            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns
                Flux.just(noMentionEvent)

            val commandSlot = slot<DiscordEventCommand>()
            coEvery { handler.handleMessage(capture(commandSlot)) } returns Unit

            val listener = DiscordMessageListener(client, handler, noMentionProps)
            listener.startListening()

            coVerify(timeout = 2000, exactly = 1) { handler.handleMessage(any()) }
            commandSlot.captured.content shouldBe "hello bot"
        }

        @Test
        fun `filters messages without author`() = runTest {
            val message = mockk<Message>()
            every { message.author } returns Optional.empty()

            val event = mockk<MessageCreateEvent>()
            every { event.message } returns message

            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns Flux.just(event)

            val listener = DiscordMessageListener(client, handler, properties)
            listener.startListening()

            delay(20)

            coVerify(exactly = 0) { handler.handleMessage(any()) }
        }
    }

    @Nested
    inner class EventDispatching {

        @Test
        fun `dispatches valid mentioned message to handler`() = runTest {
            val event = createMockEvent()
            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns Flux.just(event)

            val commandSlot = slot<DiscordEventCommand>()
            coEvery { handler.handleMessage(capture(commandSlot)) } returns Unit

            val listener = DiscordMessageListener(client, handler, properties)
            listener.startListening()

            coVerify(timeout = 2000, exactly = 1) { handler.handleMessage(any()) }
            commandSlot.captured.channelId shouldBe "333333"
            commandSlot.captured.userId shouldBe "222222"
            commandSlot.captured.username shouldBe "testuser"
            commandSlot.captured.content shouldBe "<@111111> hello"
            commandSlot.captured.messageId shouldBe "444444"
            commandSlot.captured.guildId shouldBe "555555"
        }

        @Test
        fun `sets guildId to null for DM messages`() = runTest {
            val event = createMockEvent(guildId = null)
            every { client.selfId } returns selfId
            every { client.on(MessageCreateEvent::class.java) } returns Flux.just(event)

            val commandSlot = slot<DiscordEventCommand>()
            coEvery { handler.handleMessage(capture(commandSlot)) } returns Unit

            val listener = DiscordMessageListener(client, handler, properties)
            listener.startListening()

            coVerify(timeout = 2000, exactly = 1) { handler.handleMessage(any()) }
            commandSlot.captured.guildId shouldBe null
        }
    }
}
