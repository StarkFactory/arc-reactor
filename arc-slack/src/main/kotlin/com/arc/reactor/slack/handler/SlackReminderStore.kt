package com.arc.reactor.slack.handler

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class SlackReminder(
    val id: Int,
    val text: String,
    val createdAt: Instant = Instant.now()
)

class SlackReminderStore(
    private val maxPerUser: Int = DEFAULT_MAX_PER_USER
) {
    private val remindersByUser = ConcurrentHashMap<String, CopyOnWriteArrayList<SlackReminder>>()
    private val sequenceByUser = ConcurrentHashMap<String, AtomicInteger>()

    fun add(userId: String, text: String): SlackReminder {
        val normalizedText = text.trim()
        val reminder = SlackReminder(id = nextId(userId), text = normalizedText)
        val list = listRef(userId)
        list.add(reminder)
        trimOverflow(list)
        return reminder
    }

    fun list(userId: String): List<SlackReminder> =
        listRef(userId).sortedBy { it.id }

    fun done(userId: String, id: Int): SlackReminder? {
        val list = listRef(userId)
        val existing = list.firstOrNull { it.id == id } ?: return null
        list.remove(existing)
        return existing
    }

    fun clear(userId: String): Int {
        val list = listRef(userId)
        val size = list.size
        list.clear()
        return size
    }

    private fun nextId(userId: String): Int =
        sequenceByUser.computeIfAbsent(userId) { AtomicInteger(0) }.incrementAndGet()

    private fun listRef(userId: String): CopyOnWriteArrayList<SlackReminder> =
        remindersByUser.computeIfAbsent(userId) { CopyOnWriteArrayList() }

    private fun trimOverflow(list: CopyOnWriteArrayList<SlackReminder>) {
        while (list.size > maxPerUser) {
            list.removeAt(0)
        }
    }

    companion object {
        private const val DEFAULT_MAX_PER_USER = 50
    }
}
