package com.arc.reactor.slack.handler

import com.arc.reactor.agent.model.AgentResult

/**
 * 에이전트 실행 결과를 Slack Block Kit 형식으로 변환하는 포매터.
 *
 * 응답 본문은 mrkdwn section 블록으로, 출처는 context 블록으로 변환하여
 * 기업 사용자에게 보기 좋은 구조화된 응답을 제공한다.
 *
 * ## Block Kit 구조
 * 1. 본문 section (mrkdwn) — 최대 3000자, 초과 시 분할
 * 2. divider — 출처가 있을 때만
 * 3. 출처 context 블록 — 아이콘 + 클릭 가능 링크
 *
 * @see SlackResponseTextFormatter
 */
internal object SlackBlockKitFormatter {

    /** Slack section 블록 텍스트 최대 길이 */
    private const val SECTION_TEXT_LIMIT = 3000

    /** 출처 블록에 표시할 최대 출처 수 */
    private const val MAX_SOURCE_LINKS = 8

    /** 도구 패밀리별 이모지 매핑 */
    private val TOOL_EMOJI_MAP = mapOf(
        "confluence" to ":confluence:",
        "jira" to ":jira:",
        "bitbucket" to ":bitbucket:",
        "swagger" to ":swagger:",
        "spec" to ":swagger:",
        "work" to ":calendar:"
    )

    /**
     * [AgentResult]를 Block Kit 블록 목록으로 변환한다.
     *
     * @param result 에이전트 실행 결과
     * @param originalPrompt 원본 사용자 질문
     * @return Block Kit 블록 목록. 블록이 불필요하면 null 반환 (plain text 폴백)
     */
    fun buildBlocks(result: AgentResult, originalPrompt: String): List<Map<String, Any>>? {
        if (!result.success) return null

        val content = result.content?.trim().orEmpty()
        if (content.isBlank()) return null

        val sources = extractVerifiedSources(result)
        val blocks = mutableListOf<Map<String, Any>>()

        // 본문 — 출처 블록이 이미 텍스트에 포함되어 있으면 제거 후 Block Kit으로 분리
        val cleanContent = stripTrailingSourcesBlock(content)
        addContentBlocks(blocks, cleanContent)

        // 출처가 있으면 divider + context 블록 추가
        if (sources.isNotEmpty()) {
            blocks.add(mapOf("type" to "divider"))
            addSourcesContextBlock(blocks, sources)
        }

        // 블록이 본문 하나뿐이면 plain text와 차이 없으므로 null 반환
        return if (blocks.size <= 1) null else blocks
    }

    /**
     * [AgentResult.metadata]에서 verifiedSources를 추출한다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractVerifiedSources(result: AgentResult): List<SourceInfo> {
        val rawSources = result.metadata["verifiedSources"] as? List<*> ?: return emptyList()
        return rawSources.mapNotNull { raw ->
            when (raw) {
                is Map<*, *> -> {
                    val title = raw["title"]?.toString() ?: return@mapNotNull null
                    val url = raw["url"]?.toString() ?: return@mapNotNull null
                    val toolName = raw["toolName"]?.toString()
                    SourceInfo(title, url, toolName)
                }
                else -> null
            }
        }.distinctBy { it.url }.take(MAX_SOURCE_LINKS)
    }

    /** 본문을 section 블록으로 추가한다. 3000자 초과 시 분할. */
    private fun addContentBlocks(blocks: MutableList<Map<String, Any>>, content: String) {
        if (content.length <= SECTION_TEXT_LIMIT) {
            blocks.add(sectionBlock(content))
            return
        }
        // 줄 단위로 분할하여 3000자 이내 청크로 나눈다
        val lines = content.split('\n')
        val chunk = StringBuilder()
        for (line in lines) {
            if (chunk.length + line.length + 1 > SECTION_TEXT_LIMIT && chunk.isNotEmpty()) {
                blocks.add(sectionBlock(chunk.toString().trimEnd()))
                chunk.clear()
            }
            chunk.appendLine(line)
        }
        if (chunk.isNotEmpty()) {
            blocks.add(sectionBlock(chunk.toString().trimEnd()))
        }
    }

    /** 출처를 context 블록으로 추가한다. */
    private fun addSourcesContextBlock(
        blocks: MutableList<Map<String, Any>>,
        sources: List<SourceInfo>
    ) {
        val elements = mutableListOf<Map<String, Any>>()

        // 헤더 아이콘 + 라벨
        elements.add(mrkdwnElement(":link: *출처*"))

        for (source in sources) {
            val emoji = resolveEmoji(source.toolName)
            val safeTitle = source.title
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("&", "&amp;")
                .take(80)
            elements.add(mrkdwnElement("$emoji <${source.url}|$safeTitle>"))
        }

        // context 블록의 elements는 최대 10개
        for (chunk in elements.chunked(10)) {
            blocks.add(mapOf("type" to "context", "elements" to chunk))
        }
    }

    /** 도구 이름에서 이모지를 결정한다. */
    private fun resolveEmoji(toolName: String?): String {
        if (toolName == null) return ":page_facing_up:"
        val prefix = toolName.substringBefore('_').lowercase()
        return TOOL_EMOJI_MAP[prefix] ?: ":page_facing_up:"
    }

    /** 텍스트 끝에 있는 "출처\n- [...](...)" 블록을 제거한다. */
    private fun stripTrailingSourcesBlock(content: String): String {
        val trimmed = content.trimEnd()
        val pattern = Regex("(?m)^\\s*(?:\\*\\*)?(Sources|출처)(?:\\*\\*)?\\s*$")
        val matches = pattern.findAll(trimmed).toList()
        if (matches.isEmpty()) return trimmed
        val first = matches.first()
        return trimmed.substring(0, first.range.first).trimEnd()
    }

    private fun sectionBlock(text: String): Map<String, Any> = mapOf(
        "type" to "section",
        "text" to mapOf("type" to "mrkdwn", "text" to text)
    )

    private fun mrkdwnElement(text: String): Map<String, Any> = mapOf(
        "type" to "mrkdwn",
        "text" to text
    )

    /** 출처 정보 내부 DTO */
    private data class SourceInfo(
        val title: String,
        val url: String,
        val toolName: String?
    )
}
