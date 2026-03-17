package com.arc.reactor.slack.handler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SlackReminderStore]의 리마인더 저장소 CRUD 테스트.
 *
 * 리마인더 추가/완료/전체삭제 동작과 사용자별 최대 개수 제한을 검증한다.
 */
class SlackReminderStoreTest {

    @Test
    fun `and list returns reminders in id order를 추가한다`() {
        val store = SlackReminderStore()

        store.add("U1", "first")
        store.add("U1", "second")

        val reminders = store.list("U1")
        reminders.map { it.id } shouldContainExactly listOf(1, 2)
        reminders.map { it.text } shouldContainExactly listOf("first", "second")
    }

    @Test
    fun `done은(는) removes a reminder and returns removed item`() {
        val store = SlackReminderStore()
        store.add("U1", "first")
        store.add("U1", "second")

        val done = store.done("U1", 1)

        done?.id shouldBe 1
        done?.text shouldBe "first"
        store.list("U1").map { it.id } shouldContainExactly listOf(2)
    }

    @Test
    fun `reminder id does not exist일 때 done returns null`() {
        val store = SlackReminderStore()
        store.add("U1", "first")

        store.done("U1", 99).shouldBeNull()
    }

    @Test
    fun `removes all reminders and returns removed count를 비운다`() {
        val store = SlackReminderStore()
        store.add("U1", "first")
        store.add("U1", "second")

        val removed = store.clear("U1")

        removed shouldBe 2
        store.list("U1") shouldContainExactly emptyList()
    }

    @Test
    fun `max은(는) per user trims oldest reminders`() {
        val store = SlackReminderStore(maxPerUser = 2)

        store.add("U1", "first")
        store.add("U1", "second")
        store.add("U1", "third")

        val reminders = store.list("U1")
        reminders.map { it.id } shouldContainExactly listOf(2, 3)
        reminders.map { it.text } shouldContainExactly listOf("second", "third")
    }
}
