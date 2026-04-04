package com.arc.reactor.admin.controller

/**
 * 어드민 세션 API 응답 모델.
 */

/** 페이지네이션 응답 래퍼. */
data class AdminPaginatedResponse<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

/** 세션 목록 항목. */
data class AdminSessionRow(
    val sessionId: String,
    val userId: String,
    val channel: String?,
    val personaId: String?,
    val personaName: String?,
    val messageCount: Int,
    val preview: String?,
    val lastActivity: Long,
    val duration: Long?,
    val trust: String,
    val feedback: String?,
    val tags: List<AdminSessionTag>
)

/** 세션 상세 (메시지 포함). */
data class AdminSessionDetail(
    val sessionId: String,
    val userId: String,
    val channel: String?,
    val personaId: String?,
    val personaName: String?,
    val model: String?,
    val messageCount: Int,
    val duration: Long?,
    val startedAt: Long?,
    val lastActivity: Long,
    val trust: String,
    val feedback: String?,
    val tags: List<AdminSessionTag>,
    val messages: List<AdminChatMessage>
)

/** 채팅 메시지. */
data class AdminChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val model: String? = null,
    val durationMs: Long? = null,
    val grounded: Boolean? = null,
    val blockReason: String? = null,
    val verifiedSourceCount: Int? = null
)

/** 세션 태그. */
data class AdminSessionTag(
    val id: String,
    val label: String,
    val comment: String?,
    val createdBy: String,
    val createdAt: Long
)

/** 유저 요약. */
data class AdminUserSummary(
    val userId: String,
    val sessionCount: Int,
    val totalMessages: Int,
    val lastActive: Long,
    val firstSeen: Long,
    val trustIssueCount: Int,
    val negativeFeedbackCount: Int,
    val positiveFeedbackCount: Int
)

/** Overview 통계. */
data class AdminConversationOverview(
    val totalSessions: Int,
    val todaySessions: Int,
    val avgMessagesPerSession: Double,
    val activeUsers: Int,
    val trustIssues: Int,
    val negativeFeedback: Int,
    val changes: Map<String, Double>,
    val trend: List<TrendPoint>,
    val channelMix: List<ChannelMixEntry>,
    val topUsers: List<TopUserEntry>,
    val personaUsage: List<PersonaUsageEntry>,
    val recentSessions: List<AdminSessionRow>,
    val trustEvents: List<AdminSessionRow>
)

data class TrendPoint(val date: String, val count: Int)
data class ChannelMixEntry(val channel: String, val count: Int)
data class TopUserEntry(val userId: String, val sessionCount: Int, val messageCount: Int)
data class PersonaUsageEntry(val personaId: String, val name: String, val percentage: Double)

/** 세션 목록 필터. */
data class SessionQueryFilters(
    val q: String? = null,
    val userId: String? = null,
    val channel: List<String>? = null,
    val personaId: String? = null,
    val trust: List<String>? = null,
    val feedback: List<String>? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val sortBy: String = "lastActivity",
    val order: String = "desc"
)

/** 유저 목록 필터. */
data class UserQueryFilters(
    val q: String? = null,
    val sortBy: String = "lastActive",
    val periodDays: Int? = null
)

/** 태그 추가 요청. */
data class AddTagRequest(
    @field:jakarta.validation.constraints.NotBlank
    val label: String,
    val comment: String? = null
)
