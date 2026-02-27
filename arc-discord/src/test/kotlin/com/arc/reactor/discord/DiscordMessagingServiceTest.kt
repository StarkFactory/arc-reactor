package com.arc.reactor.discord

import com.arc.reactor.discord.service.DiscordMessagingService
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.Channel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.MessageCreateSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * Tests for [DiscordMessagingService].
 *
 * Mock strategy:
 * - [MessageChannel.createMessage(String)] calls through via [callOriginal] so that
 *   [discord4j.core.spec.MessageCreateMono] is constructed with our mock channel as its target.
 * - [MessageChannel.createMessage(MessageCreateSpec)] is mocked to return [Mono.just] — this is
 *   called internally by [MessageCreateMono.subscribe] when [awaitSingle] subscribes.
 */
class DiscordMessagingServiceTest {

    private val client = mockk<GatewayDiscordClient>()
    private val channel = mockk<MessageChannel>(relaxed = true)
    private val sentMessage = mockk<Message>(relaxed = true)
    private val service = DiscordMessagingService(client)

    private val channelId = "987654321"
    private val snowflake = Snowflake.of(channelId.toLong())

    @BeforeEach
    fun setupMocks() {
        @Suppress("UNCHECKED_CAST")
        every {
            client.getChannelById(snowflake)
        } returns Mono.just(channel as Channel)

        // Allow createMessage(String) to call through to the real interface default method so
        // that MessageCreateMono.of(channel).withContent(text) is created with our mock channel.
        every { channel.createMessage(any<String>()) } answers { callOriginal() }

        // Mock the MessageCreateSpec overload which is called by MessageCreateMono.subscribe()
        // when awaitSingle() subscribes — this causes the coroutine to complete normally.
        every { channel.createMessage(any<MessageCreateSpec>()) } returns Mono.just(sentMessage)
    }

    @Nested
    inner class SendMessage {

        @Test
        fun `resolves channel by Snowflake derived from channelId`() = runTest {
            service.sendMessage(channelId, "hello")

            verify { client.getChannelById(snowflake) }
        }

        @Test
        fun `calls createMessage on the resolved channel`() = runTest {
            service.sendMessage(channelId, "test message")

            verify(exactly = 1) { channel.createMessage(any<MessageCreateSpec>()) }
        }

        @Test
        fun `sends message content via the MessageCreateSpec`() = runTest {
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, "Hello Discord!")

            specSlot.captured.contentOrElse("") shouldBe "Hello Discord!"
        }
    }

    @Nested
    inner class MessageTruncation {

        @Test
        fun `passes through message of exactly 2000 chars without truncation`() = runTest {
            val content = "a".repeat(2000)
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, content)

            val sent = specSlot.captured.contentOrElse("")
            sent.length shouldBe 2000
            sent.contains("truncated").shouldBeFalse()
        }

        @Test
        fun `truncates message of 2001 chars to at most 2000 chars`() = runTest {
            val borderContent = "b".repeat(2001)
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, borderContent)

            val sent = specSlot.captured.contentOrElse("")
            (sent.length <= 2000).shouldBeTrue()
            sent shouldContain "... (truncated)"
        }

        @Test
        fun `truncated message is exactly 2000 chars`() = runTest {
            val longContent = "c".repeat(4000)
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, longContent)

            specSlot.captured.contentOrElse("").length shouldBe 2000
        }

        @Test
        fun `truncated message ends with truncation suffix`() = runTest {
            val longContent = "d".repeat(3000)
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, longContent)

            specSlot.captured.contentOrElse("").endsWith("... (truncated)").shouldBeTrue()
        }

        @Test
        fun `does not truncate 1999 char message`() = runTest {
            val content = "e".repeat(1999)
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, content)

            specSlot.captured.contentOrElse("") shouldBe content
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `rethrows exception when channel lookup returns error`() = runTest {
            every {
                client.getChannelById(snowflake)
            } returns Mono.error(RuntimeException("Channel not found"))

            shouldThrow<RuntimeException> {
                service.sendMessage(channelId, "hello")
            }
        }

        @Test
        fun `rethrows when channel Mono is empty (not a MessageChannel)`() = runTest {
            every {
                client.getChannelById(snowflake)
            } returns Mono.empty()

            shouldThrow<NoSuchElementException> {
                service.sendMessage(channelId, "hello")
            }
        }

        @Test
        fun `rethrows when createMessage fails with an exception`() = runTest {
            every { channel.createMessage(any<MessageCreateSpec>()) } returns
                Mono.error(RuntimeException("Bot cannot speak in this channel"))

            shouldThrow<RuntimeException> {
                service.sendMessage(channelId, "hello")
            }
        }
    }

    @Nested
    inner class ShortMessages {

        @Test
        fun `sends empty string to channel`() = runTest {
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, "")

            specSlot.captured.contentOrElse("not-empty") shouldBe ""
        }

        @Test
        fun `sends single character message unchanged`() = runTest {
            val specSlot = slot<MessageCreateSpec>()
            every { channel.createMessage(capture(specSlot)) } returns Mono.just(sentMessage)

            service.sendMessage(channelId, "!")

            specSlot.captured.contentOrElse("") shouldBe "!"
        }
    }
}
