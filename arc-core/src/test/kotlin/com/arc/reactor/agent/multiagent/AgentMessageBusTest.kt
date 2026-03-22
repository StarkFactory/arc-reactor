package com.arc.reactor.agent.multiagent

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [InMemoryAgentMessageBus] 단위 테스트.
 *
 * 발행, 구독, 메시지 조회, 초기화를 검증한다.
 */
class AgentMessageBusTest {

    private lateinit var bus: InMemoryAgentMessageBus

    @BeforeEach
    fun setUp() {
        bus = InMemoryAgentMessageBus()
    }

    @Nested
    inner class Publish {

        @Test
        fun `발행된 메시지가 대화 기록에 저장되어야 한다`() = runTest {
            val message = AgentMessage(
                sourceAgentId = "agent-a",
                targetAgentId = "agent-b",
                content = "이슈 JAR-36 조회 완료"
            )

            bus.publish(message)

            bus.getConversation() shouldHaveSize 1
            bus.getConversation().first().content shouldBe
                "이슈 JAR-36 조회 완료"
        }

        @Test
        fun `브로드캐스트 메시지를 발행할 수 있어야 한다`() = runTest {
            val message = AgentMessage(
                sourceAgentId = "agent-a",
                targetAgentId = null,
                content = "브로드캐스트 메시지"
            )

            bus.publish(message)

            bus.getConversation() shouldHaveSize 1
            bus.getConversation().first().targetAgentId shouldBe null
        }
    }

    @Nested
    inner class Subscribe {

        @Test
        fun `구독자에게 대상 메시지가 전달되어야 한다`() = runTest {
            val received = mutableListOf<AgentMessage>()
            bus.subscribe("agent-b") { received.add(it) }

            val message = AgentMessage(
                sourceAgentId = "agent-a",
                targetAgentId = "agent-b",
                content = "대상 메시지"
            )
            bus.publish(message)

            received shouldHaveSize 1
            received.first().content shouldBe "대상 메시지"
        }

        @Test
        fun `브로드캐스트 메시지가 모든 구독자에게 전달되어야 한다`() =
            runTest {
                val receivedA = mutableListOf<AgentMessage>()
                val receivedB = mutableListOf<AgentMessage>()
                bus.subscribe("agent-a") { receivedA.add(it) }
                bus.subscribe("agent-b") { receivedB.add(it) }

                val message = AgentMessage(
                    sourceAgentId = "agent-c",
                    targetAgentId = null,
                    content = "브로드캐스트"
                )
                bus.publish(message)

                receivedA shouldHaveSize 1
                receivedB shouldHaveSize 1
            }

        @Test
        fun `다른 에이전트 대상 메시지는 전달되지 않아야 한다`() = runTest {
            val received = mutableListOf<AgentMessage>()
            bus.subscribe("agent-b") { received.add(it) }

            val message = AgentMessage(
                sourceAgentId = "agent-a",
                targetAgentId = "agent-c",
                content = "다른 대상"
            )
            bus.publish(message)

            received.shouldBeEmpty()
        }
    }

    @Nested
    inner class GetMessages {

        @Test
        fun `특정 에이전트 대상 메시지를 조회할 수 있어야 한다`() = runTest {
            bus.publish(
                AgentMessage("a", "agent-b", "메시지 1")
            )
            bus.publish(
                AgentMessage("a", "agent-c", "메시지 2")
            )
            bus.publish(
                AgentMessage("a", "agent-b", "메시지 3")
            )

            val messages = bus.getMessages("agent-b")

            messages shouldHaveSize 2
            messages.map { it.content } shouldContainExactly
                listOf("메시지 1", "메시지 3")
        }

        @Test
        fun `브로드캐스트 메시지도 포함되어야 한다`() = runTest {
            bus.publish(
                AgentMessage("a", "agent-b", "대상 메시지")
            )
            bus.publish(
                AgentMessage("a", null, "브로드캐스트")
            )

            val messages = bus.getMessages("agent-b")

            messages shouldHaveSize 2
        }
    }

    @Nested
    inner class GetConversation {

        @Test
        fun `모든 메시지를 시간순으로 반환해야 한다`() = runTest {
            bus.publish(AgentMessage("a", "b", "첫째"))
            bus.publish(AgentMessage("b", "c", "둘째"))
            bus.publish(AgentMessage("c", null, "셋째"))

            val conversation = bus.getConversation()

            conversation shouldHaveSize 3
            conversation.map { it.content } shouldContainExactly
                listOf("첫째", "둘째", "셋째")
        }

        @Test
        fun `메시지가 없으면 빈 목록을 반환해야 한다`() {
            bus.getConversation().shouldBeEmpty()
        }
    }

    @Nested
    inner class Clear {

        @Test
        fun `초기화 후 모든 메시지가 삭제되어야 한다`() = runTest {
            bus.publish(AgentMessage("a", "b", "메시지"))
            bus.subscribe("b") { }

            bus.clear()

            bus.getConversation().shouldBeEmpty()
            bus.getMessages("b").shouldBeEmpty()
        }

        @Test
        fun `초기화 후 구독자도 제거되어야 한다`() = runTest {
            val received = mutableListOf<AgentMessage>()
            bus.subscribe("b") { received.add(it) }

            bus.clear()
            bus.publish(AgentMessage("a", "b", "초기화 후 메시지"))

            received.shouldBeEmpty()
        }
    }
}
