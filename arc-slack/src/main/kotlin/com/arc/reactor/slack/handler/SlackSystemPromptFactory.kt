package com.arc.reactor.slack.handler

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Slack 에이전트용 시스템 프롬프트를 생성하는 팩토리.
 *
 * DB 페르소나(Admin 관리)와 파일 기반 시스템 규칙(개발팀 관리)을 조합한다.
 * - DB 페르소나: 정체성, 회사 정보, 응답 톤 등 Admin이 변경 가능한 부분
 * - 파일 규칙: 안전, 포맷, 환각 방지 등 고정 시스템 규칙
 *
 * @see DefaultSlackEventHandler
 * @see DefaultSlackCommandHandler
 */
object SlackSystemPromptFactory {

    /**
     * 시스템 고정 프롬프트 섹션 (개발팀 관리, 파일 기반).
     * Admin이 변경할 수 없는 안전/포맷/정확성 규칙.
     */
    private val SYSTEM_SECTIONS = listOf(
        "accuracy",
        "tools",
        "format-slack",
        "safety"
    )

    /** 캐시된 시스템 규칙 섹션 (앱 기동 시 1회 로드) */
    private val sectionCache: Map<String, String> by lazy {
        val loaded = mutableMapOf<String, String>()
        val allSections = SYSTEM_SECTIONS + listOf("cross-tool", "proactive")
        for (name in allSections) {
            val content = loadSection(name)
            if (content != null) {
                loaded[name] = content
            } else {
                logger.warn { "시스템 프롬프트 섹션 파일 로드 실패: prompts/$name.md" }
            }
        }
        logger.info { "시스템 프롬프트 섹션 ${loaded.size}개 로드 완료: ${loaded.keys.sorted()}" }
        loaded
    }

    /**
     * DB 페르소나 프롬프트 + 시스템 고정 규칙을 조합하여 최종 시스템 프롬프트를 생성한다.
     *
     * @param personaPrompt DB 페르소나의 systemPrompt (Admin 관리). null이면 기본 프롬프트 사용.
     * @param defaultProvider LLM 프로바이더 이름
     * @param connectedToolSummary 연결된 MCP 도구 요약
     */
    fun build(
        personaPrompt: String?,
        defaultProvider: String,
        connectedToolSummary: String? = null
    ): String {
        val provider = defaultProvider.ifBlank { "configured backend model" }
        return buildString {
            // 1. DB 페르소나 프롬프트 (Admin 관리 영역)
            val persona = personaPrompt?.replace("{{provider}}", provider)
            if (!persona.isNullOrBlank()) {
                append(persona)
                append("\n\n")
            }

            // 2. 시스템 고정 규칙 (개발팀 관리 영역)
            for (section in SYSTEM_SECTIONS) {
                val content = sectionCache[section] ?: continue
                append(content.replace("{{provider}}", provider))
                append("\n\n")
            }

            // 3. 교차 도구 연계 (MCP 서버 연결 시만)
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

    /** 하위 호환: personaPrompt 없이 호출 시 파일 기반 identity + rules 사용 */
    fun build(defaultProvider: String): String =
        build(personaPrompt = null, defaultProvider = defaultProvider)

    /** 하위 호환: personaPrompt 없이 + 도구 요약 */
    fun build(defaultProvider: String, connectedToolSummary: String?): String =
        build(personaPrompt = null, defaultProvider = defaultProvider, connectedToolSummary = connectedToolSummary)

    fun buildProactive(
        personaPrompt: String?,
        defaultProvider: String,
        connectedToolSummary: String?
    ): String {
        val base = build(personaPrompt, defaultProvider, connectedToolSummary)
        val proactive = sectionCache["proactive"] ?: return base
        return "$base\n\n$proactive"
    }

    /** 하위 호환 */
    fun buildProactive(defaultProvider: String, connectedToolSummary: String?): String =
        buildProactive(personaPrompt = null, defaultProvider = defaultProvider, connectedToolSummary = connectedToolSummary)

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
            logger.error(e) { "시스템 프롬프트 섹션 파일 읽기 실패: $name" }
            null
        }
    }
}
