package com.arc.reactor.admin.query

import com.arc.reactor.admin.controller.AdminChatMessage
import com.arc.reactor.admin.controller.AdminConversationOverview
import com.arc.reactor.admin.controller.AdminPaginatedResponse
import com.arc.reactor.admin.controller.AdminSessionDetail
import com.arc.reactor.admin.controller.AdminSessionRow
import com.arc.reactor.admin.controller.AdminSessionTag
import com.arc.reactor.admin.controller.AdminUserSummary
import com.arc.reactor.admin.controller.ChannelMixEntry
import com.arc.reactor.admin.controller.PersonaUsageEntry
import com.arc.reactor.admin.controller.SessionQueryFilters
import com.arc.reactor.admin.controller.TopUserEntry
import com.arc.reactor.admin.controller.TrendPoint
import com.arc.reactor.admin.controller.UserQueryFilters
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 어드민 세션 조회 서비스.
 *
 * conversation_messages를 기본으로 하고, metric_sessions / metric_agent_executions / feedback /
 * personas 테이블을 LEFT JOIN하여 메타데이터를 보강한다.
 */
class AdminSessionQueryService(private val jdbcTemplate: JdbcTemplate) {

    companion object {
        private const val MAX_LIMIT = 100
        private const val PREVIEW_LENGTH = 200

        /** trust 상태 판정: guard 거부 건수 기반. */
        private fun deriveTrust(guardRejectCount: Int): String = when {
            guardRejectCount == 0 -> "clean"
            else -> "flagged"
        }

        /** feedback rating → 프론트 문자열. */
        private fun mapFeedback(rating: String?): String? = when (rating?.uppercase()) {
            "THUMBS_UP" -> "positive"
            "THUMBS_DOWN" -> "negative"
            else -> null
        }

        private fun clampLimit(raw: Int): Int = raw.coerceIn(1, MAX_LIMIT)
        private fun safeOffset(raw: Int): Int = raw.coerceAtLeast(0)
    }

    // ── 세션 목록 ──────────────────────────────────────────

    /** 세션 목록 (필터 + 페이지네이션). */
    fun listSessions(
        filters: SessionQueryFilters,
        limit: Int,
        offset: Int
    ): AdminPaginatedResponse<AdminSessionRow> {
        val clamped = clampLimit(limit)
        val safe = safeOffset(offset)

        val where = buildSessionWhereClause(filters)
        val orderCol = if (filters.sortBy == "messageCount") "message_count" else "last_activity"
        val orderDir = if (filters.order.equals("asc", ignoreCase = true)) "ASC" else "DESC"

        val countSql = """
            SELECT COUNT(*) FROM (
                SELECT cm.session_id
                FROM conversation_messages cm
                ${where.joins}
                ${where.clause}
                GROUP BY cm.session_id
                ${where.having}
            ) cnt
        """.trimIndent()

        val total = jdbcTemplate.queryForObject(countSql, Int::class.java, *where.params.toTypedArray()) ?: 0

        if (total == 0) {
            return AdminPaginatedResponse(emptyList(), 0, safe, clamped)
        }

        val itemsSql = """
            SELECT
                s.session_id,
                s.user_id,
                s.message_count,
                s.last_activity,
                (SELECT content FROM conversation_messages sub
                 WHERE sub.session_id = s.session_id AND sub.role = 'user'
                 ORDER BY sub.id ASC LIMIT 1) AS preview,
                (SELECT channel FROM metric_sessions ms
                 WHERE ms.session_id = s.session_id
                 ORDER BY ms.time DESC LIMIT 1) AS channel,
                (SELECT total_duration_ms FROM metric_sessions ms2
                 WHERE ms2.session_id = s.session_id
                 ORDER BY ms2.time DESC LIMIT 1) AS duration,
                (SELECT persona_id FROM metric_agent_executions mae
                 WHERE mae.session_id = s.session_id AND mae.persona_id IS NOT NULL
                 ORDER BY mae.time DESC LIMIT 1) AS persona_id,
                (SELECT p.name FROM personas p WHERE p.id = (
                    SELECT mae2.persona_id FROM metric_agent_executions mae2
                    WHERE mae2.session_id = s.session_id AND mae2.persona_id IS NOT NULL
                    ORDER BY mae2.time DESC LIMIT 1
                )) AS persona_name,
                (SELECT COUNT(*)::INT FROM metric_agent_executions mae3
                 WHERE mae3.session_id = s.session_id AND mae3.guard_rejected = true) AS guard_reject_count,
                (SELECT f.rating FROM feedback f
                 WHERE f.session_id = s.session_id
                 ORDER BY f.timestamp DESC LIMIT 1) AS feedback_rating
            FROM (
                SELECT cm.session_id,
                       cm.user_id,
                       COUNT(*) AS message_count,
                       MAX(cm.timestamp) AS last_activity
                FROM conversation_messages cm
                ${where.joins}
                ${where.clause}
                GROUP BY cm.session_id, cm.user_id
                ${where.having}
                ORDER BY $orderCol $orderDir
                LIMIT ? OFFSET ?
            ) s
        """.trimIndent()

        val itemParams = where.params + clamped + safe
        val rows = jdbcTemplate.query(itemsSql, { rs, _ -> mapSessionRow(rs) }, *itemParams.toTypedArray())
        val tags = getTagsForSessions(rows.map { it.sessionId })
        val enriched = rows.map { row -> row.copy(tags = tags[row.sessionId].orEmpty()) }

        return AdminPaginatedResponse(enriched, total, safe, clamped)
    }

    // ── 세션 상세 ──────────────────────────────────────────

    /** 세션 상세 (메시지 포함). */
    fun getSessionDetail(sessionId: String): AdminSessionDetail? {
        val metaSql = """
            SELECT
                cm.session_id,
                cm.user_id,
                COUNT(*) AS message_count,
                MAX(cm.timestamp) AS last_activity,
                MIN(cm.timestamp) AS started_at,
                (SELECT channel FROM metric_sessions ms
                 WHERE ms.session_id = cm.session_id ORDER BY ms.time DESC LIMIT 1) AS channel,
                (SELECT total_duration_ms FROM metric_sessions ms2
                 WHERE ms2.session_id = cm.session_id ORDER BY ms2.time DESC LIMIT 1) AS duration,
                (SELECT persona_id FROM metric_agent_executions mae
                 WHERE mae.session_id = cm.session_id AND mae.persona_id IS NOT NULL
                 ORDER BY mae.time DESC LIMIT 1) AS persona_id,
                (SELECT p.name FROM personas p WHERE p.id = (
                    SELECT mae2.persona_id FROM metric_agent_executions mae2
                    WHERE mae2.session_id = cm.session_id AND mae2.persona_id IS NOT NULL
                    ORDER BY mae2.time DESC LIMIT 1
                )) AS persona_name,
                (SELECT COUNT(*)::INT FROM metric_agent_executions mae4
                 WHERE mae4.session_id = cm.session_id AND mae4.guard_rejected = true) AS guard_reject_count,
                (SELECT f.rating FROM feedback f
                 WHERE f.session_id = cm.session_id
                 ORDER BY f.timestamp DESC LIMIT 1) AS feedback_rating
            FROM conversation_messages cm
            WHERE cm.session_id = ?
            GROUP BY cm.session_id, cm.user_id
        """.trimIndent()

        data class SessionMeta(
            val userId: String, val messageCount: Int, val lastActivity: Long,
            val startedAt: Long, val channel: String?, val duration: Long?,
            val personaId: String?, val personaName: String?,
            val guardRejectCount: Int, val feedbackRating: String?
        )

        val meta = jdbcTemplate.query(metaSql, { rs, _ ->
            SessionMeta(
                userId = rs.getString("user_id"),
                messageCount = rs.getInt("message_count"),
                lastActivity = rs.getLong("last_activity"),
                startedAt = rs.getLong("started_at"),
                channel = rs.getString("channel"),
                duration = rs.getObject("duration") as? Long,
                personaId = rs.getString("persona_id"),
                personaName = rs.getString("persona_name"),
                guardRejectCount = rs.getInt("guard_reject_count"),
                feedbackRating = rs.getString("feedback_rating")
            )
        }, sessionId).firstOrNull() ?: return null

        val messages = jdbcTemplate.query(
            """SELECT id, role, content, timestamp FROM conversation_messages
               WHERE session_id = ? ORDER BY id ASC""",
            { rs, _ ->
                AdminChatMessage(
                    id = rs.getLong("id"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    timestamp = rs.getLong("timestamp")
                )
            },
            sessionId
        )

        val tags = getTagsForSession(sessionId)

        return AdminSessionDetail(
            sessionId = sessionId,
            userId = meta.userId,
            channel = meta.channel,
            personaId = meta.personaId,
            personaName = meta.personaName,
            model = null,
            messageCount = meta.messageCount,
            duration = meta.duration,
            startedAt = meta.startedAt,
            lastActivity = meta.lastActivity,
            trust = deriveTrust(meta.guardRejectCount),
            feedback = mapFeedback(meta.feedbackRating),
            tags = tags,
            messages = messages
        )
    }

    // ── 세션 삭제 ──────────────────────────────────────────

    /** 세션 삭제 (conversation_messages + session_tags). */
    fun deleteSession(sessionId: String): Boolean {
        val deleted = jdbcTemplate.update(
            "DELETE FROM conversation_messages WHERE session_id = ?", sessionId
        )
        jdbcTemplate.update("DELETE FROM session_tags WHERE session_id = ?", sessionId)
        jdbcTemplate.update("DELETE FROM conversation_summaries WHERE session_id = ?", sessionId)
        return deleted > 0
    }

    // ── 세션 내보내기 ──────────────────────────────────────

    /** 내보내기용 메시지 조회. */
    fun getSessionMessages(sessionId: String): List<AdminChatMessage>? {
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversation_messages WHERE session_id = ?",
            Int::class.java, sessionId
        ) ?: 0
        if (exists == 0) return null

        return jdbcTemplate.query(
            """SELECT id, role, content, timestamp FROM conversation_messages
               WHERE session_id = ? ORDER BY id ASC""",
            { rs, _ ->
                AdminChatMessage(
                    id = rs.getLong("id"),
                    role = rs.getString("role"),
                    content = rs.getString("content"),
                    timestamp = rs.getLong("timestamp")
                )
            },
            sessionId
        )
    }

    // ── 유저 목록 ──────────────────────────────────────────

    /** 유저 목록 (세션 활동 요약 포함). */
    fun listUsers(
        filters: UserQueryFilters,
        limit: Int,
        offset: Int
    ): AdminPaginatedResponse<AdminUserSummary> {
        val clamped = clampLimit(limit)
        val safe = safeOffset(offset)

        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (!filters.q.isNullOrBlank()) {
            conditions += "cm.user_id ILIKE ?"
            params += "%${filters.q}%"
        }

        if (filters.periodDays != null) {
            val cutoff = Instant.now().minusSeconds(filters.periodDays.toLong() * 86400).toEpochMilli()
            conditions += "cm.timestamp >= ?"
            params += cutoff
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT COUNT(DISTINCT user_id) FROM conversation_messages cm $whereClause"
        val total = jdbcTemplate.queryForObject(countSql, Int::class.java, *params.toTypedArray()) ?: 0

        if (total == 0) {
            return AdminPaginatedResponse(emptyList(), 0, safe, clamped)
        }

        val orderCol = when (filters.sortBy) {
            "totalMessages" -> "total_messages"
            "trustIssueCount" -> "trust_issue_count"
            else -> "last_active"
        }

        val itemsSql = """
            SELECT
                u.user_id,
                u.session_count,
                u.total_messages,
                u.last_active,
                u.first_seen,
                COALESCE((SELECT COUNT(*)::INT FROM metric_agent_executions mae
                          WHERE mae.user_id = u.user_id AND mae.guard_rejected = true), 0) AS trust_issue_count,
                COALESCE((SELECT COUNT(*)::INT FROM feedback f
                          WHERE f.user_id = u.user_id AND f.rating = 'THUMBS_DOWN'), 0) AS negative_feedback_count,
                COALESCE((SELECT COUNT(*)::INT FROM feedback f2
                          WHERE f2.user_id = u.user_id AND f2.rating = 'THUMBS_UP'), 0) AS positive_feedback_count
            FROM (
                SELECT
                    cm.user_id,
                    COUNT(DISTINCT cm.session_id) AS session_count,
                    COUNT(*) AS total_messages,
                    MAX(cm.timestamp) AS last_active,
                    MIN(cm.timestamp) AS first_seen
                FROM conversation_messages cm
                $whereClause
                GROUP BY cm.user_id
                ORDER BY $orderCol DESC
                LIMIT ? OFFSET ?
            ) u
        """.trimIndent()

        val itemParams = params + clamped + safe
        val items = jdbcTemplate.query(itemsSql, { rs, _ ->
            AdminUserSummary(
                userId = rs.getString("user_id"),
                sessionCount = rs.getInt("session_count"),
                totalMessages = rs.getInt("total_messages"),
                lastActive = rs.getLong("last_active"),
                firstSeen = rs.getLong("first_seen"),
                trustIssueCount = rs.getInt("trust_issue_count"),
                negativeFeedbackCount = rs.getInt("negative_feedback_count"),
                positiveFeedbackCount = rs.getInt("positive_feedback_count")
            )
        }, *itemParams.toTypedArray())

        return AdminPaginatedResponse(items, total, safe, clamped)
    }

    // ── Overview ───────────────────────────────────────────

    /** 대화 Overview 통계. */
    fun getOverview(periodDays: Int): AdminConversationOverview {
        val now = Instant.now()
        val periodStart = now.minusSeconds(periodDays.toLong() * 86400).toEpochMilli()
        val prevPeriodStart = now.minusSeconds(periodDays.toLong() * 2 * 86400).toEpochMilli()
        val todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val nowMs = now.toEpochMilli()

        // 현재 기간 기본 통계
        val currentStats = queryPeriodStats(periodStart, nowMs)
        val prevStats = queryPeriodStats(prevPeriodStart, periodStart)

        // 오늘 세션 수
        val todaySessions = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT session_id) FROM conversation_messages WHERE timestamp >= ?",
            Int::class.java, todayStart
        ) ?: 0

        val prevTodaySessions = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT session_id) FROM conversation_messages WHERE timestamp >= ? AND timestamp < ?",
            Int::class.java, todayStart - 86400_000L, todayStart
        ) ?: 0

        // trust 이슈
        val trustIssues = jdbcTemplate.queryForObject(
            """SELECT COUNT(DISTINCT session_id) FROM metric_agent_executions
               WHERE guard_rejected = true AND time >= to_timestamp(? / 1000.0)""",
            Int::class.java, periodStart
        ) ?: 0

        val prevTrustIssues = jdbcTemplate.queryForObject(
            """SELECT COUNT(DISTINCT session_id) FROM metric_agent_executions
               WHERE guard_rejected = true AND time >= to_timestamp(? / 1000.0) AND time < to_timestamp(? / 1000.0)""",
            Int::class.java, prevPeriodStart, periodStart
        ) ?: 0

        // negative feedback
        val negFeedback = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE rating = 'THUMBS_DOWN' AND timestamp >= to_timestamp(? / 1000.0)",
            Int::class.java, periodStart
        ) ?: 0

        val prevNegFeedback = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM feedback WHERE rating = 'THUMBS_DOWN' AND timestamp >= to_timestamp(? / 1000.0) AND timestamp < to_timestamp(? / 1000.0)",
            Int::class.java, prevPeriodStart, periodStart
        ) ?: 0

        // 변화율 계산
        val changes = mapOf(
            "totalSessions" to changeRate(currentStats.totalSessions, prevStats.totalSessions),
            "todaySessions" to changeRate(todaySessions, prevTodaySessions),
            "avgMessagesPerSession" to changeRate(currentStats.avgMessages, prevStats.avgMessages),
            "activeUsers" to changeRate(currentStats.activeUsers, prevStats.activeUsers),
            "trustIssues" to changeRate(trustIssues, prevTrustIssues),
            "negativeFeedback" to changeRate(negFeedback, prevNegFeedback)
        )

        // 트렌드 (일별 세션 수)
        val trend = jdbcTemplate.query(
            """SELECT to_char(to_timestamp(timestamp / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS date,
                      COUNT(DISTINCT session_id) AS count
               FROM conversation_messages
               WHERE timestamp >= ?
               GROUP BY date ORDER BY date""",
            { rs, _ -> TrendPoint(rs.getString("date"), rs.getInt("count")) },
            periodStart
        )

        // 채널 분포
        val channelMix = jdbcTemplate.query(
            """SELECT COALESCE(channel, 'unknown') AS channel, COUNT(*) AS count
               FROM metric_sessions WHERE time >= to_timestamp(? / 1000.0)
               GROUP BY channel ORDER BY count DESC""",
            { rs, _ -> ChannelMixEntry(rs.getString("channel"), rs.getInt("count")) },
            periodStart
        )

        // 상위 유저
        val topUsers = jdbcTemplate.query(
            """SELECT user_id, COUNT(DISTINCT session_id) AS session_count, COUNT(*) AS message_count
               FROM conversation_messages WHERE timestamp >= ?
               GROUP BY user_id ORDER BY session_count DESC LIMIT 5""",
            { rs, _ ->
                TopUserEntry(
                    rs.getString("user_id"),
                    rs.getInt("session_count"),
                    rs.getInt("message_count")
                )
            },
            periodStart
        )

        // 페르소나 사용 비율
        val personaUsage = jdbcTemplate.query(
            """SELECT COALESCE(mae.persona_id, 'unknown') AS persona_id,
                      COALESCE(p.name, mae.persona_id, 'Unknown') AS persona_name,
                      COUNT(*) AS cnt
               FROM metric_agent_executions mae
               LEFT JOIN personas p ON p.id = mae.persona_id
               WHERE mae.time >= to_timestamp(? / 1000.0)
               GROUP BY mae.persona_id, p.name
               ORDER BY cnt DESC""",
            { rs, _ -> Triple(rs.getString("persona_id"), rs.getString("persona_name"), rs.getInt("cnt")) },
            periodStart
        )
        val totalExecutions = personaUsage.sumOf { it.third }.coerceAtLeast(1)
        val personaUsageList = personaUsage.map { (id, name, cnt) ->
            PersonaUsageEntry(id, name, (cnt.toDouble() / totalExecutions * 100).let { Math.round(it * 10) / 10.0 })
        }

        // 최근 세션 (10개)
        val recentSessions = listSessions(
            SessionQueryFilters(sortBy = "lastActivity", order = "desc"),
            limit = 10, offset = 0
        ).items

        // trust 이벤트 세션
        val trustEvents = listSessions(
            SessionQueryFilters(trust = listOf("flagged"), sortBy = "lastActivity", order = "desc"),
            limit = 10, offset = 0
        ).items

        return AdminConversationOverview(
            totalSessions = currentStats.totalSessions,
            todaySessions = todaySessions,
            avgMessagesPerSession = currentStats.avgMessages,
            activeUsers = currentStats.activeUsers,
            trustIssues = trustIssues,
            negativeFeedback = negFeedback,
            changes = changes,
            trend = trend,
            channelMix = channelMix,
            topUsers = topUsers,
            personaUsage = personaUsageList,
            recentSessions = recentSessions,
            trustEvents = trustEvents
        )
    }

    // ── 태그 ───────────────────────────────────────────────

    /** 세션에 태그 추가. */
    fun addTag(sessionId: String, label: String, comment: String?, createdBy: String): AdminSessionTag {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toEpochMilli()
        jdbcTemplate.update(
            "INSERT INTO session_tags (id, session_id, label, comment, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id, sessionId, label, comment, createdBy, now
        )
        return AdminSessionTag(id, label, comment, createdBy, now)
    }

    /** 태그 제거. */
    fun removeTag(sessionId: String, tagId: String): Boolean {
        return jdbcTemplate.update(
            "DELETE FROM session_tags WHERE id = ? AND session_id = ?", tagId, sessionId
        ) > 0
    }

    /** 세션 ID 목록으로 태그 일괄 조회. */
    fun getTagsForSessions(sessionIds: List<String>): Map<String, List<AdminSessionTag>> {
        if (sessionIds.isEmpty()) return emptyMap()

        val placeholders = sessionIds.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT id, session_id, label, comment, created_by, created_at FROM session_tags WHERE session_id IN ($placeholders) ORDER BY created_at",
            { rs, _ ->
                rs.getString("session_id") to AdminSessionTag(
                    id = rs.getString("id"),
                    label = rs.getString("label"),
                    comment = rs.getString("comment"),
                    createdBy = rs.getString("created_by"),
                    createdAt = rs.getLong("created_at")
                )
            },
            *sessionIds.toTypedArray()
        ).groupBy({ it.first }, { it.second })
    }

    /** 단일 세션 태그 조회. */
    private fun getTagsForSession(sessionId: String): List<AdminSessionTag> {
        return jdbcTemplate.query(
            "SELECT id, session_id, label, comment, created_by, created_at FROM session_tags WHERE session_id = ? ORDER BY created_at",
            { rs, _ ->
                AdminSessionTag(
                    id = rs.getString("id"),
                    label = rs.getString("label"),
                    comment = rs.getString("comment"),
                    createdBy = rs.getString("created_by"),
                    createdAt = rs.getLong("created_at")
                )
            },
            sessionId
        )
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────

    private data class PeriodStats(val totalSessions: Int, val activeUsers: Int, val avgMessages: Double)

    private fun queryPeriodStats(fromMs: Long, toMs: Long): PeriodStats {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                   COUNT(DISTINCT session_id) AS total_sessions,
                   COUNT(DISTINCT user_id) AS active_users,
                   CASE WHEN COUNT(DISTINCT session_id) = 0 THEN 0.0
                        ELSE ROUND(COUNT(*)::NUMERIC / COUNT(DISTINCT session_id), 1)
                   END AS avg_messages
               FROM conversation_messages
               WHERE timestamp >= ? AND timestamp < ?""",
            fromMs, toMs
        )
        return PeriodStats(
            totalSessions = (result["total_sessions"] as? Number)?.toInt() ?: 0,
            activeUsers = (result["active_users"] as? Number)?.toInt() ?: 0,
            avgMessages = (result["avg_messages"] as? Number)?.toDouble() ?: 0.0
        )
    }

    private fun changeRate(current: Int, previous: Int): Double {
        if (previous == 0) return if (current > 0) 1.0 else 0.0
        return Math.round((current - previous).toDouble() / previous * 100) / 100.0
    }

    private fun changeRate(current: Double, previous: Double): Double {
        if (previous == 0.0) return if (current > 0.0) 1.0 else 0.0
        return Math.round((current - previous) / previous * 100) / 100.0
    }

    /** 세션 목록 필터 → WHERE 절 + JOIN 절 + HAVING 절 빌드. */
    private fun buildSessionWhereClause(filters: SessionQueryFilters): WhereClause {
        val conditions = mutableListOf<String>()
        val havingConditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        val joins = StringBuilder()

        // 텍스트 검색
        if (!filters.q.isNullOrBlank()) {
            conditions += "cm.session_id IN (SELECT DISTINCT session_id FROM conversation_messages WHERE content ILIKE ?)"
            params += "%${filters.q}%"
        }

        // 유저 필터
        if (!filters.userId.isNullOrBlank()) {
            conditions += "cm.user_id = ?"
            params += filters.userId
        }

        // 날짜 필터
        if (filters.dateFrom != null) {
            conditions += "cm.timestamp >= ?"
            params += filters.dateFrom
        }
        if (filters.dateTo != null) {
            conditions += "cm.timestamp <= ?"
            params += filters.dateTo
        }

        // 채널 필터 (metric_sessions JOIN 필요)
        if (!filters.channel.isNullOrEmpty()) {
            val placeholders = filters.channel.joinToString(",") { "?" }
            conditions += """cm.session_id IN (
                SELECT DISTINCT session_id FROM metric_sessions WHERE channel IN ($placeholders)
            )"""
            params.addAll(filters.channel)
        }

        // 페르소나 필터
        if (!filters.personaId.isNullOrBlank()) {
            conditions += """cm.session_id IN (
                SELECT DISTINCT session_id FROM metric_agent_executions WHERE persona_id = ?
            )"""
            params += filters.personaId
        }

        // trust 필터
        if (!filters.trust.isNullOrEmpty()) {
            if (filters.trust.contains("clean") && !filters.trust.contains("flagged")) {
                // clean만: guard 거부 0건인 세션
                conditions += """cm.session_id NOT IN (
                    SELECT DISTINCT session_id FROM metric_agent_executions WHERE guard_rejected = true
                )"""
            } else if (filters.trust.contains("flagged") && !filters.trust.contains("clean")) {
                // flagged만: guard 거부 1건 이상
                conditions += """cm.session_id IN (
                    SELECT DISTINCT session_id FROM metric_agent_executions WHERE guard_rejected = true
                )"""
            }
            // clean+flagged 둘 다 선택 → 필터 없음 (전부 포함)
        }

        // feedback 필터
        if (!filters.feedback.isNullOrEmpty()) {
            val fbConditions = mutableListOf<String>()
            if (filters.feedback.contains("positive")) {
                fbConditions += "cm.session_id IN (SELECT DISTINCT session_id FROM feedback WHERE rating = 'THUMBS_UP')"
            }
            if (filters.feedback.contains("negative")) {
                fbConditions += "cm.session_id IN (SELECT DISTINCT session_id FROM feedback WHERE rating = 'THUMBS_DOWN')"
            }
            if (filters.feedback.contains("none")) {
                fbConditions += "cm.session_id NOT IN (SELECT DISTINCT session_id FROM feedback WHERE session_id IS NOT NULL)"
            }
            if (fbConditions.isNotEmpty()) {
                conditions += "(${fbConditions.joinToString(" OR ")})"
            }
        }

        val whereStr = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val havingStr = if (havingConditions.isEmpty()) "" else "HAVING ${havingConditions.joinToString(" AND ")}"

        return WhereClause(joins.toString(), whereStr, havingStr, params)
    }

    private data class WhereClause(
        val joins: String,
        val clause: String,
        val having: String,
        val params: List<Any>
    )

    private fun mapSessionRow(rs: ResultSet): AdminSessionRow {
        val preview = rs.getString("preview")
        return AdminSessionRow(
            sessionId = rs.getString("session_id"),
            userId = rs.getString("user_id"),
            channel = rs.getString("channel"),
            personaId = rs.getString("persona_id"),
            personaName = rs.getString("persona_name"),
            messageCount = rs.getInt("message_count"),
            preview = preview?.take(PREVIEW_LENGTH),
            lastActivity = rs.getLong("last_activity"),
            duration = rs.getObject("duration") as? Long,
            trust = deriveTrust(rs.getInt("guard_reject_count")),
            feedback = mapFeedback(rs.getString("feedback_rating")),
            tags = emptyList()
        )
    }
}
