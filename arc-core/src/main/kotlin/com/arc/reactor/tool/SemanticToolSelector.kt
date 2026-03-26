package com.arc.reactor.tool

import com.arc.reactor.agent.config.ToolRouteMatchEngine
import com.arc.reactor.agent.config.ToolRoutingConfig
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
 * ## Deterministic Routing
 * Before semantic selection, checks tool-routing.yml for deterministic category matches.
 * Keywords and preferred tools are loaded from the unified config — no hardcoded constants.
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
 * @param embeddingModel Spring AI embedding model for vectorization
 * @param similarityThreshold Minimum cosine similarity to include a tool (0.0 to 1.0)
 * @param maxResults Maximum number of tools to return
 * @param routingConfig Tool routing configuration (loaded from tool-routing.yml)
 *
 * @see ToolSelector for the interface contract
 */
class SemanticToolSelector(
    private val embeddingModel: EmbeddingModel,
    private val similarityThreshold: Double = 0.3,
    private val maxResults: Int = 10,
    selectionCacheMaxSize: Long = 512,
    selectionCacheTtlMinutes: Long = 10,
    private val routingConfig: ToolRoutingConfig = ToolRoutingConfig.loadFromClasspath()
) : ToolSelector {

    /** Cache: tool name -> embedding vector. Invalidated when tool list changes. */
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()
    /** synchronized 대신 ReentrantLock 사용 — 코루틴 carrier 스레드 차단 방지 (IO 전환 후 lock) */
    private val refreshLock = ReentrantLock()
    private val semanticSelectionCache: Cache<SemanticSelectionCacheKey, List<String>> =
        Caffeine.newBuilder()
            .maximumSize(selectionCacheMaxSize)
            .expireAfterWrite(selectionCacheTtlMinutes, TimeUnit.MINUTES)
            .build()

    /** Fingerprint of the last tool list (to detect changes). */
    @Volatile
    private var lastToolFingerprint: Int = 0

    override fun select(
        prompt: String,
        availableTools: List<ToolCallback>
    ): List<ToolCallback> {
        if (availableTools.isEmpty()) return emptyList()
        selectDeterministicallyIfPossible(prompt, availableTools)?.let { fastPath ->
            logger.debug {
                "Deterministic tool selection fast-path selected " +
                    "${fastPath.size}/${availableTools.size} tools"
            }
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

    // ── Semantic selection ──

    private fun selectSemantically(
        prompt: String,
        availableTools: List<ToolCallback>
    ): List<ToolCallback> {
        val fingerprint = toolFingerprint(availableTools)
        refreshEmbeddingsIfNeeded(availableTools, fingerprint)

        val cacheKey = SemanticSelectionCacheKey(
            prompt = prompt,
            toolFingerprint = fingerprint,
            similarityThreshold = similarityThreshold,
            maxResults = maxResults
        )
        resolveCachedSelection(cacheKey, availableTools)?.let { return it }

        val promptEmbedding = embed(prompt)
        val scored = availableTools.map { tool ->
            val toolEmbedding = embeddingCache[tool.name]
                ?: embed(buildToolText(tool)).also { embeddingCache[tool.name] = it }
            tool to cosineSimilarity(promptEmbedding, toolEmbedding)
        }

        val selected = scored
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }

        val baseSelection = if (selected.isEmpty()) {
            logger.debug {
                "No tools above threshold $similarityThreshold, " +
                    "returning all ${availableTools.size} tools"
            }
            availableTools
        } else {
            selected
        }

        logger.debug {
            val names = scored.sortedByDescending { it.second }.take(5)
                .joinToString {
                    "${it.first.name}(${String.format("%.3f", it.second)})"
                }
            "Semantic tool selection: top scores = [$names], " +
                "selected ${baseSelection.size}/${availableTools.size}"
        }

        val resolved = applyDeterministicRouting(prompt, baseSelection, availableTools)
        semanticSelectionCache.put(cacheKey, resolved.map(ToolCallback::name))
        return resolved
    }

    private fun resolveCachedSelection(
        cacheKey: SemanticSelectionCacheKey,
        availableTools: List<ToolCallback>
    ): List<ToolCallback>? {
        val cachedToolNames =
            semanticSelectionCache.getIfPresent(cacheKey) ?: return null
        val availableByName = availableTools.associateBy { it.name }
        val resolved = cachedToolNames.mapNotNull(availableByName::get)
        if (resolved.size != cachedToolNames.size) {
            semanticSelectionCache.invalidate(cacheKey)
            logger.debug {
                "Invalidated stale semantic selection cache entry " +
                    "due to tool mismatch"
            }
            return null
        }
        logger.debug {
            "Semantic tool selection exact cache hit selected " +
                "${resolved.size}/${availableTools.size} tools"
        }
        return resolved
    }

    // ── Deterministic routing (fast-path, pre-semantic) ──

    private fun selectDeterministicallyIfPossible(
        prompt: String,
        availableTools: List<ToolCallback>
    ): List<ToolCallback>? {
        val availableByName = availableTools.associateBy { it.name }

        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(prompt)) {
            return preferredReadOnlyMutationEvidenceTools(prompt, availableByName)
        }

        return resolveFirstMatchingRoute(prompt, availableByName, DETERMINISTIC_CATEGORIES)
    }

    // ── Deterministic routing (post-semantic, overlay) ──

    private fun applyDeterministicRouting(
        prompt: String,
        selected: List<ToolCallback>,
        availableTools: List<ToolCallback>
    ): List<ToolCallback> {
        val availableByName = availableTools.associateBy { it.name }

        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(prompt)) {
            return preferredReadOnlyMutationEvidenceTools(prompt, availableByName)
        }

        resolveFirstMatchingRoute(prompt, availableByName, NON_CONFLUENCE_CATEGORIES)
            ?.let { return it }

        return applyConfluenceRouting(prompt, selected, availableByName)
    }

    // ── Config-driven route resolution ──

    private fun resolveFirstMatchingRoute(
        prompt: String,
        availableByName: Map<String, ToolCallback>,
        categories: List<String>
    ): List<ToolCallback>? {
        for (category in categories) {
            val match = ToolRouteMatchEngine.findFirstMatch(
                category, prompt, routingConfig
            ) ?: continue
            if (match.preferredTools.isEmpty()) continue
            val resolved = match.preferredTools
                .mapNotNull(availableByName::get)
                .take(maxResults)
            if (resolved.isNotEmpty()) return resolved
        }
        return null
    }

    // ── Confluence routing (special handling for answer vs discovery) ──

    private fun applyConfluenceRouting(
        prompt: String,
        selected: List<ToolCallback>,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val confluenceMatch = ToolRouteMatchEngine.findFirstMatch(
            "confluence", prompt, routingConfig
        ) ?: return selected

        if (confluenceMatch.preferredTools.isEmpty()) return selected

        val preferred = confluenceMatch.preferredTools
            .mapNotNull(availableByName::get)
        if (preferred.isEmpty()) return selected

        if (confluenceMatch.id in NARROW_CONFLUENCE_ROUTES) {
            return preferred.take(maxResults)
        }

        val filtered = selected.filterNot { it.name in LOW_LEVEL_CONFLUENCE_TOOLS }
        val ordered = LinkedHashMap<String, ToolCallback>()
        preferred.forEach { ordered[it.name] = it }
        filtered.forEach { ordered.putIfAbsent(it.name, it) }
        return ordered.values.take(maxResults)
    }

    // ── Workspace mutation (special: read-only evidence tools) ──

    private fun preferredReadOnlyMutationEvidenceTools(
        prompt: String,
        availableByName: Map<String, ToolCallback>
    ): List<ToolCallback> {
        val normalized = prompt.lowercase()

        if (ISSUE_KEY_REGEX.containsMatchIn(prompt.uppercase())) {
            val ordered = if (normalized.contains("담당자") ||
                normalized.contains("재할당")
            ) {
                listOf("work_owner_lookup", "jira_get_issue")
            } else {
                listOf("jira_get_issue", "work_owner_lookup")
            }
            return ordered.mapNotNull(availableByName::get).take(1)
        }

        if (isConfluenceMutationTarget(normalized) &&
            availableByName.containsKey("confluence_get_page")
        ) {
            return listOfNotNull(availableByName["confluence_get_page"])
        }

        if (isPrMutationTarget(normalized) &&
            availableByName.containsKey("bitbucket_get_pr")
        ) {
            return listOfNotNull(availableByName["bitbucket_get_pr"])
        }

        return emptyList()
    }

    private fun isConfluenceMutationTarget(normalized: String): Boolean {
        return normalized.contains("confluence") ||
            normalized.contains("페이지") ||
            normalized.contains("page")
    }

    private fun isPrMutationTarget(normalized: String): Boolean {
        return normalized.contains("pull request") ||
            normalized.contains("pr")
    }

    // ── Embedding utilities ──

    /**
     * 도구 목록 변경 시 임베딩을 갱신한다.
     *
     * [ToolSelector.select]가 non-suspend이므로 코루틴 Mutex를 쓸 수 없다.
     * ReentrantLock + [runBlocking](Dispatchers.IO)로 블로킹 HTTP 호출을
     * IO 스레드 풀에서 실행하여 코루틴 carrier 스레드 차단을 방지한다.
     */
    private fun refreshEmbeddingsIfNeeded(
        tools: List<ToolCallback>,
        fingerprint: Int = toolFingerprint(tools)
    ) {
        if (fingerprint == lastToolFingerprint) return
        refreshLock.lock()
        try {
            if (fingerprint == lastToolFingerprint) return
            val refreshed = runBlocking(Dispatchers.IO) {
                embedBatch(tools.map(::buildToolText))
            }.mapIndexed { index, embedding ->
                tools[index].name to embedding
            }.toMap(LinkedHashMap())
            embeddingCache.clear()
            embeddingCache.putAll(refreshed)
            semanticSelectionCache.invalidateAll()
            lastToolFingerprint = fingerprint
            logger.info {
                "Refreshed semantic embeddings for ${tools.size} tools"
            }
        } finally {
            refreshLock.unlock()
        }
    }

    private fun buildToolText(tool: ToolCallback): String {
        return "${tool.name}: ${tool.description}"
    }

    /**
     * 도구 목록 변경 감지용 fingerprint — 논리적 동등성 기반.
     *
     * 기존 identityHashCode 방식의 문제:
     * - GC 후 다른 객체가 같은 해시 → 충돌로 stale 캐시
     * - MCP 재연결 시 같은 도구가 다른 인스턴스 → 불필요한 재임베딩
     *
     * 도구 이름으로 정렬 후 name+description 해시로 논리적 변경만 감지.
     */
    private fun toolFingerprint(tools: List<ToolCallback>): Int {
        return tools.sortedBy { it.name }
            .fold(17) { hash, tool ->
                31 * hash + tool.name.hashCode() + 37 * tool.description.hashCode()
            }
    }

    /** 단일 텍스트 임베딩 — 블로킹 HTTP 호출이므로 IO 디스패처에서 실행한다. */
    private fun embed(text: String): FloatArray = runBlocking(Dispatchers.IO) {
        embeddingModel.embed(text)
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> =
        embeddingModel.embed(texts)

    private data class SemanticSelectionCacheKey(
        val prompt: String,
        val toolFingerprint: Int,
        val similarityThreshold: Double,
        val maxResults: Int
    )

    companion object {
        private val ISSUE_KEY_REGEX = ToolRoutingConfig.resolveRegex("ISSUE_KEY")

        private val NARROW_CONFLUENCE_ROUTES = setOf(
            "confluence_answer", "confluence_discovery", "confluence_page_body"
        )

        private val LOW_LEVEL_CONFLUENCE_TOOLS = setOf(
            "confluence_search", "confluence_search_by_text"
        )

        /** Categories to check in deterministic fast-path (pre-semantic). */
        private val DETERMINISTIC_CATEGORIES = listOf(
            "workContext", "work", "jira", "bitbucket", "swagger", "confluence"
        )

        /** Categories to check in deterministic overlay (post-semantic, excluding confluence). */
        private val NON_CONFLUENCE_CATEGORIES = listOf(
            "workContext", "work", "jira", "bitbucket", "swagger"
        )

        /**
         * Compute cosine similarity between two vectors.
         * Returns a value between -1.0 and 1.0 (1.0 = identical direction).
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) {
                "Vector dimensions must match: ${a.size} vs ${b.size}"
            }
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
