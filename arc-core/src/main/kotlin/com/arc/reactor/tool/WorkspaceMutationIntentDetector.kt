package com.arc.reactor.tool

internal object WorkspaceMutationIntentDetector {

    fun isWorkspaceMutationPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return hasWorkspaceHint(normalized) && hasMutationHint(normalized) && hasMutationTargetHint(normalized)
    }

    private fun hasWorkspaceHint(normalized: String): Boolean {
        return WORKSPACE_HINTS.any(normalized::contains)
    }

    private fun hasMutationHint(normalized: String): Boolean {
        if (READ_ONLY_LOOKUP_EXCEPTIONS.any(normalized::contains)) return false
        return MUTATION_REGEXES.any { regex -> regex.containsMatchIn(normalized) } ||
            KOREAN_MUTATION_HINTS.any(normalized::contains)
    }

    private fun hasMutationTargetHint(normalized: String): Boolean {
        return MUTATION_TARGET_HINTS.any(normalized::contains)
    }

    private val WORKSPACE_HINTS = setOf(
        "jira", "confluence", "bitbucket", "이슈", "티켓", "프로젝트", "페이지", "문서", "저장소",
        "repository", "repo", "pull request", "pr", "액션 아이템", "action item",
        "swagger", "openapi", "spec", "스펙", "catalog", "카탈로그", "endpoint", "schema",
        "엔드포인트", "스키마"
    )

    private val MUTATION_REGEXES = listOf(
        Regex("\\bcreate\\b"),
        Regex("\\bupdate\\b"),
        Regex("\\bedit\\b"),
        Regex("\\bmodify\\b"),
        Regex("\\bchange\\b"),
        Regex("\\breassign\\b"),
        Regex("\\bassign\\b"),
        Regex("\\btransition\\b"),
        Regex("\\bapprove\\b"),
        Regex("\\bcomment\\b"),
        Regex("\\bdelete\\b"),
        Regex("\\bremove\\b"),
        Regex("\\bconvert\\b"),
        Regex("\\bwrite\\b")
    )

    private val KOREAN_MUTATION_HINTS = setOf(
        "작성해", "만들어", "수정해", "업데이트해", "변경해", "재할당", "할당해", "전이해", "바꿔",
        "승인해", "코멘트해", "댓글 달", "삭제해", "제거해", "변환해"
    )

    private val READ_ONLY_LOOKUP_EXCEPTIONS = setOf("unassigned", "미할당")

    private val MUTATION_TARGET_HINTS = setOf(
        "issue", "ticket", "comment", "page", "document", "attachment", "action item",
        "pull request", "branch", "review", "이슈", "티켓", "코멘트", "댓글", "페이지",
        "문서", "첨부", "액션 아이템", "브랜치", "리뷰",
        "spec", "swagger", "openapi", "catalog", "endpoint", "schema",
        "스펙", "카탈로그", "엔드포인트", "스키마"
    )
}
