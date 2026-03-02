package com.arc.reactor.slack.handler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SlackReminderStoreTest {

    @Test
    fun `add and list returns reminders in id order`() {
        val store = SlackReminderStore()

        store.add("U1", "first")
        store.add("U1", "second")

        val reminders = store.list("U1")
        reminders.map { it.id } shouldContainExactly listOf(1, 2)
        reminders.map { it.text } shouldContainExactly listOf("first", "second")
    }

    @Test
    fun `done removes a reminder and returns removed item`() {
        val store = SlackReminderStore()
        store.add("U1", "first")
        store.add("U1", "second")

        val done = store.done("U1", 1)

        done?.id shouldBe 1
        done?.text shouldBe "first"
        store.list("U1").map { it.id } shouldContainExactly listOf(2)
    }

    @Test
    fun `done returns null when reminder id does not exist`() {
        val store = SlackReminderStore()
        store.add("U1", "first")

        store.done("U1", 99).shouldBeNull()
    }

    @Test
    fun `clear removes all reminders and returns removed count`() {
        val store = SlackReminderStore()
        store.add("U1", "first")
        store.add("U1", "second")

        val removed = store.clear("U1")

        removed shouldBe 2
        store.list("U1") shouldContainExactly emptyList()
    }

    @Test
    fun `max per user trims oldest reminders`() {
        val store = SlackReminderStore(maxPerUser = 2)

        store.add("U1", "first")
        store.add("U1", "second")
        store.add("U1", "third")

        val reminders = store.list("U1")
        reminders.map { it.id } shouldContainExactly listOf(2, 3)
        reminders.map { it.text } shouldContainExactly listOf("second", "third")
    }
}
