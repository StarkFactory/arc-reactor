package com.arc.reactor.slack.handler

/**
 * 슬래시 명령의 파싱된 인텐트를 표현하는 봉인(sealed) 인터페이스.
 *
 * @see SlackSlashIntentParser
 */
internal sealed interface SlackSlashIntent {
    data class Agent(
        val prompt: String,
        val mode: Mode
    ) : SlackSlashIntent {
        enum class Mode {
            GENERAL,
            BRIEF,
            MY_WORK
        }
    }

    data class ReminderAdd(val text: String) : SlackSlashIntent

    data object ReminderList : SlackSlashIntent

    data class ReminderDone(val id: Int) : SlackSlashIntent

    data object ReminderClear : SlackSlashIntent

    data object Help : SlackSlashIntent
}

/**
 * 슬래시 명령 텍스트를 [SlackSlashIntent]로 파싱하는 파서.
 *
 * 지원 인텐트: help, brief, my-work, remind (add/list/done/clear), 일반 에이전트 질의.
 * 한국어·영어 키워드를 모두 지원한다.
 */
internal object SlackSlashIntentParser {
    private val helpRegex = Regex("^(help|도움말|도움|commands)$", RegexOption.IGNORE_CASE)
    private val briefRegex = Regex("^(brief|브리프)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
    private val myWorkRegex = Regex("^(my-work|mywork|내업무)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
    private val remindRegex = Regex("^(remind|리마인드)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
    private val doneRegex = Regex("^(done|완료)\\s+(\\d+)$", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): SlackSlashIntent {
        val prompt = rawText.trim()
        if (helpRegex.matches(prompt)) return SlackSlashIntent.Help
        parseBrief(prompt)?.let { return it }
        parseMyWork(prompt)?.let { return it }
        parseReminder(prompt)?.let { return it }
        return SlackSlashIntent.Agent(prompt = prompt, mode = SlackSlashIntent.Agent.Mode.GENERAL)
    }

    private fun parseBrief(prompt: String): SlackSlashIntent.Agent? {
        val match = briefRegex.matchEntire(prompt) ?: return null
        val focus = match.groupValues.getOrNull(2).orEmpty().trim()
        return SlackSlashIntent.Agent(prompt = buildBriefPrompt(focus), mode = SlackSlashIntent.Agent.Mode.BRIEF)
    }

    private fun parseMyWork(prompt: String): SlackSlashIntent.Agent? {
        val match = myWorkRegex.matchEntire(prompt) ?: return null
        val scope = match.groupValues.getOrNull(2).orEmpty().trim()
        return SlackSlashIntent.Agent(prompt = buildMyWorkPrompt(scope), mode = SlackSlashIntent.Agent.Mode.MY_WORK)
    }

    private fun parseReminder(prompt: String): SlackSlashIntent? {
        val match = remindRegex.matchEntire(prompt) ?: return null
        val argument = match.groupValues.getOrNull(2).orEmpty().trim()
        if (argument.isBlank() || argument.equals("list", ignoreCase = true) || argument == "목록") {
            return SlackSlashIntent.ReminderList
        }
        if (argument.equals("clear", ignoreCase = true) || argument == "전체삭제") {
            return SlackSlashIntent.ReminderClear
        }
        val doneMatch = doneRegex.matchEntire(argument)
        if (doneMatch != null) {
            val id = doneMatch.groupValues[2].toIntOrNull() ?: return SlackSlashIntent.ReminderList
            return SlackSlashIntent.ReminderDone(id)
        }
        return SlackSlashIntent.ReminderAdd(argument)
    }

    private fun buildBriefPrompt(focus: String): String {
        val normalizedFocus = focus.ifBlank { "today's priorities" }
        return """
            Create a personal daily brief for the user.
            Focus: $normalizedFocus

            Requirements:
            - Provide exactly 3 bullet priorities.
            - Include 1 risk/blocker check.
            - Include 1 concise next action for the next 60 minutes.
            - If workspace-specific data is unavailable, make reasonable assumptions and state them briefly.
        """.trimIndent()
    }

    private fun buildMyWorkPrompt(scope: String): String {
        val normalizedScope = scope.ifBlank { "current assigned work items" }
        return """
            Summarize my work status as my personal assistant.
            Scope: $normalizedScope

            Requirements:
            - Group into: In Progress, Waiting, Next.
            - Keep each group to at most 3 bullets.
            - Highlight one item that should be finished first.
            - If no live data is available, provide a practical checklist template I can fill in quickly.
        """.trimIndent()
    }
}
