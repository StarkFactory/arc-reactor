package com.arc.reactor.slack.handler

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Slack 에이전트용 시스템 프롬프트를 생성하는 팩토리.
 *
 * 프롬프트 내용은 classpath의 prompts 디렉토리 내 .md 파일에서 로드하고,
 * 이 팩토리는 섹션 조립 순서와 변수 치환만 담당한다.
 *
 * @see DefaultSlackEventHandler
 * @see DefaultSlackCommandHandler
 */
object SlackSystemPromptFactory {

    /** 프롬프트 섹션 파일 목록 (조립 순서) */
    private val BASE_SECTIONS = listOf(
        "identity",
        "rules",
        "accuracy",
        "tools",
        "format-slack",
        "safety"
    )

    /** 캐시된 프롬프트 섹션 (앱 기동 시 1회 로드) */
    private val sectionCache: Map<String, String> by lazy {
        val loaded = mutableMapOf<String, String>()
        val allSections = BASE_SECTIONS + listOf("cross-tool", "proactive")
        for (name in allSections) {
            val content = loadSection(name)
            if (content != null) {
                loaded[name] = content
            } else {
                logger.warn { "프롬프트 섹션 파일 로드 실패: prompts/$name.md" }
            }
        }
        logger.info { "프롬프트 섹션 ${loaded.size}개 로드 완료: ${loaded.keys.sorted()}" }
        loaded
    }

    fun build(defaultProvider: String): String =
        build(defaultProvider, connectedToolSummary = null)

    fun build(defaultProvider: String, connectedToolSummary: String?): String {
        val provider = defaultProvider.ifBlank { "configured backend model" }
        return buildString {
            for (section in BASE_SECTIONS) {
                val content = sectionCache[section] ?: continue
                append(content.replace("{{provider}}", provider))
                append("\n\n")
            }
            if (!connectedToolSummary.isNullOrBlank()) {
                val crossTool = sectionCache["cross-tool"]
                if (crossTool != null) {
                    append(crossTool)
                    append("\n\n")
                    append(connectedToolSummary)
                }
            }
        }.trimEnd()
    }

    fun buildProactive(defaultProvider: String, connectedToolSummary: String?): String {
        val base = build(defaultProvider, connectedToolSummary)
        val proactive = sectionCache["proactive"] ?: return base
        return "$base\n\n$proactive"
    }

    fun buildToolSummary(toolsByServer: Map<String, List<String>>): String? {
        if (toolsByServer.isEmpty()) return null
        return buildString {
            append("[Connected Workspace Tools]\n")
            for ((server, tools) in toolsByServer) {
                append("- $server: ${tools.joinToString(", ")}\n")
            }
        }.trimEnd()
    }

    private fun loadSection(name: String): String? {
        return try {
            val path = "prompts/$name.md"
            val stream = SlackSystemPromptFactory::class.java.classLoader.getResourceAsStream(path)
                ?: return null
            stream.bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            logger.error(e) { "프롬프트 섹션 파일 읽기 실패: $name" }
            null
        }
    }
}
