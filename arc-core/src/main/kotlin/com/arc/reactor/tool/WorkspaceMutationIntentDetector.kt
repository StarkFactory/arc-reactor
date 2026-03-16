package com.arc.reactor.tool

/**
 * 사용자 프롬프트에서 워크스페이스 변경 의도(mutation intent)를 감지하는 탐지기.
 *
 * 세 가지 조건이 모두 충족되어야 변경 의도로 판단한다:
 * 1. 워크스페이스 힌트 존재 (Jira, Confluence, Bitbucket 등)
 * 2. 변경 행위 힌트 존재 (create, update, delete 등)
 * 3. 변경 대상 힌트 존재 (issue, page, PR 등)
 *
 * 이 탐지 결과는 읽기 전용 모드에서 변경 도구 호출을 차단하는 데 사용된다.
 */
internal object WorkspaceMutationIntentDetector {

    /**
     * 프롬프트가 워크스페이스 변경 의도를 포함하는지 판단한다.
     *
     * @param prompt 사용자 프롬프트 텍스트
     * @return 변경 의도가 감지되면 true
     */
    fun isWorkspaceMutationPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return hasWorkspaceHint(normalized) && hasMutationHint(normalized) && hasMutationTargetHint(normalized)
    }

    /** 워크스페이스 관련 키워드가 포함되어 있는지 확인 */
    private fun hasWorkspaceHint(normalized: String): Boolean {
        return WORKSPACE_HINTS.any(normalized::contains)
    }

    /**
     * 변경 행위 힌트가 포함되어 있는지 확인.
     * "unassigned"/"미할당" 같은 읽기 전용 조회 예외를 먼저 체크한다.
     */
    private fun hasMutationHint(normalized: String): Boolean {
        if (READ_ONLY_LOOKUP_EXCEPTIONS.any(normalized::contains)) return false
        return MUTATION_REGEXES.any { regex -> regex.containsMatchIn(normalized) } ||
            KOREAN_MUTATION_HINTS.any(normalized::contains)
    }

    /** 변경 대상 힌트가 포함되어 있는지 확인 */
    private fun hasMutationTargetHint(normalized: String): Boolean {
        return MUTATION_TARGET_HINTS.any(normalized::contains)
    }

    /** 워크스페이스 플랫폼 및 개체 힌트 (영어 + 한국어) */
    private val WORKSPACE_HINTS = setOf(
        "jira", "confluence", "bitbucket", "이슈", "티켓", "프로젝트", "페이지", "문서", "저장소",
        "repository", "repo", "pull request", "pr", "액션 아이템", "action item",
        "swagger", "openapi", "spec", "스펙", "catalog", "카탈로그", "endpoint", "schema",
        "엔드포인트", "스키마"
    )

    /** 영어 변경 행위를 감지하는 정규식 목록 (\b로 단어 경계 매칭) */
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

    /** 한국어 변경 행위 힌트 */
    private val KOREAN_MUTATION_HINTS = setOf(
        "작성해", "만들어", "수정해", "업데이트해", "변경해", "재할당", "할당해", "전이해", "바꿔",
        "승인해", "코멘트해", "댓글 달", "삭제해", "제거해", "변환해"
    )

    /** 읽기 전용 조회 예외 — 이 키워드가 있으면 변경 의도로 판단하지 않음 */
    private val READ_ONLY_LOOKUP_EXCEPTIONS = setOf("unassigned", "미할당")

    /** 변경 대상 개체 힌트 (영어 + 한국어) */
    private val MUTATION_TARGET_HINTS = setOf(
        "issue", "ticket", "comment", "page", "document", "attachment", "action item",
        "pull request", "branch", "review", "이슈", "티켓", "코멘트", "댓글", "페이지",
        "문서", "첨부", "액션 아이템", "브랜치", "리뷰",
        "spec", "swagger", "openapi", "catalog", "endpoint", "schema",
        "스펙", "카탈로그", "엔드포인트", "스키마"
    )
}
