package com.arc.reactor.slack.proactive

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [InMemoryProactiveChannelStore]의 단위 테스트.
 *
 * 채널 추가/제거, isEnabled 조회, 목록 정렬, seedFromConfig 초기화를 검증한다.
 */
class ProactiveChannelStoreTest {

    // ── add ─────────────────────────────────────────────────────────────────

    @Nested
    inner class Add {

        @Test
        fun `채널을 추가하면 isEnabled가 true를 반환한다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C001")

            store.isEnabled("C001") shouldBe true
        }

        @Test
        fun `추가된 채널을 list에서 조회할 수 있다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C001", "general")

            val channels = store.list()

            channels.size shouldBe 1
            channels[0].channelId shouldBe "C001"
        }

        @Test
        fun `채널명이 null이어도 추가된다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C002", null)

            store.isEnabled("C002") shouldBe true
        }

        @Test
        fun `동일 채널 ID를 두 번 추가하면 마지막 값으로 덮어쓴다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C003", "first-name")
            store.add("C003", "second-name")

            val channels = store.list()

            channels.size shouldBe 1
            channels[0].channelName shouldBe "second-name"
        }
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Nested
    inner class Remove {

        @Test
        fun `존재하는 채널 제거 시 true를 반환한다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C010")

            val removed = store.remove("C010")

            removed shouldBe true
        }

        @Test
        fun `채널 제거 후 isEnabled가 false를 반환한다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C010")
            store.remove("C010")

            store.isEnabled("C010") shouldBe false
        }

        @Test
        fun `존재하지 않는 채널 제거 시 false를 반환한다`() {
            val store = InMemoryProactiveChannelStore()

            val removed = store.remove("C999")

            removed shouldBe false
        }

        @Test
        fun `채널 제거 후 list에서 사라진다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C011")
            store.remove("C011")

            store.list().isEmpty() shouldBe true
        }
    }

    // ── isEnabled ─────────────────────────────────────────────────────────

    @Nested
    inner class IsEnabled {

        @Test
        fun `빈 저장소에서 isEnabled는 false를 반환한다`() {
            val store = InMemoryProactiveChannelStore()

            store.isEnabled("C001") shouldBe false
        }

        @Test
        fun `추가되지 않은 채널은 false를 반환한다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C001")

            store.isEnabled("C002") shouldBe false
        }
    }

    // ── list ──────────────────────────────────────────────────────────────

    @Nested
    inner class List {

        @Test
        fun `빈 저장소의 list는 빈 목록을 반환한다`() {
            val store = InMemoryProactiveChannelStore()

            store.list().isEmpty() shouldBe true
        }

        @Test
        fun `복수 채널 추가 시 addedAt 오름차순으로 정렬된다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C100")
            Thread.sleep(5) // addedAt 차이를 보장한다
            store.add("C200")

            val channels = store.list()

            channels[0].channelId shouldBe "C100"
            channels[1].channelId shouldBe "C200"
        }
    }

    // ── seedFromConfig ─────────────────────────────────────────────────────

    @Nested
    inner class SeedFromConfig {

        @Test
        fun `seedFromConfig는 설정 채널 ID를 초기 목록으로 등록한다`() {
            val store = InMemoryProactiveChannelStore()
            store.seedFromConfig(listOf("C500", "C501"))

            store.isEnabled("C500") shouldBe true
            store.isEnabled("C501") shouldBe true
        }

        @Test
        fun `seedFromConfig는 이미 존재하는 채널을 덮어쓰지 않는다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C500", "existing-name")
            store.seedFromConfig(listOf("C500"))

            // 기존 채널명이 보존되어야 한다
            val channel = store.list().first { it.channelId == "C500" }
            channel.channelName shouldBe "existing-name"
        }

        @Test
        fun `빈 목록으로 seed하면 저장소가 변경되지 않는다`() {
            val store = InMemoryProactiveChannelStore()
            store.add("C999")
            store.seedFromConfig(emptyList())

            store.list().size shouldBe 1
        }
    }
}
