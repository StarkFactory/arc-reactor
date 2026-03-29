package com.arc.reactor.tool

import com.arc.reactor.agent.config.ToolRouteMatchEngine
import com.arc.reactor.agent.config.ToolRoutingConfig
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private val logger = KotlinLogging.logger {}

/**
 * 시맨틱 도구 선택기
 *
 * 임베딩 기반 코사인 유사도를 사용하여 관련 도구를 선택한다.
 * 도구 설명은 임베딩 후 캐시하고, 사용자 프롬프트는 요청마다 임베딩한다.
 *
 * ## 동작 방식
 * 1. 첫 호출 시(또는 도구 목록 변경 시) 각 도구의 `name + description`을 임베딩
 * 2. 사용자 프롬프트를 임베딩
 * 3. 프롬프트와 각 도구 간 코사인 유사도 계산
 * 4. 유사도 임계값 이상인 도구를 관련성 순으로 반환
 * 5. 임계값을 충족하는 도구가 없으면 전체 도구를 폴백으로 반환
 *
 * ## 결정적 라우팅
 * 시맨틱 선택 전 tool-routing.yml에서 결정적 카테고리 매칭을 확인한다.
 * 키워드와 우선 도구는 통합 설정에서 로딩 — 하드코딩 상수 없음.
 *
 * ## 설정
 * ```yaml
 * arc:
 *   reactor:
 *     tool-selection:
 *       strategy: semantic
 *       similarity-threshold: 0.3
 *       max-results: 10
 * ```
 *
 * @param embeddingModel 벡터화를 위한 Spring AI 임베딩 모델
 * @param similarityThreshold 도구 포함 최소 코사인 유사도 (0.0 ~ 1.0)
 * @param maxResults 반환할 최대 도구 수
 * @param routingConfig tool-routing.yml에서 로딩한 도구 라우팅 설정
 *
 * @see ToolSelector 인터페이스 계약
 */
class SemanticToolSelector(
    private val embeddingModel: EmbeddingModel,
    private val similarityThreshold: Double = 0.3,
    private val maxResults: Int = 10,
    selectionCacheMaxSize: Long = 512,
    selectionCacheTtlMinutes: Long = 10,
    private val routingConfig: ToolRoutingConfig = ToolRoutingConfig.loadFromClasspath()
) : ToolSelector {

    /** 도구 이름 → 임베딩 벡터 캐시. 도구 목록 변경 시 무효화된다. */
    private val embeddingCache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(1024)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()
    /** synchronized 대신 ReentrantLock 사용 — 코루틴 carrier 스레드 차단 방지 (IO 전환 후 lock). */
    private val refreshLock = ReentrantLock()
    private val semanticSelectionCache: Cache<SemanticSelectionCacheKey, List<String>> =
        Caffeine.newBuilder()
            .maximumSize(selectionCacheMaxSize)
            .expireAfterWrite(selectionCacheTtlMinutes, TimeUnit.MINUTES)
            .build()

    /** 마지막 도구 목록의 fingerprint (변경 감지용). */
    @Volatile
    private var lastToolFingerprint: Int = 0

    override fun select(
        prompt: String,
        availableTools: List<ToolCallback>
    ): List<ToolCallback> {
        if (availableTools.isEmpty()) return emptyList()
        selectDeterministicallyIfPossible(prompt, availableTools)?.let { fastPath ->
            logger.debug {
                "결정적 도구 선택 fast-path: " +
                    "${fastPath.size}/${availableTools.size}개 도구 선택됨"
            }
            return fastPath
        }
        if (availableTools.size <= maxResults) {
            return applyDeterministicRouting(prompt, availableTools, availableTools)
        }

        return try {
            selectSemantically(prompt, availableTools)
        } catch (e: Exception) {
            logger.warn(e) { "시맨틱 도구 선택 실패, 전체 도구로 폴백" }
            applyDeterministicRouting(prompt, availableTools, availableTools)
        }
    }

    fun prewarm(availableTools: List<ToolCallback>) {
        if (availableTools.isEmpty()) return
        refreshEmbeddingsIfNeeded(availableTools)
    }

    // ── 시맨틱 선택 ──

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
            val toolEmbedding = embeddingCache.getIfPresent(tool.name)
                ?: embed(buildToolText(tool)).also { embeddingCache.put(tool.name, it) }
            tool to cosineSimilarity(promptEmbedding, toolEmbedding)
        }

        val selected = scored
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }

        val baseSelection = if (selected.isEmpty()) {
            logger.debug {
                "임계값 $similarityThreshold 이상 도구 없음, " +
                    "전체 ${availableTools.size}개 도구 반환"
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
            "시맨틱 도구 선택: 상위 점수 = [$names], " +
                "${baseSelection.size}/${availableTools.size}개 선택됨"
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
                "도구 불일치로 시맨틱 선택 캐시 항목 무효화"
            }
            return null
        }
        logger.debug {
            "시맨틱 도구 선택 캐시 히트: " +
                "${resolved.size}/${availableTools.size}개 도구 선택됨"
        }
        return resolved
    }

    // ── 결정적 라우팅 (fast-path, 시맨틱 선택 전) ──

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

    // ── 결정적 라우팅 (시맨틱 선택 후, 오버레이) ──

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

    // ── 설정 기반 라우트 매칭 ──

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

    // ── Confluence 라우팅 (답변 vs 탐색 구분 처리) ──

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

    // ── 워크스페이스 변경 의도 (읽기 전용 증거 도구 우선) ──

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

    // ── 임베딩 유틸리티 ──

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
            embeddingCache.invalidateAll()
            embeddingCache.putAll(refreshed)
            semanticSelectionCache.invalidateAll()
            lastToolFingerprint = fingerprint
            logger.info {
                "${tools.size}개 도구의 시맨틱 임베딩 갱신 완료"
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

    /** 단일 텍스트를 임베딩한다. 블로킹 HTTP 호출이므로 IO 디스패처에서 실행한다. */
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

        /** 결정적 fast-path에서 확인할 카테고리 (시맨틱 선택 전). */
        private val DETERMINISTIC_CATEGORIES = listOf(
            "workContext", "work", "jira", "bitbucket", "swagger", "confluence"
        )

        /** 결정적 오버레이에서 확인할 카테고리 (시맨틱 선택 후, Confluence 제외). */
        private val NON_CONFLUENCE_CATEGORIES = listOf(
            "workContext", "work", "jira", "bitbucket", "swagger"
        )

        /**
         * 두 벡터 간 코사인 유사도를 계산한다.
         * 반환값 범위: -1.0 ~ 1.0 (1.0 = 동일 방향).
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
