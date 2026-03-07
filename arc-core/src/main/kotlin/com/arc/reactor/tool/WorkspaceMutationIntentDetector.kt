package com.arc.reactor.tool

internal object WorkspaceMutationIntentDetector {

    fun isWorkspaceMutationPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return hasWorkspaceHint(normalized) && hasMutationHint(normalized)
    }

    private fun hasWorkspaceHint(normalized: String): Boolean {
        return WORKSPACE_HINTS.any(normalized::contains)
    }

    private fun hasMutationHint(normalized: String): Boolean {
        return MUTATION_HINTS.any(normalized::contains)
    }

    private val WORKSPACE_HINTS = setOf(
        "jira", "confluence", "bitbucket", "이슈", "티켓", "프로젝트", "페이지", "문서", "저장소",
        "repository", "repo", "pull request", "pr", "액션 아이템", "action item"
    )

    private val MUTATION_HINTS = setOf(
        "create", "update", "edit", "modify", "change", "assign", "reassign", "transition", "approve",
        "comment", "delete", "remove", "convert", "write", "작성", "만들", "수정", "업데이트", "변경",
        "재할당", "할당", "전이", "바꿔", "승인", "코멘트", "댓글", "삭제", "변환"
    )
}
