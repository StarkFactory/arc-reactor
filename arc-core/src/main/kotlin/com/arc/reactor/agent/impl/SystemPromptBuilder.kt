package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import com.arc.reactor.tool.WorkspaceMutationIntentDetector

class SystemPromptBuilder(
    private val postProcessor: SystemPromptPostProcessor? = null
) {

    fun build(
        basePrompt: String,
        ragContext: String?,
        responseFormat: ResponseFormat = ResponseFormat.TEXT,
        responseSchema: String? = null,
        userPrompt: String? = null
    ): String {
        val parts = mutableListOf(basePrompt)
        parts.add(buildGroundingInstruction(responseFormat, userPrompt))

        if (ragContext != null) {
            parts.add(buildRagInstruction(ragContext))
        }

        when (responseFormat) {
            ResponseFormat.JSON -> parts.add(buildJsonInstruction(responseSchema))
            ResponseFormat.YAML -> parts.add(buildYamlInstruction(responseSchema))
            ResponseFormat.TEXT -> {}
        }

        val result = parts.joinToString("\n\n")
        return postProcessor?.process(result) ?: result
    }

    private fun buildGroundingInstruction(responseFormat: ResponseFormat, userPrompt: String?): String = buildString {
        append("[Grounding Rules]\n")
        append("Use only facts supported by the retrieved context or tool results.\n")
        append("If you cannot verify a fact, say you cannot verify it instead of guessing.\n")
        append("For Jira, Confluence, Bitbucket, Swagger/OpenAPI, policy, documentation, or internal knowledge requests, ")
        append("call the relevant workspace tool before answering.\n")
        append("If a Jira, Confluence, Bitbucket, or work-management request asks to create, update, assign, reassign, ")
        append("comment, approve, transition, convert, or delete something, refuse it as not allowed in read-only mode.\n")
        append("Prefer `confluence_answer_question` for Confluence policy, wiki, service, or page-summary questions.")
        append("\nDo not answer Confluence knowledge questions from `confluence_search` or `confluence_search_by_text` alone; ")
        append("use them only for discovery, then verify with `confluence_answer_question` or `confluence_get_page_content`.")
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(userPrompt)) {
            append("\nFor this request, you MUST refuse the action.")
            append(" State that the workspace is read-only and the requested mutation is not allowed.")
            append(" Do not ask follow-up questions.")
            append(" You may call a single read-only lookup tool only to cite the current item,")
            append(" but you MUST still refuse the mutation itself.")
        }
        if (looksLikeConfluenceAnswerPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `confluence_answer_question` before answering.")
            append(" Do not reply directly from general knowledge or prior context.")
        }
        if (looksLikeWorkBriefingPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_morning_briefing` before answering.")
            append(" Do not assemble the briefing manually.")
            append(" The tool accepts optional inputs and will use default profile settings when details are omitted.")
            append(" Infer obvious project/repository hints from the user message, but do not ask follow-up questions before the first tool call.")
        }
        if (looksLikeWorkOwnerPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_owner_lookup` before answering.")
            append(" Do not guess ownership from prior context.")
        }
        if (looksLikeWorkItemContextPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_item_context` before answering.")
            append(" Do not summarize Jira, Confluence, or Bitbucket context manually.")
        }
        if (looksLikeWorkServiceContextPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_service_context` before answering.")
            append(" Do not summarize service state from general knowledge or prior context.")
        }
        when {
            looksLikeJiraProjectListPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_list_projects` before answering.")
                append(" Do not answer from prior knowledge.")
            }
            looksLikeJiraIssueTransitionPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_get_transitions` before answering.")
                append(" Do not guess the available states.")
            }
            looksLikeJiraIssuePrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_get_issue` before answering.")
                append(" Do not answer from prior knowledge.")
            }
            looksLikeJiraDueSoonPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_due_soon_issues` before answering.")
            }
            looksLikeJiraBlockerPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_blocker_digest` before answering.")
            }
            looksLikeJiraDailyBriefingPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_daily_briefing` before answering.")
            }
            looksLikeJiraSearchPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `jira_search_by_text` or `jira_search_issues` before answering.")
                append(" Prefer `jira_search_by_text` when the user gives a keyword, and `jira_search_issues` for project-scoped searches.")
            }
            looksLikeJiraPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call one or more Jira tools before answering.")
                append(" Prefer `jira_search_issues` for project-scoped status questions.")
            }
        }
        when {
            looksLikeBitbucketReviewSlaPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_review_sla_alerts` before answering.")
            }
            looksLikeBitbucketReviewQueuePrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_review_queue` before answering.")
            }
            looksLikeBitbucketStalePrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_stale_prs` before answering.")
            }
            looksLikeBitbucketBranchPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_list_branches` before answering.")
            }
            looksLikeBitbucketRepositoryPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_list_repositories` before answering.")
            }
            looksLikeBitbucketPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `bitbucket_list_prs` or `bitbucket_get_pr` before answering.")
            }
        }
        when {
            looksLikeSwaggerListPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_list` before answering.")
            }
            looksLikeSwaggerValidatePrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_validate` before answering.")
            }
            looksLikeSwaggerSchemaPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_load` and then `spec_schema` before answering.")
            }
            looksLikeSwaggerDetailPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_load` and then `spec_detail` before answering.")
            }
            looksLikeSwaggerSearchPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_load` and then `spec_search` before answering.")
            }
            looksLikeSwaggerRemovePrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_remove` before answering.")
            }
            looksLikeSwaggerPrompt(userPrompt) -> {
                append("\nFor this request, you MUST call `spec_load` and then `spec_summary` before answering.")
            }
        }
        if (responseFormat == ResponseFormat.TEXT) {
            append("\nEnd the response with a 'Sources' section that lists the supporting links.")
        }
    }

    private fun buildJsonInstruction(responseSchema: String?): String = buildString {
        append("[Response Format]\n")
        append("You MUST respond with valid JSON only.\n")
        append("- Do NOT wrap the response in markdown code blocks (no ```json or ```).\n")
        append("- Do NOT include any text before or after the JSON.\n")
        append("- The response MUST start with '{' or '[' and end with '}' or ']'.")
        if (responseSchema != null) {
            append("\n\nExpected JSON schema:\n$responseSchema")
        }
    }

    private fun buildYamlInstruction(responseSchema: String?): String = buildString {
        append("[Response Format]\n")
        append("You MUST respond with valid YAML only.\n")
        append("- Do NOT wrap the response in markdown code blocks (no ```yaml or ```).\n")
        append("- Do NOT include any text before or after the YAML.\n")
        append("- Use proper YAML indentation (2 spaces).")
        if (responseSchema != null) {
            append("\n\nExpected YAML structure:\n$responseSchema")
        }
    }

    private fun buildRagInstruction(ragContext: String): String = buildString {
        append("[Retrieved Context]\n")
        append("The following information was retrieved from the knowledge base and may be relevant.\n")
        append("Use this context to inform your answer when relevant. ")
        append("If the context does not contain the answer, say so rather than guessing.\n")
        append("Do not mention the retrieval process to the user.\n\n")
        append(ragContext)
    }

    private fun looksLikeConfluenceAnswerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val knowledgeHint = CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) }
        val answerHint = CONFLUENCE_ANSWER_HINTS.any { normalized.contains(it) }
        return knowledgeHint && answerHint
    }

    private fun looksLikeWorkBriefingPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_BRIEFING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkOwnerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return WORK_OWNER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkItemContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val hasIssueKey = ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
        return hasIssueKey && WORK_ITEM_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkServiceContextPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        val hasServiceMention = normalized.contains("service") || normalized.contains("서비스")
        return hasServiceMention && WORK_SERVICE_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return JIRA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return BITBUCKET_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) || SWAGGER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraProjectListPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && PROJECT_LIST_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraIssueTransitionPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) && TRANSITION_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraIssuePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        return ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
    }

    private fun looksLikeJiraDueSoonPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && DUE_SOON_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraBlockerPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && BLOCKER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraDailyBriefingPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && DAILY_BRIEFING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraSearchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeJiraPrompt(prompt) && SEARCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketRepositoryPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REPOSITORY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketBranchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && BRANCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketStalePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && STALE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketReviewQueuePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REVIEW_QUEUE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketReviewSlaPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeBitbucketPrompt(prompt) && REVIEW_SLA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerListPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && LIST_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerValidatePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && VALIDATE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerSchemaPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && SCHEMA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerDetailPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && DETAIL_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerSearchPrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && SEARCH_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerRemovePrompt(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return false
        val normalized = prompt.lowercase()
        return looksLikeSwaggerPrompt(prompt) && REMOVE_HINTS.any { normalized.contains(it) }
    }

    companion object {
        private val CONFLUENCE_KNOWLEDGE_HINTS = setOf(
            "confluence", "wiki", "page", "document", "policy", "policies", "guideline", "guidelines",
            "runbook", "knowledge", "internal", "service", "space", "컨플루언스", "위키", "페이지",
            "문서", "정책", "규정", "가이드", "런북", "사내", "서비스", "스페이스"
        )
        private val CONFLUENCE_ANSWER_HINTS = setOf(
            "what", "who", "why", "how", "describe", "explain", "summary", "summarize", "tell me",
            "알려", "설명", "요약", "정리", "무엇", "왜", "어떻게", "누구"
        )
        private val WORK_BRIEFING_HINTS = setOf(
            "morning briefing", "daily briefing", "briefing", "work summary", "daily digest",
            "브리핑", "요약 브리핑", "아침 브리핑", "데일리 브리핑"
        )
        private val WORK_OWNER_HINTS = setOf(
            "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당", "담당 서비스"
        )
        private val WORK_ITEM_CONTEXT_HINTS = setOf(
            "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr", "오픈 pr", "다음 액션", "next action"
        )
        private val WORK_SERVICE_CONTEXT_HINTS = setOf(
            "서비스 상황", "서비스 현황", "service context", "service summary", "현재 상황", "현재 현황",
            "최근 jira", "최근 jira 이슈", "열린 pr", "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
        )
        private val ISSUE_KEY_REGEX = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b")
        private val JIRA_HINTS = setOf(
            "jira", "이슈", "프로젝트", "jql", "ticket", "티켓", "blocker", "마감", "due", "transition", "전이"
        )
        private val BITBUCKET_HINTS = setOf(
            "bitbucket", "repository", "repo", "pull request", "pr", "branch", "브랜치", "저장소", "리뷰", "sla"
        )
        private val SWAGGER_HINTS = setOf(
            "swagger", "openapi", "spec", "schema", "endpoint", "api spec", "스펙", "엔드포인트", "스키마"
        )
        private val OPENAPI_URL_REGEX = Regex("https?://\\S+(?:openapi|swagger)\\S*", RegexOption.IGNORE_CASE)
        private val PROJECT_LIST_HINTS = setOf("project list", "projects", "프로젝트 목록", "프로젝트 리스트")
        private val DUE_SOON_HINTS = setOf("due soon", "마감", "임박", "due")
        private val BLOCKER_HINTS = setOf("blocker", "차단", "막힌")
        private val DAILY_BRIEFING_HINTS = setOf("daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest")
        private val SEARCH_HINTS = setOf("search", "찾아", "검색", "look up", "find")
        private val TRANSITION_HINTS = setOf("transition", "상태 전이", "전이", "possible states")
        private val REPOSITORY_HINTS = setOf("repository", "repo", "저장소")
        private val BRANCH_HINTS = setOf("branch", "브랜치")
        private val STALE_HINTS = setOf("stale", "오래된", "방치된")
        private val REVIEW_QUEUE_HINTS = setOf("queue", "대기열")
        private val REVIEW_SLA_HINTS = setOf("sla", "응답 지연", "리뷰 sla")
        private val VALIDATE_HINTS = setOf("validate", "검증", "유효성")
        private val SCHEMA_HINTS = setOf("schema", "스키마", "model", "dto")
        private val DETAIL_HINTS = setOf("detail", "상세", "parameter", "response", "security")
        private val LIST_HINTS = setOf("loaded specs", "list specs", "목록", "list")
        private val REMOVE_HINTS = setOf("remove", "삭제")
    }
}
