package com.arc.reactor.slack.handler

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 리마인더 단건 정보. */
data class SlackReminder(
    val id: Int,
    val text: String,
    val dueAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)

/**
 * 사용자별 인메모리 리마인더 저장소.
 *
 * 슬래시 명령(`/jarvis remind`)으로 등록된 리마인더를 관리한다.
 * 시간 표현("at HH:mm" 또는 "N시 M분")을 자동 파싱하여 [SlackReminder.dueAt]을 설정하며,
 * [SlackReminderScheduler]가 주기적으로 만료된 리마인더를 수거한다.
 *
 * @param maxPerUser 사용자당 최대 리마인더 수 (초과 시 오래된 순 삭제)
 * @param timezone 시간 파싱에 사용할 타임존 (기본: Asia/Seoul)
 * @see SlackReminderScheduler
 * @see ReminderTimeParser
 */
class SlackReminderStore(
    private val maxPerUser: Int = DEFAULT_MAX_PER_USER,
    private val timezone: ZoneId = ZoneId.of("Asia/Seoul")
) {
    private val remindersByUser = ConcurrentHashMap<String, CopyOnWriteArrayList<SlackReminder>>()
    private val sequenceByUser = ConcurrentHashMap<String, AtomicInteger>()

    /** 리마인더를 추가한다. 텍스트에서 시간 표현을 파싱하여 [SlackReminder.dueAt]을 설정한다. */
    fun add(userId: String, text: String): SlackReminder {
        val parsed = ReminderTimeParser.parse(text.trim(), timezone)
        val reminder = SlackReminder(
            id = nextId(userId),
            text = parsed.cleanText,
            dueAt = parsed.dueAt
        )
        val list = listRef(userId)
        list.add(reminder)
        trimOverflow(list)
        return reminder
    }

    /** 사용자의 리마인더 목록을 ID 순으로 반환한다. */
    fun list(userId: String): List<SlackReminder> =
        listRef(userId).sortedBy { it.id }

    /** 리마인더를 완료 처리(삭제)한다. 존재하지 않으면 null을 반환한다. */
    fun done(userId: String, id: Int): SlackReminder? {
        val list = listRef(userId)
        val existing = list.firstOrNull { it.id == id } ?: return null
        list.remove(existing)
        return existing
    }

    /** 사용자의 모든 리마인더를 삭제하고 삭제된 건수를 반환한다. */
    fun clear(userId: String): Int {
        val list = listRef(userId)
        val size = list.size
        list.clear()
        return size
    }

    /**
     * 모든 사용자에서 만료된(dueAt <= now) 리마인더를 수거하고 저장소에서 제거한다.
     *
     * @return (사용자 ID, 리마인더) 쌍의 리스트
     */
    fun collectDueReminders(): List<Pair<String, SlackReminder>> {
        val now = Instant.now()
        val result = mutableListOf<Pair<String, SlackReminder>>()

        for ((userId, reminders) in remindersByUser) {
            val due = reminders.filter { it.dueAt != null && !it.dueAt.isAfter(now) }
            if (due.isEmpty()) continue
            // CopyOnWriteArrayList.removeAll은 내부 배열을 한 번만 복사한다 (개별 remove N회 대비 효율적)
            reminders.removeAll(due.toSet())
            for (reminder in due) {
                result.add(userId to reminder)
            }
        }
        return result
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

/**
 * 리마인더 텍스트에서 시간 표현을 파싱하는 유틸리티.
 *
 * 지원 형식:
 * - 영문: "at HH:mm" (예: "at 15:30")
 * - 한국어: "N시 M분에" (예: "3시 30분에")
 *
 * 파싱된 시간이 현재보다 과거이면 다음 날로 설정한다.
 */
internal object ReminderTimeParser {
    private val atTimeRegex = Regex(
        """(?:^|\s)at\s+(\d{1,2}):(\d{2})(?:\s*$)""",
        RegexOption.IGNORE_CASE
    )
    private val koreanTimeRegex = Regex(
        """(?:^|\s)(\d{1,2})시(?:\s*(\d{1,2})분)?(?:\s*에?)(?:\s*$)"""
    )

    data class ParseResult(val cleanText: String, val dueAt: Instant?)

    fun parse(text: String, timezone: ZoneId): ParseResult {
        atTimeRegex.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return ParseResult(text, null)
            val minute = match.groupValues[2].toIntOrNull() ?: return ParseResult(text, null)
            val dueAt = resolveTime(hour, minute, timezone) ?: return ParseResult(text, null)
            val cleanText = text.removeRange(match.range).trim()
            return ParseResult(cleanText.ifBlank { text.trim() }, dueAt)
        }
        koreanTimeRegex.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return ParseResult(text, null)
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val dueAt = resolveTime(hour, minute, timezone) ?: return ParseResult(text, null)
            val cleanText = text.removeRange(match.range).trim()
            return ParseResult(cleanText.ifBlank { text.trim() }, dueAt)
        }
        return ParseResult(text, null)
    }

    private fun resolveTime(hour: Int, minute: Int, timezone: ZoneId): Instant? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val now = ZonedDateTime.now(timezone)
        val time = LocalTime.of(hour, minute)
        var target = ZonedDateTime.of(LocalDate.now(timezone), time, timezone)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.toInstant()
    }
}
