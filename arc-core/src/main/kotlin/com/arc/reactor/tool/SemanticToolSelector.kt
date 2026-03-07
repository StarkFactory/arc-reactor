package com.arc.reactor.tool

import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Semantic Tool Selector
 *
 * Uses embedding-based cosine similarity to select relevant tools.
 * Tool descriptions are embedded and cached; the user prompt is embedded per request.
 *
 * ## How It Works
 * 1. On first call (or when tools change), embeds each tool's `name + description`
 * 2. Embeds the user prompt
 * 3. Computes cosine similarity between prompt and each tool
 * 4. Returns tools above the similarity threshold (sorted by relevance)
 * 5. Falls back to all tools if none meet the threshold
 *
 * ## Configuration
 * ```yaml
 * arc:
 *   reactor:
 *     tool-selection:
 *       strategy: semantic
 *       similarity-threshold: 0.3
 *       max-results: 10
 * ```
 *
 * ## Example
 * ```kotlin
 * val selector = SemanticToolSelector(embeddingModel)
 * val tools = selector.select("refund my order", allTools)
 * // Returns: [processRefund, checkOrder] (most relevant first)
 * ```
 *
 * @param embeddingModel Spring AI embedding model for vectorization
 * @param similarityThreshold Minimum cosine similarity to include a tool (0.0 to 1.0)
 * @param maxResults Maximum number of tools to return
 */
class SemanticToolSelector(
    private val embeddingModel: EmbeddingModel,
    private val similarityThreshold: Double = 0.3,
    private val maxResults: Int = 10
) : ToolSelector {

    /** Cache: tool name → embedding vector. Invalidated when tool list changes. */
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()

    /** Fingerprint of the last tool list (to detect changes). */
    @Volatile
    private var lastToolFingerprint: Int = 0

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (availableTools.isEmpty()) return emptyList()
        if (availableTools.size <= maxResults) {
            return applyDeterministicRouting(prompt, availableTools, availableTools)
        }

        return try {
            selectSemantically(prompt, availableTools)
        } catch (e: Exception) {
            logger.warn(e) { "Semantic tool selection failed, falling back to all tools" }
            applyDeterministicRouting(prompt, availableTools, availableTools)
        }
    }

    private fun selectSemantically(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        // 1. Refresh cached embeddings if tool list changed
        refreshEmbeddingsIfNeeded(availableTools)

        // 2. Embed the user prompt
        val promptEmbedding = embed(prompt)

        // 3. Score each tool by cosine similarity
        val scored = availableTools.map { tool ->
            val toolEmbedding = embeddingCache[tool.name]
                ?: embed(buildToolText(tool)).also { embeddingCache[tool.name] = it }
            val similarity = cosineSimilarity(promptEmbedding, toolEmbedding)
            tool to similarity
        }

        // 4. Filter by threshold, sort by relevance, limit results
        val selected = scored
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }

        // 5. Fallback: if nothing meets threshold, return all
        val baseSelection = if (selected.isEmpty()) {
            logger.debug { "No tools above threshold $similarityThreshold, returning all ${availableTools.size} tools" }
            availableTools
        } else {
            selected
        }

        logger.debug {
            val names = scored.sortedByDescending { it.second }.take(5)
                .joinToString { "${it.first.name}(${String.format("%.3f", it.second)})" }
            "Semantic tool selection: top scores = [$names], selected ${baseSelection.size}/${availableTools.size}"
        }

        return applyDeterministicRouting(prompt, baseSelection, availableTools)
    }

    private fun applyDeterministicRouting(
        prompt: String,
        selected: List<ToolCallback>,
        availableTools: List<ToolCallback>
    ): List<ToolCallback> {
        val availableByName = availableTools.associateBy { it.name }
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(prompt)) {
            return preferredReadOnlyMutationEvidenceTools(prompt, availableByName)
        }

        if (looksLikeWorkItemContextPrompt(prompt)) {
            val preferred = PREFERRED_WORK_ITEM_CONTEXT_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkServiceContextPrompt(prompt)) {
            val preferred = PREFERRED_WORK_SERVICE_CONTEXT_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkOwnerPrompt(prompt)) {
            val preferred = PREFERRED_WORK_OWNER_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkBriefingPrompt(prompt)) {
            val preferred = PREFERRED_WORK_BRIEFING_TOOLS
                .mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) {
                return preferred
            }
        }

        if (looksLikeJiraPrompt(prompt)) {
            val preferred = preferredJiraTools(prompt, availableByName)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeBitbucketPrompt(prompt)) {
            val preferred = preferredBitbucketTools(prompt, availableByName)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeSwaggerPrompt(prompt)) {
            val preferred = preferredSwaggerTools(prompt, availableByName)
            if (preferred.isNotEmpty()) return preferred
        }

        if (!looksLikeConfluenceKnowledgePrompt(prompt)) return selected

        val preferred = PREFERRED_CONFLUENCE_KNOWLEDGE_TOOLS
            .mapNotNull(availableByName::get)
        if (preferred.isEmpty()) return selected

        if (looksLikeConfluenceAnswerPrompt(prompt)) {
            return preferred.take(maxResults)
        }

        val filtered = selected.filterNot { it.name in LOW_LEVEL_CONFLUENCE_DISCOVERY_TOOLS }
        val ordered = LinkedHashMap<String, ToolCallback>()
        preferred.forEach { ordered[it.name] = it }
        filtered.forEach { ordered.putIfAbsent(it.name, it) }
        return ordered.values.take(maxResults)
    }

    private fun looksLikeConfluenceKnowledgePrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return CONFLUENCE_KNOWLEDGE_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeConfluenceAnswerPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return CONFLUENCE_ANSWER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkOwnerPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_OWNER_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkItemContextPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        val hasIssueKey = ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())
        return hasIssueKey && WORK_ITEM_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkServiceContextPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        val hasServiceMention = normalized.contains("service") || normalized.contains("서비스")
        return hasServiceMention && WORK_SERVICE_CONTEXT_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkBriefingPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_BRIEFING_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeJiraPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return JIRA_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeBitbucketPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return BITBUCKET_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeSwaggerPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return OPENAPI_URL_REGEX.containsMatchIn(prompt) || SWAGGER_HINTS.any { normalized.contains(it) }
    }

    private fun preferredJiraTools(
        prompt: String,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val normalized = prompt.lowercase()
        val orderedNames = when {
            ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) && TRANSITION_HINTS.any { normalized.contains(it) } ->
                listOf("jira_get_transitions", "jira_get_issue")

            ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase()) ->
                listOf("jira_get_issue", "jira_search_issues")

            DUE_SOON_HINTS.any { normalized.contains(it) } ->
                listOf("jira_due_soon_issues")

            BLOCKER_HINTS.any { normalized.contains(it) } ->
                listOf("jira_blocker_digest", "jira_search_issues")

            DAILY_BRIEFING_HINTS.any { normalized.contains(it) } ->
                listOf("jira_daily_briefing", "jira_search_issues")

            PROJECT_LIST_HINTS.any { normalized.contains(it) } ->
                listOf("jira_list_projects")

            MY_WORK_HINTS.any { normalized.contains(it) } ->
                listOf("jira_my_open_issues", "jira_search_issues")

            SEARCH_HINTS.any { normalized.contains(it) } ->
                listOf("jira_search_by_text", "jira_search_issues")

            else -> listOf("jira_search_issues", "jira_search_by_text", "jira_list_projects")
        }
        return orderedNames.mapNotNull(availableByName::get).take(maxResults)
    }

    private fun preferredReadOnlyMutationEvidenceTools(
        prompt: String,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val normalized = prompt.lowercase()
        if (ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())) {
            val ordered = if (normalized.contains("담당자") || normalized.contains("재할당")) {
                listOf("work_owner_lookup", "jira_get_issue")
            } else {
                listOf("jira_get_issue", "work_owner_lookup")
            }
            return ordered.mapNotNull(availableByName::get).take(1)
        }

        if ((normalized.contains("confluence") || normalized.contains("페이지") || normalized.contains("page")) &&
            availableByName.containsKey("confluence_get_page")
        ) {
            return listOfNotNull(availableByName["confluence_get_page"])
        }

        if ((normalized.contains("pull request") || normalized.contains("pr")) && availableByName.containsKey("bitbucket_get_pr")) {
            return listOfNotNull(availableByName["bitbucket_get_pr"])
        }

        return emptyList()
    }

    private fun preferredBitbucketTools(
        prompt: String,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val normalized = prompt.lowercase()
        val orderedNames = when {
            PR_HINTS.any { normalized.contains(it) } && REVIEW_SLA_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_review_sla_alerts", "bitbucket_list_prs")

            PR_HINTS.any { normalized.contains(it) } && REVIEW_QUEUE_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_review_queue", "bitbucket_list_prs")

            STALE_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_stale_prs", "bitbucket_list_prs")

            BRANCH_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_list_branches")

            PR_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_list_prs", "bitbucket_get_pr")

            REPOSITORY_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_list_repositories")

            else -> listOf("bitbucket_list_repositories", "bitbucket_list_prs")
        }
        return orderedNames.mapNotNull(availableByName::get).take(maxResults)
    }

    private fun preferredSwaggerTools(
        prompt: String,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val normalized = prompt.lowercase()
        val orderedNames = when {
            REMOVE_HINTS.any { normalized.contains(it) } ->
                listOf("spec_remove", "spec_list")

            VALIDATE_HINTS.any { normalized.contains(it) } ->
                listOf("spec_validate", "spec_load")

            SCHEMA_HINTS.any { normalized.contains(it) } ->
                listOf("spec_load", "spec_schema")

            DETAIL_HINTS.any { normalized.contains(it) } ->
                listOf("spec_load", "spec_detail")

            SEARCH_HINTS.any { normalized.contains(it) } ->
                listOf("spec_load", "spec_search")

            LIST_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list")

            else -> listOf("spec_load", "spec_summary", "spec_list")
        }
        return orderedNames.mapNotNull(availableByName::get).take(maxResults)
    }

    private fun refreshEmbeddingsIfNeeded(tools: List<ToolCallback>) {
        val fingerprint = tools.map { it.name }.sorted().hashCode()
        if (fingerprint != lastToolFingerprint) {
            embeddingCache.clear()
            // Batch-embed all tool descriptions at once
            val texts = tools.map { buildToolText(it) }
            val embeddings = embedBatch(texts)
            tools.forEachIndexed { index, tool ->
                embeddingCache[tool.name] = embeddings[index]
            }
            lastToolFingerprint = fingerprint
            logger.info { "Refreshed semantic embeddings for ${tools.size} tools" }
        }
    }

    private fun buildToolText(tool: ToolCallback): String {
        return "${tool.name}: ${tool.description}"
    }

    private fun embed(text: String): FloatArray {
        val response = embeddingModel.embed(text)
        return response
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = embeddingModel.embed(texts)
        return response
    }

    companion object {
        private val PREFERRED_CONFLUENCE_KNOWLEDGE_TOOLS = listOf(
            "confluence_answer_question",
            "confluence_get_page_content",
            "confluence_get_page"
        )
        private val PREFERRED_WORK_OWNER_TOOLS = listOf(
            "work_owner_lookup"
        )
        private val PREFERRED_WORK_ITEM_CONTEXT_TOOLS = listOf(
            "work_item_context"
        )
        private val PREFERRED_WORK_SERVICE_CONTEXT_TOOLS = listOf(
            "work_service_context"
        )
        private val PREFERRED_WORK_BRIEFING_TOOLS = listOf(
            "work_morning_briefing"
        )
        private val LOW_LEVEL_CONFLUENCE_DISCOVERY_TOOLS = setOf(
            "confluence_search",
            "confluence_search_by_text"
        )
        private val CONFLUENCE_KNOWLEDGE_HINTS = setOf(
            "confluence", "wiki", "page", "document", "policy", "policies", "guideline", "guidelines",
            "runbook", "knowledge", "internal", "service", "컨플루언스", "위키", "페이지", "문서",
            "정책", "규정", "가이드", "런북", "사내", "서비스"
        )
        private val CONFLUENCE_ANSWER_HINTS = setOf(
            "what", "who", "why", "how", "describe", "explain", "summary", "summarize", "tell me",
            "알려", "설명", "요약", "정리", "무엇", "왜", "어떻게", "누구"
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
        private val WORK_BRIEFING_HINTS = setOf(
            "morning briefing", "daily briefing", "briefing", "work summary", "daily digest",
            "브리핑", "요약 브리핑", "아침 브리핑", "데일리 브리핑"
        )
        private val JIRA_HINTS = setOf(
            "jira", "이슈", "프로젝트", "jql", "ticket", "티켓", "blocker", "마감", "due", "transition", "전이"
        )
        private val BITBUCKET_HINTS = setOf(
            "bitbucket", "repository", "repo", "pull request", "pr", "branch", "브랜치", "저장소", "리뷰", "sla"
        )
        private val SWAGGER_HINTS = setOf(
            "swagger", "openapi", "spec", "schema", "endpoint", "api spec", "스펙", "엔드포인트", "스키마"
        )
        private val PROJECT_LIST_HINTS = setOf("project list", "projects", "프로젝트 목록", "프로젝트 리스트")
        private val DUE_SOON_HINTS = setOf("due soon", "마감", "임박", "due")
        private val BLOCKER_HINTS = setOf("blocker", "차단", "막힌")
        private val DAILY_BRIEFING_HINTS = setOf("daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest")
        private val MY_WORK_HINTS = setOf("my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈")
        private val SEARCH_HINTS = setOf("search", "찾아", "검색", "look up", "find")
        private val TRANSITION_HINTS = setOf("transition", "상태 전이", "전이", "possible states")
        private val PR_HINTS = setOf("pull request", "pr", "리뷰")
        private val REVIEW_SLA_HINTS = setOf("sla", "응답 지연", "리뷰 sla")
        private val REVIEW_QUEUE_HINTS = setOf("queue", "대기열")
        private val STALE_HINTS = setOf("stale", "오래된", "방치된")
        private val BRANCH_HINTS = setOf("branch", "브랜치")
        private val REPOSITORY_HINTS = setOf("repository", "repo", "저장소")
        private val VALIDATE_HINTS = setOf("validate", "검증", "유효성")
        private val SCHEMA_HINTS = setOf("schema", "스키마", "model", "dto")
        private val DETAIL_HINTS = setOf("detail", "상세", "parameter", "response", "security")
        private val LIST_HINTS = setOf("loaded specs", "list specs", "목록", "list")
        private val REMOVE_HINTS = setOf("remove", "삭제")
        private val OPENAPI_URL_REGEX = Regex("https?://\\S+(?:openapi|swagger)\\S*", RegexOption.IGNORE_CASE)

        /**
         * Compute cosine similarity between two vectors.
         * Returns a value between -1.0 and 1.0 (1.0 = identical direction).
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "Vector dimensions must match: ${a.size} vs ${b.size}" }
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = Math.sqrt(normA) * Math.sqrt(normB)
            return if (denominator == 0.0) 0.0 else dotProduct / denominator
        }
    }
}
