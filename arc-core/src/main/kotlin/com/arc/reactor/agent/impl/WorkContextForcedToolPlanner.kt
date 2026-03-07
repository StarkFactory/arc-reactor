package com.arc.reactor.agent.impl

internal data class ForcedToolCallPlan(
    val toolName: String,
    val arguments: Map<String, Any?>
)

internal object WorkContextForcedToolPlanner {
    private val issueKeyRegex = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b")
    private val workOwnerHints = setOf(
        "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
    )
    private val workItemContextHints = setOf(
        "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr", "오픈 pr", "다음 액션", "next action"
    )
    private val workServiceContextHints = setOf(
        "서비스 상황", "서비스 현황", "service context", "service summary", "현재 상황", "현재 현황",
        "최근 jira", "최근 jira 이슈", "열린 pr", "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
    )
    private val serviceRegexes = listOf(
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*서비스", RegexOption.IGNORE_CASE),
        Regex("\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*service\\b", RegexOption.IGNORE_CASE)
    )

    fun plan(prompt: String?): ForcedToolCallPlan? {
        if (prompt.isNullOrBlank()) return null

        val normalized = prompt.lowercase()
        val issueKey = extractIssueKey(prompt)

        if (workOwnerHints.any { normalized.contains(it) }) {
            val query = issueKey ?: extractServiceName(prompt) ?: return null
            return ForcedToolCallPlan(
                toolName = "work_owner_lookup",
                arguments = mapOf("query" to query)
            )
        }

        if (issueKey != null && workItemContextHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_item_context",
                arguments = mapOf("issueKey" to issueKey)
            )
        }

        val serviceName = extractServiceName(prompt)
        if (serviceName != null && workServiceContextHints.any { normalized.contains(it) }) {
            return ForcedToolCallPlan(
                toolName = "work_service_context",
                arguments = mapOf("service" to serviceName)
            )
        }

        return null
    }

    private fun extractIssueKey(prompt: String): String? {
        return issueKeyRegex.find(prompt.uppercase())?.value
    }

    private fun extractServiceName(prompt: String): String? {
        return serviceRegexes.asSequence()
            .mapNotNull { regex -> regex.find(prompt)?.groupValues?.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }
}
