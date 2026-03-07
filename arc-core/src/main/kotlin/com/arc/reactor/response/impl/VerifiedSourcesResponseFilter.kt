package com.arc.reactor.response.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.tool.WorkspaceMutationIntentDetector

class VerifiedSourcesResponseFilter : ResponseFilter {
    override val order: Int = 90

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        if (context.command.responseFormat != ResponseFormat.TEXT) return content

        val normalizedContent = stripSourcesBlock(content)
        val sources = context.verifiedSources.distinctBy { it.url }.take(MAX_SOURCES)
        if (shouldBlockUnverifiedAnswer(context, sources, normalizedContent)) {
            return buildUnverifiedResponse(context.command.userPrompt, sources)
        }
        return normalizedContent.trimEnd() + "\n\n" + buildSourcesBlock(context.command.userPrompt, sources)
    }

    private fun shouldBlockUnverifiedAnswer(
        context: ResponseFilterContext,
        sources: List<VerifiedSource>,
        content: String
    ): Boolean {
        if (sources.isNotEmpty()) return false
        if (!requiresVerifiedSources(context)) return false
        if (allowsReadOnlyMutationRefusal(context, content)) return false
        return !alreadyDeclinesVerification(content)
    }

    private fun allowsReadOnlyMutationRefusal(context: ResponseFilterContext, content: String): Boolean {
        if (!WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(context.command.userPrompt)) return false
        return READ_ONLY_MUTATION_PATTERNS.any { pattern -> content.contains(pattern, ignoreCase = true) }
    }

    private fun requiresVerifiedSources(context: ResponseFilterContext): Boolean {
        if (isCasualPrompt(context.command.userPrompt)) return false
        if (context.toolsUsed.any(::isWorkspaceTool)) return true
        return looksLikeInformationRequest(context.command.userPrompt)
    }

    private fun isWorkspaceTool(toolName: String): Boolean {
        return WORKSPACE_TOOL_PREFIXES.any { prefix -> toolName.startsWith(prefix) }
    }

    private fun alreadyDeclinesVerification(content: String): Boolean {
        return UNVERIFIED_PATTERNS.any { pattern -> content.contains(pattern, ignoreCase = true) }
    }

    private fun buildUnverifiedResponse(userPrompt: String, sources: List<VerifiedSource>): String {
        val message = if (containsHangul(userPrompt)) {
            "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. 승인된 Jira, Confluence, Bitbucket, Swagger/OpenAPI 자료를 다시 조회해 주세요."
        } else {
            "I couldn't verify this answer from approved sources. Please re-run the query against approved Jira, Confluence, Bitbucket, or Swagger/OpenAPI data."
        }
        return "$message\n\n${buildSourcesBlock(userPrompt, sources)}"
    }

    private fun buildSourcesBlock(userPrompt: String, sources: List<VerifiedSource>): String {
        val heading = if (containsHangul(userPrompt)) "출처" else "Sources"
        if (sources.isEmpty()) {
            val emptyLine = if (containsHangul(userPrompt)) {
                "- 검증된 출처를 찾지 못했습니다."
            } else {
                "- No verified sources available."
            }
            return "$heading\n$emptyLine"
        }
        val lines = sources.map { source ->
            "- [${escapeTitle(source.title)}](${source.url})"
        }
        return "$heading\n${lines.joinToString("\n")}"
    }

    private fun stripSourcesBlock(content: String): String {
        val trimmed = content.trimEnd()
        val indexes = listOf("\n\nSources\n", "\n\n출처\n")
            .map { marker -> trimmed.lastIndexOf(marker) }
            .filter { it >= 0 }
        if (indexes.isEmpty()) return trimmed
        return trimmed.substring(0, indexes.maxOrNull() ?: 0).trimEnd()
    }

    private fun escapeTitle(title: String): String {
        return title.replace("[", "\\[").replace("]", "\\]")
    }

    private fun containsHangul(text: String): Boolean {
        return text.any { ch -> ch in '\uAC00'..'\uD7A3' }
    }

    private fun looksLikeInformationRequest(userPrompt: String): Boolean {
        if (VERIFICATION_KEYWORDS.any { keyword -> userPrompt.contains(keyword, ignoreCase = true) }) return true
        if (userPrompt.contains('?')) return true
        return INFORMATION_REQUEST_PATTERNS.any { pattern ->
            userPrompt.contains(pattern, ignoreCase = true)
        }
    }

    private fun isCasualPrompt(userPrompt: String): Boolean {
        val normalized = userPrompt.trim().lowercase()
        if (normalized.isBlank()) return true
        return CASUAL_PROMPTS.any { casual -> normalized == casual } ||
            CASUAL_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    }

    companion object {
        private val WORKSPACE_TOOL_PREFIXES =
            listOf("jira_", "confluence_", "bitbucket_", "work_", "mcp_", "spec_")
        private val VERIFICATION_KEYWORDS = setOf(
            "jira", "confluence", "bitbucket", "slack", "policy", "policies", "runbook", "incident",
            "release", "owner", "status", "guideline", "documentation", "swagger", "openapi",
            "endpoint", "schema", "api", "지라", "컨플루언스", "비트버킷", "스웨거", "오픈api",
            "엔드포인트", "스키마", "정책", "규정", "문서", "사내", "서비스", "규칙"
        )
        private val INFORMATION_REQUEST_PATTERNS = setOf(
            "who", "what", "when", "where", "why", "how", "tell me", "explain", "summarize", "summary",
            "list", "show", "find", "search", "lookup", "알려", "설명", "요약", "정리", "보여", "찾아",
            "조회", "무엇", "왜", "어떻게", "누구", "언제", "어디", "몇"
        )
        private val CASUAL_PROMPTS = setOf(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "안녕", "고마워", "감사해",
            "감사합니다", "오케이", "ㅇㅋ", "ㅎㅇ"
        )
        private val CASUAL_PREFIXES = setOf("thanks ", "thank you ", "고마워 ", "감사해 ", "감사합니다 ")
        private val UNVERIFIED_PATTERNS = listOf(
            "cannot verify",
            "couldn't verify",
            "insufficient",
            "검증 가능한 출처를 찾지 못",
            "확인 가능한 출처를 찾지 못",
            "근거를 찾지 못"
        )
        private val READ_ONLY_MUTATION_PATTERNS = listOf(
            "read-only",
            "readonly",
            "읽기 전용",
            "지원하지 않습니다",
            "수행할 수 없습니다",
            "업데이트할 수 없습니다",
            "재할당은 불가능",
            "변경 작업을 수행할 수 없습니다"
        )
        private const val MAX_SOURCES = 8
    }
}
