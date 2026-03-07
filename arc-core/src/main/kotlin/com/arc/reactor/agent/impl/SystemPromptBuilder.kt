package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.guard.canary.SystemPromptPostProcessor

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
        append("For Jira, Confluence, Bitbucket, policy, documentation, or internal knowledge requests, ")
        append("call the relevant workspace tool before answering.\n")
        append("Prefer `confluence_answer_question` for Confluence policy, wiki, service, or page-summary questions.")
        append("\nDo not answer Confluence knowledge questions from `confluence_search` or `confluence_search_by_text` alone; ")
        append("use them only for discovery, then verify with `confluence_answer_question` or `confluence_get_page_content`.")
        if (looksLikeConfluenceAnswerPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `confluence_answer_question` before answering.")
            append(" Do not reply directly from general knowledge or prior context.")
        }
        if (looksLikeWorkBriefingPrompt(userPrompt)) {
            append("\nFor this request, you MUST call `work_morning_briefing` before answering.")
            append(" Do not assemble the briefing manually.")
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
    }
}
