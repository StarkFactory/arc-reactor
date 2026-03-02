package com.arc.reactor.slack.handler

import com.arc.reactor.agent.model.AgentResult

internal object SlackResponseTextFormatter {
    private val genericRefusalPatterns = listOf(
        Regex("요청하신 작업을 수행할 수 없습니다"),
        Regex("어떤 작업을 해야 하는지 알려주시면"),
        Regex("cannot (fulfill|complete) (this )?request", RegexOption.IGNORE_CASE),
        Regex("unable to (help|proceed)", RegexOption.IGNORE_CASE)
    )

    fun fromResult(result: AgentResult, originalPrompt: String): String {
        if (!result.success) {
            return ":warning: ${result.errorMessage ?: "An error occurred while processing your request."}"
        }
        val content = result.content?.trim().orEmpty()
        if (content.isBlank()) {
            return "I processed your request but have no response."
        }
        if (isGenericRefusal(content)) {
            return buildBestEffortFallback(originalPrompt)
        }
        return content
    }

    private fun isGenericRefusal(content: String): Boolean =
        genericRefusalPatterns.any { it.containsMatchIn(content) }

    private fun buildBestEffortFallback(prompt: String): String {
        if (looksLikeTaskPlanning(prompt)) {
            return """
                바로 실행 가능한 초안으로 정리합니다.
                1. 오늘 반드시 끝낼 핵심 작업 1개를 먼저 확정합니다.
                2. 그 다음 우선순위 작업 2개를 60분 단위로 배치합니다.
                3. 마지막으로 10분 점검 슬롯을 예약해 지연 요인을 정리합니다.
                필요하면 현재 업무 맥락(프로젝트/마감일)을 주시면 바로 맞춤형으로 다시 정리해드릴게요.
            """.trimIndent()
        }
        return """
            요청을 처리하기에 충분한 실시간 맥락이 없어도, 우선 실행 가능한 답을 먼저 드립니다.
            - 목표를 한 줄로 확정
            - 다음 행동 1개를 30분 내 완료 가능한 크기로 분해
            - 완료 기준을 한 줄로 정의
            필요한 맥락을 알려주시면 바로 구체화하겠습니다.
        """.trimIndent()
    }

    private fun looksLikeTaskPlanning(prompt: String): Boolean {
        val lowercase = prompt.lowercase()
        return listOf("할 일", "today", "task", "priority", "my-work", "mywork")
            .any { lowercase.contains(it) }
    }
}
