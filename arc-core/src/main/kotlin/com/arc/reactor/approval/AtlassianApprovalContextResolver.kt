package com.arc.reactor.approval

/**
 * atlassian-mcp-server 도구에 특화된 [ApprovalContextResolver] 프로덕션 구현체.
 *
 * R221 [HeuristicApprovalContextResolver]는 일반적인 이름 기반 휴리스틱을 제공했다면,
 * 이 클래스는 **실제 atlassian-mcp-server가 노출하는 도구 이름과 인수 스키마**를 알고
 * 그에 맞춰 풍부한 [ApprovalContext]를 생성한다.
 *
 * ## 지원 도구 카테고리
 *
 * - **Jira**: `jira_get_issue`, `jira_search_issues`, `jira_search_by_text`,
 *   `jira_search_my_issues_by_text`, `jira_my_open_issues`, `jira_due_soon_issues`,
 *   `jira_daily_briefing`, `jira_blocker_digest`, `jira_list_projects`, `jira_search_users`,
 *   `jira_get_comments`, `jira_get_issue_changelog`, `jira_get_transitions`
 * - **Confluence**: `confluence_search`, `confluence_search_by_text`,
 *   `confluence_answer_question`, `confluence_get_page`, `confluence_get_page_content`,
 *   `confluence_get_page_comments`, `confluence_get_children`, `confluence_list_spaces`,
 *   `confluence_generate_weekly_auto_summary_draft`
 * - **Bitbucket**: `bitbucket_list_prs`, `bitbucket_get_pr`, `bitbucket_get_pr_diff`,
 *   `bitbucket_my_authored_prs`, `bitbucket_review_queue`, `bitbucket_review_sla_alerts`,
 *   `bitbucket_stale_prs`, `bitbucket_list_repositories`, `bitbucket_list_branches`,
 *   `bitbucket_list_commits`
 *
 * ## 현재 atlassian-mcp-server는 read-only
 *
 * 위 도구들은 모두 **읽기 전용** 이다. 따라서 [Reversibility.REVERSIBLE]로 분류되며
 * (읽기는 상태를 바꾸지 않으므로 "되돌릴 수 있음"과 등가), 일반적으로 승인이 요구되지
 * 않는다. 그러나 다음 시나리오에서 이 리졸버가 유용하다:
 *
 * 1. 민감한 프로젝트/저장소에 스트릭트한 정책을 적용한 경우 (예: HR 전용 Jira 프로젝트)
 * 2. 대량 스캔 시 비용 가시화 (예: `bitbucket_review_queue`가 전체 저장소를 스캔)
 * 3. 감사 추적용 컨텍스트 기록 ([com.arc.reactor.approval.ToolApprovalRequest.context]에
 *    저장되어 사후 분석 가능)
 * 4. 향후 쓰기 도구(`jira_create_issue`, `confluence_update_page`, `bitbucket_merge_pr` 등)
 *    추가 시 확장 가능한 기반
 *
 * ## MCP 호환성
 *
 * 이 리졸버는 atlassian-mcp-server 도구의 **인수를 전혀 수정하지 않는다**. 오직 읽기만
 * 하여 메타데이터를 생성한다. 모든 MCP 경로(SSE, tool discovery, tool call, response
 * parsing)는 불변.
 *
 * ## opt-in
 *
 * 이 리졸버는 **기본적으로 활성화되지 않는다**. 사용자가 `@Bean`으로 등록하거나
 * `arc.reactor.approval.atlassian-resolver.enabled=true` 속성을 설정해야 사용된다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * @Configuration
 * class MyApprovalConfig {
 *     @Bean
 *     fun approvalContextResolver(): ApprovalContextResolver =
 *         AtlassianApprovalContextResolver()
 * }
 * ```
 *
 * 또는 체인 방식:
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = ApprovalContextResolver { tool, args ->
 *     AtlassianApprovalContextResolver().resolve(tool, args)
 *         ?: HeuristicApprovalContextResolver().resolve(tool, args)
 * }
 * ```
 *
 * @see ApprovalContextResolver 인터페이스
 * @see HeuristicApprovalContextResolver 일반 휴리스틱 fallback
 */
class AtlassianApprovalContextResolver : ApprovalContextResolver {

    override fun resolve(
        toolName: String,
        arguments: Map<String, Any?>
    ): ApprovalContext? {
        val category = categorize(toolName) ?: return null
        val scope = extractImpactScope(category, arguments)
        return ApprovalContext(
            reason = buildReason(category, toolName),
            action = buildAction(category, toolName, arguments, scope),
            impactScope = scope,
            reversibility = Reversibility.REVERSIBLE
        )
    }

    /** 도구 이름 prefix로 Atlassian 카테고리를 분류한다. */
    internal fun categorize(toolName: String): AtlassianCategory? {
        return when {
            toolName.startsWith("jira_") -> AtlassianCategory.JIRA
            toolName.startsWith("confluence_") -> AtlassianCategory.CONFLUENCE
            toolName.startsWith("bitbucket_") -> AtlassianCategory.BITBUCKET
            else -> null
        }
    }

    /** 카테고리별 승인 사유 문구를 구성한다. */
    internal fun buildReason(category: AtlassianCategory, toolName: String): String {
        return when (category) {
            AtlassianCategory.JIRA -> "Jira 읽기 작업: $toolName"
            AtlassianCategory.CONFLUENCE -> "Confluence 읽기 작업: $toolName"
            AtlassianCategory.BITBUCKET -> "Bitbucket 읽기 작업: $toolName"
        }
    }

    /**
     * 카테고리별 influence scope 추출.
     * 각 카테고리의 인수 키 우선순위를 적용한다.
     */
    internal fun extractImpactScope(
        category: AtlassianCategory,
        arguments: Map<String, Any?>
    ): String? {
        val keys = when (category) {
            AtlassianCategory.JIRA -> JIRA_SCOPE_KEYS
            AtlassianCategory.CONFLUENCE -> CONFLUENCE_SCOPE_KEYS
            AtlassianCategory.BITBUCKET -> BITBUCKET_SCOPE_KEYS
        }
        val value = firstNonBlank(arguments, keys) ?: return null
        return value.take(IMPACT_SCOPE_MAX_LEN)
    }

    /**
     * 사람이 읽을 수 있는 행동 문자열을 구성한다.
     * 예: "jira_get_issue(JAR-42)", "bitbucket_list_prs(workspace=ihunet, repo=web-labs)"
     */
    internal fun buildAction(
        category: AtlassianCategory,
        toolName: String,
        arguments: Map<String, Any?>,
        scope: String?
    ): String {
        if (scope != null && scope.isNotBlank()) {
            return "$toolName($scope)"
        }
        val primary = when (category) {
            AtlassianCategory.JIRA -> JIRA_PRIMARY_KEYS
            AtlassianCategory.CONFLUENCE -> CONFLUENCE_PRIMARY_KEYS
            AtlassianCategory.BITBUCKET -> BITBUCKET_PRIMARY_KEYS
        }
        val firstArg = firstNonBlank(arguments, primary)
        return if (firstArg != null) "$toolName($firstArg)" else toolName
    }

    /** 맵에서 주어진 키 목록 순서대로 첫 non-blank 값을 반환한다. */
    private fun firstNonBlank(
        arguments: Map<String, Any?>,
        keys: List<String>
    ): String? {
        for (key in keys) {
            val raw = arguments[key] ?: continue
            val text = raw.toString().trim()
            if (text.isNotBlank()) return text
        }
        return null
    }

    companion object {
        /** Jira impactScope 추출 우선순위 — 이슈 > 프로젝트 > JQL > 키워드 > 담당자 */
        internal val JIRA_SCOPE_KEYS = listOf(
            "issueKey", "project", "projectKey", "jql",
            "keyword", "assigneeAccountId", "requesterEmail"
        )

        /** Confluence impactScope 추출 우선순위 — 페이지 > 스페이스 > 쿼리 */
        internal val CONFLUENCE_SCOPE_KEYS = listOf(
            "pageId", "spaceKey", "space", "query", "keyword", "question"
        )

        /** Bitbucket impactScope 추출 우선순위 — PR > 저장소 > 워크스페이스 > 브랜치 */
        internal val BITBUCKET_SCOPE_KEYS = listOf(
            "pullRequestId", "prId", "repoSlug", "repo",
            "repository", "workspace", "branch"
        )

        /** Jira action 텍스트용 추가 키 */
        internal val JIRA_PRIMARY_KEYS = JIRA_SCOPE_KEYS + listOf("ticketKey")

        /** Confluence action 텍스트용 추가 키 */
        internal val CONFLUENCE_PRIMARY_KEYS = CONFLUENCE_SCOPE_KEYS + listOf("title")

        /** Bitbucket action 텍스트용 추가 키 */
        internal val BITBUCKET_PRIMARY_KEYS = BITBUCKET_SCOPE_KEYS + listOf("commitHash")

        /** impactScope 최대 길이 (UI 노출용) */
        internal const val IMPACT_SCOPE_MAX_LEN = 120
    }
}

/**
 * Atlassian 도구 카테고리 분류 enum.
 * [AtlassianApprovalContextResolver]가 내부에서 사용한다.
 */
enum class AtlassianCategory {
    JIRA,
    CONFLUENCE,
    BITBUCKET
}
