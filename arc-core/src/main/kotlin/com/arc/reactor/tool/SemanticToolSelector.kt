package com.arc.reactor.tool

import com.arc.reactor.support.WorkContextPatterns
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    private val maxResults: Int = 10,
    selectionCacheMaxSize: Long = 512,
    selectionCacheTtlMinutes: Long = 10
) : ToolSelector {

    /** Cache: tool name → embedding vector. Invalidated when tool list changes. */
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()
    private val refreshLock = Any()
    private val semanticSelectionCache: Cache<SemanticSelectionCacheKey, List<String>> = Caffeine.newBuilder()
        .maximumSize(selectionCacheMaxSize)
        .expireAfterWrite(selectionCacheTtlMinutes, TimeUnit.MINUTES)
        .build()

    /** Fingerprint of the last tool list (to detect changes). */
    @Volatile
    private var lastToolFingerprint: Int = 0

    override fun select(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        if (availableTools.isEmpty()) return emptyList()
        selectDeterministicallyIfPossible(prompt, availableTools)?.let { fastPath ->
            logger.debug { "Deterministic tool selection fast-path selected ${fastPath.size}/${availableTools.size} tools" }
            return fastPath
        }
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

    fun prewarm(availableTools: List<ToolCallback>) {
        if (availableTools.isEmpty()) return
        refreshEmbeddingsIfNeeded(availableTools)
    }

    private fun selectSemantically(prompt: String, availableTools: List<ToolCallback>): List<ToolCallback> {
        // 1. Refresh cached embeddings if tool list changed
        val fingerprint = toolFingerprint(availableTools)
        refreshEmbeddingsIfNeeded(availableTools, fingerprint)

        val cacheKey = SemanticSelectionCacheKey(
            prompt = prompt,
            toolFingerprint = fingerprint,
            similarityThreshold = similarityThreshold,
            maxResults = maxResults
        )
        resolveCachedSelection(cacheKey, availableTools)?.let { return it }

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

        val resolvedSelection = applyDeterministicRouting(prompt, baseSelection, availableTools)
        semanticSelectionCache.put(cacheKey, resolvedSelection.map(ToolCallback::name))
        return resolvedSelection
    }

    private fun resolveCachedSelection(
        cacheKey: SemanticSelectionCacheKey,
        availableTools: List<ToolCallback>
    ): List<ToolCallback>? {
        val cachedToolNames = semanticSelectionCache.getIfPresent(cacheKey) ?: return null
        val availableByName = availableTools.associateBy { it.name }
        val resolved = cachedToolNames.mapNotNull(availableByName::get)
        if (resolved.size != cachedToolNames.size) {
            semanticSelectionCache.invalidate(cacheKey)
            logger.debug { "Invalidated stale semantic selection cache entry due to tool mismatch" }
            return null
        }
        logger.debug {
            "Semantic tool selection exact cache hit selected ${resolved.size}/${availableTools.size} tools"
        }
        return resolved
    }

    private fun selectDeterministicallyIfPossible(
        prompt: String,
        availableTools: List<ToolCallback>
    ): List<ToolCallback>? {
        val availableByName = availableTools.associateBy { it.name }

        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(prompt)) {
            return preferredReadOnlyMutationEvidenceTools(prompt, availableByName)
        }

        if (looksLikeWorkItemContextPrompt(prompt)) {
            return PREFERRED_WORK_ITEM_CONTEXT_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkServiceContextPrompt(prompt)) {
            return PREFERRED_WORK_SERVICE_CONTEXT_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkReleaseReadinessPrompt(prompt)) {
            return PREFERRED_WORK_RELEASE_READINESS_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkReleaseRiskPrompt(prompt) || looksLikeHybridPriorityPrompt(prompt)) {
            return PREFERRED_WORK_RELEASE_RISK_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkStandupPrompt(prompt)) {
            return PREFERRED_WORK_STANDUP_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkBriefingProfilePrompt(prompt)) {
            return PREFERRED_WORK_BRIEFING_PROFILE_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkOwnerPrompt(prompt)) {
            return PREFERRED_WORK_OWNER_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeWorkBriefingPrompt(prompt)) {
            return PREFERRED_WORK_BRIEFING_TOOLS.mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeJiraPrompt(prompt)) {
            return preferredJiraTools(prompt, availableByName).takeIf { it.isNotEmpty() }
        }

        if (looksLikeBitbucketPrompt(prompt)) {
            return preferredBitbucketTools(prompt, availableByName).takeIf { it.isNotEmpty() }
        }

        if (looksLikeSwaggerPrompt(prompt)) {
            return preferredSwaggerTools(prompt, availableByName).takeIf { it.isNotEmpty() }
        }

        if (!looksLikeConfluenceKnowledgePrompt(prompt)) return null

        if (looksLikeConfluenceDiscoveryPrompt(prompt)) {
            return listOf("confluence_search_by_text", "confluence_search")
                .mapNotNull(availableByName::get)
                .takeIf { it.isNotEmpty() }
        }

        if (looksLikeConfluencePageBodyPrompt(prompt)) {
            return listOf("confluence_get_page_content", "confluence_answer_question", "confluence_search_by_text")
                .mapNotNull(availableByName::get)
                .take(maxResults)
                .takeIf { it.isNotEmpty() }
        }

        val preferredKnowledgeTools = PREFERRED_CONFLUENCE_KNOWLEDGE_TOOLS
            .mapNotNull(availableByName::get)
        if (preferredKnowledgeTools.isEmpty()) return null

        if (looksLikeConfluenceAnswerPrompt(prompt)) {
            return preferredKnowledgeTools.take(maxResults)
        }

        return null
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

        if (looksLikeWorkReleaseReadinessPrompt(prompt)) {
            val preferred = PREFERRED_WORK_RELEASE_READINESS_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkReleaseRiskPrompt(prompt)) {
            val preferred = PREFERRED_WORK_RELEASE_RISK_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeHybridPriorityPrompt(prompt)) {
            val preferred = PREFERRED_WORK_RELEASE_RISK_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkStandupPrompt(prompt)) {
            val preferred = PREFERRED_WORK_STANDUP_TOOLS.mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeWorkBriefingProfilePrompt(prompt)) {
            val preferred = PREFERRED_WORK_BRIEFING_PROFILE_TOOLS.mapNotNull(availableByName::get)
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

        if (looksLikeConfluenceDiscoveryPrompt(prompt)) {
            val preferred = listOf("confluence_search_by_text", "confluence_search")
                .mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred
        }

        if (looksLikeConfluencePageBodyPrompt(prompt)) {
            val preferred = listOf("confluence_get_page_content", "confluence_answer_question", "confluence_search_by_text")
                .mapNotNull(availableByName::get)
            if (preferred.isNotEmpty()) return preferred.take(maxResults)
        }

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
        return CONFLUENCE_ANSWER_HINTS.any { normalized.contains(it) } && !looksLikeConfluenceDiscoveryPrompt(prompt)
    }

    private fun looksLikeConfluenceDiscoveryPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return CONFLUENCE_DISCOVERY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeConfluencePageBodyPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return CONFLUENCE_PAGE_BODY_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkOwnerPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return !MISSING_ASSIGNEE_HINTS.any { normalized.contains(it) } &&
            WORK_OWNER_HINTS.any { normalized.contains(it) }
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

    private fun looksLikeWorkStandupPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_STANDUP_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkReleaseRiskPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_RELEASE_RISK_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeHybridPriorityPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        val hasPriorityHint = HYBRID_PRIORITY_HINTS.any { normalized.contains(it) }
        return hasPriorityHint &&
            BLOCKER_HINTS.any { normalized.contains(it) } &&
            (REVIEW_QUEUE_HINTS.any { normalized.contains(it) } || REVIEW_SLA_HINTS.any { normalized.contains(it) })
    }

    private fun looksLikeWorkReleaseReadinessPrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_RELEASE_READINESS_HINTS.any { normalized.contains(it) }
    }

    private fun looksLikeWorkBriefingProfilePrompt(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        return WORK_BRIEFING_PROFILE_HINTS.any { normalized.contains(it) }
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

            JIRA_PROJECT_SUMMARY_HINTS.any { normalized.contains(it) } ->
                listOf("jira_search_issues", "jira_search_by_text")

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
            REVIEW_RISK_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_review_sla_alerts", "bitbucket_review_queue", "bitbucket_list_prs")

            MY_REVIEW_HINTS.any { normalized.contains(it) } ->
                listOf("bitbucket_review_queue", "bitbucket_list_prs")

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
        val hasSwaggerUrl = OPENAPI_URL_REGEX.containsMatchIn(prompt)
        val orderedNames = when {
            LOADED_HINTS.any { normalized.contains(it) } && SUMMARY_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list", "spec_summary")

            LOADED_HINTS.any { normalized.contains(it) } && SCHEMA_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list", "spec_schema")

            LOADED_HINTS.any { normalized.contains(it) } && DETAIL_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list", "spec_detail")

            LOADED_HINTS.any { normalized.contains(it) } && SEARCH_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list", "spec_search")

            WRONG_ENDPOINT_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list", "spec_search")

            REMOVE_HINTS.any { normalized.contains(it) } ->
                listOf("spec_remove", "spec_list")

            VALIDATE_HINTS.any { normalized.contains(it) } ->
                listOf("spec_validate", "spec_load")

            SCHEMA_HINTS.any { normalized.contains(it) } ->
                if (hasSwaggerUrl) listOf("spec_load", "spec_schema") else listOf("spec_list", "spec_schema")

            DETAIL_HINTS.any { normalized.contains(it) } ->
                if (hasSwaggerUrl) listOf("spec_load", "spec_detail") else listOf("spec_list", "spec_detail")

            SEARCH_HINTS.any { normalized.contains(it) } ->
                if (hasSwaggerUrl) listOf("spec_load", "spec_search") else listOf("spec_list", "spec_search")

            SUMMARY_HINTS.any { normalized.contains(it) } ->
                if (hasSwaggerUrl) listOf("spec_load", "spec_summary") else listOf("spec_list", "spec_summary")

            LIST_HINTS.any { normalized.contains(it) } ->
                listOf("spec_list")

            else -> if (hasSwaggerUrl) {
                listOf("spec_load", "spec_summary", "spec_list")
            } else {
                listOf("spec_list", "spec_summary", "spec_search")
            }
        }
        return orderedNames.mapNotNull(availableByName::get).take(maxResults)
    }

    private fun refreshEmbeddingsIfNeeded(
        tools: List<ToolCallback>,
        fingerprint: Int = toolFingerprint(tools)
    ) {
        if (fingerprint == lastToolFingerprint) return
        synchronized(refreshLock) {
            if (fingerprint == lastToolFingerprint) return
            val refreshedEmbeddings = embedBatch(tools.map(::buildToolText))
                .mapIndexed { index, embedding -> tools[index].name to embedding }
                .toMap(LinkedHashMap())
            embeddingCache.clear()
            embeddingCache.putAll(refreshedEmbeddings)
            semanticSelectionCache.invalidateAll()
            lastToolFingerprint = fingerprint
            logger.info { "Refreshed semantic embeddings for ${tools.size} tools" }
        }
    }

    private fun buildToolText(tool: ToolCallback): String {
        return "${tool.name}: ${tool.description}"
    }

    private fun toolFingerprint(tools: List<ToolCallback>): Int {
        return tools.map(::buildToolText).sorted().hashCode()
    }

    private fun embed(text: String): FloatArray {
        val response = embeddingModel.embed(text)
        return response
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = embeddingModel.embed(texts)
        return response
    }

    private data class SemanticSelectionCacheKey(
        val prompt: String,
        val toolFingerprint: Int,
        val similarityThreshold: Double,
        val maxResults: Int
    )

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
        private val PREFERRED_WORK_RELEASE_RISK_TOOLS = listOf(
            "work_release_risk_digest"
        )
        private val PREFERRED_WORK_RELEASE_READINESS_TOOLS = listOf(
            "work_release_readiness_pack"
        )
        private val PREFERRED_WORK_STANDUP_TOOLS = listOf(
            "work_prepare_standup_update"
        )
        private val PREFERRED_WORK_BRIEFING_PROFILE_TOOLS = listOf(
            "work_list_briefing_profiles"
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
            "알려", "설명", "요약", "정리", "무엇", "왜", "어떻게", "누구", "본문", "body", "read", "읽"
        )
        private val CONFLUENCE_DISCOVERY_HINTS = setOf(
            "search", "find", "look up", "keyword", "list", "찾아", "검색", "키워드", "목록", "어떤 문서"
        )
        private val CONFLUENCE_PAGE_BODY_HINTS = setOf(
            "본문", "body", "content", "read", "읽고", "읽어", "내용", "핵심만"
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
        private val ISSUE_KEY_REGEX = WorkContextPatterns.ISSUE_KEY_REGEX
        private val WORK_BRIEFING_HINTS = setOf(
            "morning briefing", "daily briefing", "briefing", "work summary", "daily digest",
            "브리핑", "요약 브리핑", "아침 브리핑", "데일리 브리핑"
        )
        private val WORK_STANDUP_HINTS = setOf(
            "standup", "스탠드업", "daily update", "업데이트 초안", "standup update"
        )
        private val WORK_RELEASE_RISK_HINTS = setOf(
            "release risk", "risk digest", "릴리즈 위험", "출시 위험", "release digest"
        )
        private val HYBRID_PRIORITY_HINTS = setOf(
            "priority", "priorities", "우선순위", "오늘 우선", "today priority"
        )
        private val WORK_RELEASE_READINESS_HINTS = setOf(
            "release readiness", "readiness pack", "릴리즈 준비", "출시 준비", "readiness"
        )
        private val WORK_BRIEFING_PROFILE_HINTS = setOf(
            "briefing profile", "profile list", "profiles", "브리핑 프로필", "프로필 목록"
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
        private val DAILY_BRIEFING_HINTS = setOf(
            "daily briefing", "아침 브리핑", "데일리 브리핑", "daily digest", "오늘의 jira 브리핑", "오늘 jira 브리핑"
        )
        private val JIRA_PROJECT_SUMMARY_HINTS = setOf(
            "recent", "latest", "summary", "summarize", "최근", "요약", "정리", "브리핑"
        )
        private val MY_WORK_HINTS = setOf("my open", "assigned to me", "내 이슈", "내가 담당", "내 오픈")
        private val SEARCH_HINTS = setOf("search", "찾아", "검색", "look up", "find")
        private val TRANSITION_HINTS = setOf("transition", "상태 전이", "전이", "possible states")
        private val PR_HINTS = setOf("pull request", "pr", "리뷰")
        private val REVIEW_SLA_HINTS = setOf("sla", "응답 지연", "리뷰 sla")
        private val REVIEW_QUEUE_HINTS = setOf("queue", "대기열", "리뷰가 필요한", "검토가 필요한", "review needed")
        private val REVIEW_RISK_HINTS = setOf("review risk", "리뷰 리스크", "코드 리뷰 리스크")
        private val MY_REVIEW_HINTS = setOf(
            "내가 검토",
            "검토해야",
            "review for me",
            "needs review",
            "리뷰가 필요한",
            "검토가 필요한"
        )
        private val MISSING_ASSIGNEE_HINTS = setOf("담당자가 없는", "담당자 없는", "미할당", "unassigned", "assignee 없는")
        private val STALE_HINTS = setOf("stale", "오래된", "방치된")
        private val BRANCH_HINTS = setOf("branch", "브랜치")
        private val REPOSITORY_HINTS = setOf("repository", "repo", "저장소")
        private val VALIDATE_HINTS = setOf("validate", "검증", "유효성")
        private val SCHEMA_HINTS = setOf("schema", "스키마", "model", "dto")
        private val DETAIL_HINTS = setOf("detail", "상세", "parameter", "response", "security")
        private val SUMMARY_HINTS = setOf("summary", "summarize", "요약", "정리")
        private val LIST_HINTS = setOf("loaded specs", "list specs", "목록", "list")
        private val LOADED_HINTS = setOf("loaded", "로드된", "현재 로드된")
        private val REMOVE_HINTS = setOf("remove", "삭제")
        private val WRONG_ENDPOINT_HINTS = setOf("wrong endpoint", "invalid endpoint", "잘못된 endpoint", "없는 endpoint")
        private val OPENAPI_URL_REGEX = WorkContextPatterns.OPENAPI_URL_REGEX

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
